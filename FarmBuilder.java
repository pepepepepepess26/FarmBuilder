package com.example.farmbuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Бот-строитель. Каждый блок: тик A - повернуть камеру (и присесть, если ставим на сундук/воронку/печь),
 * тик B - поставить. Если камера сдвинулась между тиками - пауза.
 */
@EventBusSubscriber(modid = FarmBuilderMod.MODID, value = Dist.CLIENT)
public final class FarmBuilder {
    public enum State { IDLE, RUNNING, PAUSED }

    private static final double REACH = 4.3;
    private static final int DELAY = 3;

    public static State state = State.IDLE;
    private static Farms.Blueprint bp;
    private static List<Farms.Cell> cells = List.of();
    private static Level buildLevel;
    private static Direction facing = Direction.NORTH;

    private static Pending pending;
    private static int cooldown;
    private static int msgCooldown;
    private static boolean lockValid;
    private static float expYaw, expPitch;
    private static boolean sneaking, userSneak;
    private static final Map<BlockPos, Integer> attempts = new HashMap<>();
    private static final Set<BlockPos> skipped = new HashSet<>();

    private record Click(BlockPos target, Direction face, Vec3 hit) {}
    private record Pending(Farms.Cell cell, Click click) {}

    private FarmBuilder() {}

    // ---------------- управление ----------------

    public static boolean start(Farms.Blueprint b) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return false;
        HitResult hr = p.pick(6.0, 1.0F, false);
        if (hr.getType() != HitResult.Type.BLOCK) {
            msg("Наведите прицел на землю: это будет передний левый угол фермы");
            return false;
        }
        BlockHitResult bhr = (BlockHitResult) hr;
        BlockPos origin = bhr.getBlockPos().relative(bhr.getDirection());
        cancelInternal();
        bp = b;
        facing = p.getDirection();
        buildLevel = mc.level;
        cells = b.cells(origin, facing);
        attempts.clear();
        skipped.clear();
        state = State.RUNNING;
        msgCooldown = 0;

