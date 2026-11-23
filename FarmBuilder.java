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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Бот-строитель. Каждый блок: тик A - повернуть камеру (и присесть, если ставим на сундук/воронку/печь),
 * тик B - поставить. Если блоки далеко - бот сам идёт (поиск пути), при нехватке высоты строит леса из земли
 * и потом их убирает. Сдвинули камеру / получили урон - пауза.
 */
@EventBusSubscriber(modid = FarmBuilderMod.MODID, value = Dist.CLIENT)
public final class FarmBuilder {
    public enum State { IDLE, RUNNING, PAUSED }

    private enum Phase { BUILD, RETURN, CLEAN }

    private static final double REACH = 4.3;
    private static final double WALK_REACH = 3.9;
    private static final int DELAY = 3;
    private static final Set<Item> SCAFFOLD_ITEMS = Set.of(
            Items.DIRT, Items.COARSE_DIRT, Items.NETHERRACK, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);

    public static State state = State.IDLE;
    private static Phase phase = Phase.BUILD;
    private static Farms.Blueprint bp;
    private static List<Farms.Cell> cells = List.of();
    private static Level buildLevel;
    private static Direction facing = Direction.NORTH;
    private static BlockPos startPos = BlockPos.ZERO;

    private static Pending pending;
    private static int cooldown;
    private static int msgCooldown;
    private static boolean lockValid;
    private static float expYaw, expPitch;
    private static boolean sneaking, userSneak;
    private static float lastHealth;
    private static final Map<BlockPos, Integer> attempts = new HashMap<>();
    private static final Set<BlockPos> skipped = new HashSet<>();

    // навигация
    private static List<Nav.Step> path;
    private static int pathIdx, stepTicks, stuckTicks, replans, abandoned;
    private static Vec3 lastPos;
    private static boolean keysHeld;
    private static final Set<BlockPos> scaffold = new HashSet<>();

    private record Click(BlockPos target, Direction face, Vec3 hit) {}
    private record Pending(Farms.Cell cell, Click click) {}
    private record Far(Click click, double score) {}

    private FarmBuilder() {}

    // ---------------- управление ----------------

    public static boolean start(Farms.Blueprint b) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return false;
        HitResult hr = p.pick(6.0, 1.0F, false);
        if (hr.getType() != HitResult.Type.BLOCK) {
            msg("Наведите прицел на землю: это будет передний левый угол постройки");
            return false;
        }
        BlockHitResult bhr = (BlockHitResult) hr;
        BlockPos origin;
        if (bhr.getDirection() == Direction.UP) {
            // снег/трава сверху не считаются землёй
            BlockPos g = bhr.getBlockPos();
            while (mc.level.getBlockState(g).canBeReplaced() && g.getY() > mc.level.getMinBuildHeight()) g = g.below();
            origin = g.above();
        } else {
            origin = bhr.getBlockPos().relative(bhr.getDirection());
        }
        cancelInternal();
        bp = b;
        facing = p.getDirection();
        buildLevel = mc.level;
        startPos = p.blockPosition();
        cells = b.cells(origin, facing);
        attempts.clear();
        skipped.clear();
        scaffold.clear();
        abandoned = 0;
        phase = Phase.BUILD;
        state = State.RUNNING;
        msgCooldown = 0;
        lastHealth = p.getHealth();