        int holes = 0;
        Direction right = facing.getClockWise();
        for (int x = 0; x < b.w; x++) {
            for (int z = 0; z < b.d; z++) {
                BlockPos g = origin.relative(right, x).relative(facing, z).below();
                if (mc.level.getBlockState(g).canBeReplaced()) holes++;
            }
        }
        msg(holes > 0 ? "Строю «" + b.title + "». Внимание, ям под фермой: " + holes
                : "Строю «" + b.title + "»");
        return true;
    }

    public static void toggle() {
        if (state == State.RUNNING) {
            pause("Пауза. Кнопка K или меню (дверь) - продолжить");
        } else if (state == State.PAUSED) {
            state = State.RUNNING;
            lockValid = false;
            msg("Продолжаю");
        } else {
            msg("Нет активной стройки");
        }
    }

    public static void cancel() {
        cancelInternal();
        msg("Стройка отменена");
    }

    private static void pause(String m) {
        state = State.PAUSED;
        pending = null;
        lockValid = false;
        releaseSneak(Minecraft.getInstance());
        msg(m);
    }

    private static void cancelInternal() {
        state = State.IDLE;
        pending = null;
        lockValid = false;
        cells = List.of();
        bp = null;
        releaseSneak(Minecraft.getInstance());
    }

    private static void finish() {
        String name = bp != null ? bp.title : "";
        String extra = skipped.isEmpty() ? "" : ". Пропущено блоков: " + skipped.size();
        cancelInternal();
        msg("Готово: " + name + extra);
    }

    public static String statusLine() {
        if (state == State.IDLE || bp == null) return "";
        Level lvl = Minecraft.getInstance().level;
        int total = cells.size(), left = 0;
        if (lvl != null) {
            for (Farms.Cell c : cells) {
                if (lvl.getBlockState(c.pos()).canBeReplaced() && !skipped.contains(c.pos())) left++;
            }
        }
        return (state == State.RUNNING ? "Строится: " : "На паузе: ") + bp.title
                + " (" + (total - left) + "/" + total + ")";
    }

    public static int countItems(Predicate<ItemStack> pred) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return 0;
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && pred.test(s)) n += s.getCount();
        return n;
    }

    // ---------------- цикл ----------------

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        tick();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null || mc.gameMode == null) {
            if (state != State.IDLE) cancelInternal();
            return;
        }
        while (FarmKeys.TOGGLE.consumeClick()) toggle();
        if (state == State.IDLE) return;
        if (lvl != buildLevel) {
            cancelInternal();
            msg("Стройка отменена (другой мир)");
            return;
        }
        if (state == State.PAUSED) return;
        if (mc.screen != null) return;

        if (lockValid && (Math.abs(Mth.wrapDegrees(p.getYRot() - expYaw)) > 0.15F
                || Math.abs(Mth.wrapDegrees(p.getXRot() - expPitch)) > 0.15F)) {
            pause("Пауза: вы двинули камеру. Кнопка K или меню (дверь) - продолжить");
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (pending != null) {
            place(mc, p, lvl);
            return;
        }
        chooseNext(mc, p, lvl);
    }

    private static void chooseNext(Minecraft mc, LocalPlayer p, Level lvl) {
        Vec3 eye = p.getEyePosition();
        Farms.Cell best = null;
        Click bestClick = null;
        double bestScore = Double.MAX_VALUE;
        Farms.Cell nearFar = null;
        double nearFarDist = Double.MAX_VALUE;
        int remaining = 0, far = 0, blocked = 0;
        Set<String> missing = new LinkedHashSet<>();

        for (Farms.Cell c : cells) {
            if (skipped.contains(c.pos())) continue;
            if (!lvl.getBlockState(c.pos()).canBeReplaced()) continue; // уже стоит
            remaining++;
            Click click = findClick(lvl, c);
            if (click == null) {
                blocked++;
                continue;
            }
            if (!hasItem(p, c.entry())) {
                missing.add(c.entry().label());
                continue;
            }
            double dist = eye.distanceTo(click.hit());
            if (dist > REACH) {
                far++;
                if (dist < nearFarDist) {
                    nearFarDist = dist;
                    nearFar = c;
                }
                continue;
            }
            if (p.getBoundingBox().intersects(new AABB(c.pos()))) {
                blocked++;
                continue;
            }
            double score = c.pos().getY() * 1000.0 + dist;
            if (score < bestScore) {
                bestScore = score;
                best = c;
                bestClick = click;
            }
        }

        if (remaining == 0) {
            finish();
            return;
        }

        if (best == null) {
            lockValid = false;
            releaseSneak(mc);
            if (msgCooldown-- <= 0) {
                msgCooldown = 30;
                StringBuilder sb = new StringBuilder();
                if (far > 0 && nearFar != null) {
                    BlockPos np = nearFar.pos();
                    sb.append("Подойдите ближе к ").append(np.getX()).append(' ').append(np.getY())
                            .append(' ').append(np.getZ()).append(" (осталось ").append(remaining).append(")");
                }
                if (!missing.isEmpty()) {
                    if (sb.length() > 0) sb.append(". ");
                    sb.append("Не хватает: ").append(String.join(", ", missing));
                }
                if (sb.length() == 0) sb.append("Жду: ").append(blocked).append(" блоков пока нельзя поставить");
                msg(sb.toString());
            }
            return;
        }

        if (msgCooldown-- <= 0) {
            msgCooldown = 40;
            msg("Строю: осталось " + remaining);
        }

        // повернуть камеру на точку клика
        Vec3 d = bestClick.hit().subtract(eye);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
        if (best.entry().lockYaw()) yaw = facing.toYRot(); // калитка встаёт вдоль забора
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        expYaw = p.getYRot();
        expPitch = p.getXRot();
        lockValid = true;

        boolean needSneak = lvl.getBlockEntity(bestClick.target()) != null;
        if (needSneak && !sneaking) {
            userSneak = mc.options.keyShift.isDown();
            mc.options.keyShift.setDown(true);
            sneaking = true;
        } else if (!needSneak) {
            releaseSneak(mc);
        }
        pending = new Pending(best, bestClick);
    }

    private static void place(Minecraft mc, LocalPlayer p, Level lvl) {
        Pending pd = pending;
        pending = null;
        Farms.Cell c = pd.cell();
        if (!lvl.getBlockState(c.pos()).canBeReplaced()) {
            releaseSneak(mc);
            return;
        }
        Click click = findClick(lvl, c);
        if (click == null || !click.target().equals(pd.click().target())) {
            releaseSneak(mc);
            return;
        }
        if (!c.entry().match().test(p.getMainHandItem())) {
            if (!selectItem(mc, p, c.entry())) {
                releaseSneak(mc);
                return;
            }
            if (!c.entry().match().test(p.getMainHandItem())) {
                pending = pd;
                cooldown = 1;
                return;
            }
        }
        BlockHitResult bhr = new BlockHitResult(click.hit(), click.face(), click.target(), false);
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
        if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
        int n = attempts.merge(c.pos(), 1, Integer::sum);
        if (n > 8) skipped.add(c.pos());
        releaseSneak(mc);
        cooldown = DELAY;
    }

    // ---------------- помощники ----------------

    private static void releaseSneak(Minecraft mc) {
        if (sneaking) {
            mc.options.keyShift.setDown(userSneak);
            sneaking = false;
        }
    }

    private static boolean solid(Level lvl, BlockPos t) {
        BlockState s = lvl.getBlockState(t);
        return !s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    private static Click make(BlockPos target, Direction face) {
        Vec3 hit = Vec3.atCenterOf(target).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new Click(target, face, hit);
    }

    /** К какому соседу прислониться и с какой стороны кликнуть. */
    private static Click findClick(Level lvl, Farms.Cell c) {
        if (c.hopperDir() != null) {
            // воронка смотрит в сторону клика: кликаем по блоку, в который она должна смотреть
            Direction d = c.hopperDir();
            BlockPos t = c.pos().relative(d);
            return solid(lvl, t) ? make(t, d.getOpposite()) : null;
        }
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos t = c.pos().relative(d);
            if (solid(lvl, t)) return make(t, d.getOpposite());
        }
        return null;
    }

    private static boolean hasItem(LocalPlayer p, Farms.Entry e) {
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && e.match().test(s)) return true;
        return false;
    }

    /** Берёт нужный предмет в руку (из хотбара или меняет местами с основным инвентарём). */
    private static boolean selectItem(Minecraft mc, LocalPlayer p, Farms.Entry e) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && e.match().test(s)) {
                inv.selected = i;
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && e.match().test(s)) {
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, i, inv.selected, ClickType.SWAP, p);
                return true;
            }
        }
        return false;
    }

    private static void msg(String text) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(text), true);
    }
}