        int holes = 0;
        Direction right = facing.getClockWise();
        for (int x = 0; x < b.w; x++) {
            for (int z = 0; z < b.d; z++) {
                BlockPos g = origin.relative(right, x).relative(facing, z).below();
                if (mc.level.getBlockState(g).canBeReplaced()) holes++;
            }
        }
        msg(holes > 0 ? "Строю «" + b.title + "». Внимание, ям под постройкой: " + holes
                : "Строю «" + b.title + "»");
        return true;
    }

    public static void toggle() {
        if (state == State.RUNNING) {
            pause("Пауза. Кнопка K или меню (дверь) - продолжить");
        } else if (state == State.PAUSED) {
            state = State.RUNNING;
            lockValid = false;
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) lastHealth = p.getHealth();
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
        path = null;
        lockValid = false;
        stopActions(Minecraft.getInstance());
        msg(m);
    }

    private static void cancelInternal() {
        state = State.IDLE;
        pending = null;
        path = null;
        lockValid = false;
        cells = List.of();
        bp = null;
        stopActions(Minecraft.getInstance());
    }

    private static void done() {
        String name = bp != null ? bp.title : "";
        String extra = skipped.isEmpty() ? "" : ". Пропущено блоков: " + skipped.size();
        if (abandoned > 0) extra += ". Остались леса: " + abandoned + " (уберите вручную)";
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

        if (p.getHealth() < lastHealth - 0.01F) {
            lastHealth = p.getHealth();
            pause("Пауза: вы получили урон. Кнопка K или меню - продолжить");
            return;
        }
        lastHealth = p.getHealth();

        if (lockValid && (Math.abs(Mth.wrapDegrees(p.getYRot() - expYaw)) > 0.15F
                || Math.abs(Mth.wrapDegrees(p.getXRot() - expPitch)) > 0.15F)) {
            pause("Пауза: вы двинули камеру. Кнопка K или меню (дверь) - продолжить");
            return;
        }

        if (path != null) {
            navTick(mc, p, lvl);
            return;
        }
        switch (phase) {
            case BUILD -> buildTick(mc, p, lvl);
            case RETURN -> returnTick(mc, p, lvl);
            case CLEAN -> cleanTick(mc, p, lvl);
        }
    }

    // ---------------- стройка ----------------

    private static void buildTick(Minecraft mc, LocalPlayer p, Level lvl) {
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

    private static Set<BlockPos> unplaced(Level lvl) {
        Set<BlockPos> s = new HashSet<>();
        for (Farms.Cell c : cells) {
            if (!skipped.contains(c.pos()) && lvl.getBlockState(c.pos()).canBeReplaced()) s.add(c.pos());
        }
        return s;
    }

    private static void chooseNext(Minecraft mc, LocalPlayer p, Level lvl) {
        Vec3 eye = p.getEyePosition();
        Farms.Cell best = null;
        Click bestClick = null;
        double bestScore = Double.MAX_VALUE;
        List<Far> fars = new ArrayList<>();
        int remaining = 0, blocked = 0, standBlocked = 0;
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
            if (!hasItem(p, c.entry().match())) {
                missing.add(c.entry().label());
                continue;
            }
            double dist = eye.distanceTo(click.hit());
            double score = c.pos().getY() * 1000.0 + dist;
            if (dist > REACH) {
                fars.add(new Far(click, score));
                continue;
            }
            if (p.getBoundingBox().intersects(new AABB(c.pos()))) {
                standBlocked++;
                continue;
            }
            if (score < bestScore) {
                bestScore = score;
                best = c;
                bestClick = click;
            }
        }

        if (remaining == 0) {
            finishBuild(p);
            return;
        }

        if (best == null) {
            lockValid = false;
            releaseSneak(mc);
            // идём к дальним блокам
            if (!fars.isEmpty()) {
                fars.sort(Comparator.comparingDouble(Far::score));
                for (int i = 0; i < Math.min(4, fars.size()); i++) {
                    if (planNear(mc, p, lvl, fars.get(i).click().hit())) return;
                }
            } else if (standBlocked > 0) {
                if (planAway(mc, p, lvl)) return;
            }
            if (msgCooldown-- <= 0) {
                msgCooldown = 30;
                StringBuilder sb = new StringBuilder();
                if (!fars.isEmpty()) {
                    sb.append("Не нахожу путь к блокам (осталось ").append(remaining)
                            .append("). Нужна земля для лесов или свободное место");
                }
                if (!missing.isEmpty()) {
                    if (sb.length() > 0) sb.append(". ");
                    sb.append("Не хватает: ").append(String.join(", ", missing));
                }
                if (sb.length() == 0) {
                    sb.append("Жду: ").append(blocked + standBlocked).append(" блоков пока нельзя поставить");
                }
                msg(sb.toString());
            }
            return;
        }

        if (msgCooldown-- <= 0) {
            msgCooldown = 40;
            msg("Строю: осталось " + remaining);
        }

        Vec3 d = bestClick.hit().subtract(eye);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
        Direction yd = yawDirection(best.entry().yawDir());
        if (yd != null) yaw = yd.toYRot(); // ступени/калитка встают вдоль чертежа
        setLook(p, yaw, pitch);

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
            if (!selectPred(mc, p, c.entry().match())) {
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

    private static void finishBuild(LocalPlayer p) {
        scaffold.removeIf(b -> Minecraft.getInstance().level == null
                || Minecraft.getInstance().level.getBlockState(b).isAir());
        if (scaffold.isEmpty()) {
            done();
        } else {
            phase = Phase.RETURN;
            msg("Построено. Спускаюсь и убираю леса");
        }
    }

    // ---------------- навигация ----------------

    private static boolean planNear(Minecraft mc, LocalPlayer p, Level lvl, Vec3 hit) {
        if (!p.onGround()) return true; // подождём приземления
        Set<BlockPos> forb = unplaced(lvl);
        Predicate<BlockPos> goal = n -> !forb.contains(n) && !forb.contains(n.above())
                && new Vec3(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5).distanceTo(hit) <= WALK_REACH;
        return startPath(p, lvl, goal, forb, true);
    }

    private static boolean planAway(Minecraft mc, LocalPlayer p, Level lvl) {
        if (!p.onGround()) return true;
        Set<BlockPos> forb = unplaced(lvl);
        BlockPos cur = p.blockPosition();
        Predicate<BlockPos> goal = n -> !forb.contains(n) && !forb.contains(n.above())
                && n.distSqr(cur) >= 4 && n.getY() == cur.getY();
        return startPath(p, lvl, goal, forb, false);
    }

    private static boolean startPath(LocalPlayer p, Level lvl, Predicate<BlockPos> goal, Set<BlockPos> forb,
                                     boolean canPillar) {
        List<Nav.Step> res = Nav.find(lvl, p.blockPosition(), goal, forb,
                canPillar && hasItem(p, scaffoldMatch()), scaffold, 8000, 40);
        if (res == null || res.isEmpty()) return false;
        path = res;
        pathIdx = 0;
        stepTicks = 0;
        stuckTicks = 0;
        lastPos = p.position();
        return true;
    }

    private static void navTick(Minecraft mc, LocalPlayer p, Level lvl) {
        releaseSneak(mc);
        if (path == null || pathIdx >= path.size()) {
            endPath(mc);
            return;
        }
        BlockPos cur = p.blockPosition();
        if (p.onGround()) {
            for (int i = path.size() - 1; i >= pathIdx; i--) {
                if (path.get(i).pos().equals(cur)) {
                    pathIdx = i + 1;
                    stepTicks = 0;
                    break;
                }
            }
        }
        if (pathIdx >= path.size()) {
            endPath(mc);
            return;
        }
        Nav.Step st = path.get(pathIdx);
        BlockPos t = st.pos();
        if (++stepTicks > 140) {
            failNav(mc, "Застрял");
            return;
        }
        switch (st.move()) {
            case WALK, STEP_UP, DROP -> walk(mc, p, st, t);
            case PILLAR -> pillar(mc, p, lvl, t);
            case DIG -> dig(mc, p, lvl, t);
        }
    }

    private static void endPath(Minecraft mc) {
        path = null;
        replans = 0;
        stopActions(mc);
    }

    private static void failNav(Minecraft mc, String why) {
        path = null;
        stopActions(mc);
        replans++;
        if (replans > 3) {
            replans = 0;
            pause("Пауза: " + why + ". Кнопка K или меню - продолжить");
        }
    }

    private static void walk(Minecraft mc, LocalPlayer p, Nav.Step st, BlockPos t) {
        Vec3 c = Vec3.atBottomCenterOf(t);
        double dx = c.x - p.getX(), dz = c.z - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (p.blockPosition().equals(t) && p.onGround() && hd < 0.6) {
            pathIdx++;
            stepTicks = 0;
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        setLook(p, yaw, 10.0F);
        mc.options.keyUp.setDown(true);
        keysHeld = true;
        boolean jump = st.move() == Nav.Move.STEP_UP && p.onGround() && hd < 1.6 && p.getY() < t.getY() - 0.05;
        mc.options.keyJump.setDown(jump);

        if (lastPos != null && p.position().distanceToSqr(lastPos) < 0.0004) stuckTicks++;
        else stuckTicks = 0;
        lastPos = p.position();
        if (stuckTicks > 30) failNav(mc, "Застрял по дороге");
    }

    private static void pillar(Minecraft mc, LocalPlayer p, Level lvl, BlockPos t) {
        BlockPos a = t.below(); // клетка, где мы стоим сейчас
        mc.options.keyUp.setDown(false);
        if (p.blockPosition().equals(t) && p.onGround()) {
            mc.options.keyJump.setDown(false);
            pathIdx++;
            stepTicks = 0;
            return;
        }
        setLook(p, p.getYRot(), 90.0F);
        Predicate<ItemStack> sc = scaffoldMatch();
        if (!sc.test(p.getMainHandItem()) && !selectPred(mc, p, sc)) {
            failNav(mc, "Нет блоков для лесов (положите землю)");
            return;
        }
        keysHeld = true;
        if (lvl.getBlockState(a).canBeReplaced()) {
            mc.options.keyJump.setDown(p.onGround());
            BlockPos floor = a.below();
            if (p.getY() >= a.getY() + 1.06 && sc.test(p.getMainHandItem())) {
                if (lvl.getBlockEntity(floor) != null) {
                    failNav(mc, "Под ногами контейнер");
                    return;
                }
                Vec3 hit = Vec3.atCenterOf(floor).add(0, 0.5, 0);
                InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, Direction.UP, floor, false));
                if (r.consumesAction()) {
                    p.swing(InteractionHand.MAIN_HAND);
                    scaffold.add(a);
                }
            }
        } else {
            mc.options.keyJump.setDown(false);
        }
    }

    private static void dig(Minecraft mc, LocalPlayer p, Level lvl, BlockPos f) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        if (p.blockPosition().equals(f) && p.onGround()) {
            scaffold.remove(f);
            mc.gameMode.stopDestroyBlock();
            pathIdx++;
            stepTicks = 0;
            return;
        }
        setLook(p, p.getYRot(), 90.0F);
        if (lvl.getBlockState(f).isAir()) {
            scaffold.remove(f);
        } else {
            mineBlock(mc, p, lvl, f);
        }
    }

    // ---------------- спуск и уборка лесов ----------------

    private static void returnTick(Minecraft mc, LocalPlayer p, Level lvl) {
        if (!p.onGround()) return;
        BlockPos cur = p.blockPosition();
        if (cur.getY() == startPos.getY() || cur.getY() <= startPos.getY() + 1) {
            phase = Phase.CLEAN;
            return;
        }
        BlockPos s = startPos;
        Predicate<BlockPos> goal = n -> n.getY() == s.getY()
                && Math.abs(n.getX() - s.getX()) <= 6 && Math.abs(n.getZ() - s.getZ()) <= 6;
        if (!startPath(p, lvl, goal, Set.of(), false)) {
            msg("Не могу спуститься сам: сойдите вручную");
            abandoned = scaffold.size();
            done();
        }
    }

    private static void cleanTick(Minecraft mc, LocalPlayer p, Level lvl) {
        scaffold.removeIf(b -> lvl.getBlockState(b).isAir());
        if (scaffold.isEmpty()) {
            done();
            return;
        }
        BlockPos target = null;
        for (BlockPos b : scaffold) if (target == null || b.getY() > target.getY()) target = b;
        final BlockPos tg = target;
        Vec3 c = Vec3.atCenterOf(tg);
        if (p.getEyePosition().distanceTo(c) <= 4.0) {
            Vec3 d = c.subtract(p.getEyePosition());
            double h = Math.sqrt(d.x * d.x + d.z * d.z);
            setLook(p, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, h)));
            mineBlock(mc, p, lvl, tg);
            return;
        }
        if (!p.onGround()) return;
        Predicate<BlockPos> goal = n -> !n.equals(tg) && !n.equals(tg.above())
                && new Vec3(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5).distanceTo(c) <= WALK_REACH;
        if (!startPath(p, lvl, goal, Set.of(), false)) {
            scaffold.remove(tg);
            abandoned++;
        }
    }

    private static void mineBlock(Minecraft mc, LocalPlayer p, Level lvl, BlockPos pos) {
        BlockState s = lvl.getBlockState(pos);
        if (s.isAir()) return;
        selectBestTool(p, s);
        if (!mc.gameMode.isDestroying()) mc.gameMode.startDestroyBlock(pos, Direction.UP);
        else mc.gameMode.continueDestroyBlock(pos, Direction.UP);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static void selectBestTool(LocalPlayer p, BlockState s) {
        Inventory inv = p.getInventory();
        int bestSlot = inv.selected;
        float bestSpeed = inv.items.get(bestSlot).getDestroySpeed(s);
        for (int i = 0; i < 9; i++) {
            float sp = inv.items.get(i).getDestroySpeed(s);
            if (sp > bestSpeed) {
                bestSpeed = sp;
                bestSlot = i;
            }
        }
        inv.selected = bestSlot;
    }

    // ---------------- помощники ----------------

    private static Predicate<ItemStack> scaffoldMatch() {
        final Farms.Blueprint b = bp;
        return s -> !s.isEmpty() && SCAFFOLD_ITEMS.contains(s.getItem())
                && !(b != null && b.palette.values().stream().anyMatch(e -> e.match().test(s)));
    }

    private static void setLook(LocalPlayer p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        expYaw = p.getYRot();
        expPitch = p.getXRot();
        lockValid = true;
    }

    private static Direction yawDirection(char c) {
        return switch (c) {
            case '^' -> facing;
            case 'v' -> facing.getOpposite();
            case '>' -> facing.getClockWise();
            case '<' -> facing.getCounterClockWise();
            default -> null;
        };
    }

    private static void releaseSneak(Minecraft mc) {
        if (sneaking) {
            mc.options.keyShift.setDown(userSneak);
            sneaking = false;
        }
    }

    private static void stopActions(Minecraft mc) {
        releaseSneak(mc);
        if (keysHeld) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            keysHeld = false;
        }
        if (mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
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

    private static boolean hasItem(LocalPlayer p, Predicate<ItemStack> pred) {
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && pred.test(s)) return true;
        return false;
    }

    /** Берёт нужный предмет в руку (из хотбара или меняет местами с основным инвентарём). */
    private static boolean selectPred(Minecraft mc, LocalPlayer p, Predicate<ItemStack> pred) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && pred.test(s)) {
                inv.selected = i;
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && pred.test(s)) {
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
