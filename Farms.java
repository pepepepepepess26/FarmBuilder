package com.example.farmbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Чертежи ферм. Слои: layers[y][z] = строка по x.
 * z=0 - ближний к игроку ряд, x растёт вправо. '.' = пусто.
 * Воронки: > вправо, < влево, ^ вперёд, v назад, D вниз (направление, куда они "смотрят").
 * Без воды, лавы и копания: бот только ставит блоки.
 */
public final class Farms {
    private Farms() {}

    public record Entry(String label, Predicate<ItemStack> match, Item icon, char dir, boolean lockYaw) {}
    public record Cell(BlockPos pos, Entry entry, Direction hopperDir) {}
    public record Req(Entry entry, int count) {}

    private static Entry item(String label, Item it) {
        return new Entry(label, s -> s.is(it), it, '\0', false);
    }

    private static Entry tagged(String label, String tagId, Item icon, boolean lockYaw) {
        TagKey<Item> t = TagKey.create(Registries.ITEM, ResourceLocation.parse(tagId));
        return new Entry(label, s -> s.is(t), icon, '\0', lockYaw);
    }

    private static Entry hopper(char dir) {
        return new Entry("Воронка", s -> s.is(Items.HOPPER), Items.HOPPER, dir, false);
    }

    public static final class Blueprint {
        public final String id, name, title, desc;
        public final Item icon;
        public final int w, d, h;
        public final String[][] layers;
        public final Map<Character, Entry> palette;

        Blueprint(String id, String name, String title, String desc, Item icon, int w, int d,
                  Map<Character, Entry> palette, String[][] layers) {
            this.id = id;
            this.name = name;
            this.title = title;
            this.desc = desc;
            this.icon = icon;
            this.w = w;
            this.d = d;
            this.h = layers.length;
            this.palette = palette;
            this.layers = layers;
        }

        public List<Cell> cells(BlockPos origin, Direction facing) {
            Direction right = facing.getClockWise();
            List<Cell> out = new ArrayList<>();
            for (int y = 0; y < layers.length; y++) {
                for (int z = 0; z < layers[y].length; z++) {
                    String row = layers[y][z];
                    for (int x = 0; x < row.length(); x++) {
                        Entry e = palette.get(row.charAt(x));
                        if (e == null) continue;
                        BlockPos pos = origin.relative(right, x).relative(facing, z).above(y);
                        Direction hd = null;
                        switch (e.dir()) {
                            case '>' -> hd = right;
                            case '<' -> hd = right.getOpposite();
                            case '^' -> hd = facing;
                            case 'v' -> hd = facing.getOpposite();
                            case 'D' -> hd = Direction.DOWN;
                            default -> { }
                        }
                        out.add(new Cell(pos, e, hd));
                    }
                }
            }
            return out;
        }

        public List<Req> requirements() {
            Map<String, int[]> counts = new LinkedHashMap<>();
            Map<String, Entry> ents = new LinkedHashMap<>();
            for (String[] layer : layers) {
                for (String row : layer) {
                    for (char ch : row.toCharArray()) {
                        Entry e = palette.get(ch);
                        if (e == null) continue;
                        counts.computeIfAbsent(e.label(), k -> new int[1])[0]++;
                        ents.putIfAbsent(e.label(), e);
                    }
                }
            }
            List<Req> out = new ArrayList<>();
            for (String k : counts.keySet()) out.add(new Req(ents.get(k), counts.get(k)[0]));
            return out;
        }
    }

    // ---------- 1. Мясо: загон 7x7, воронки у задней стенки собирают дроп в сундук ----------
    private static Blueprint meat() {
        Map<Character, Entry> p = new HashMap<>();
        p.put('F', tagged("Забор (деревянный)", "minecraft:wooden_fences", Items.OAK_FENCE, false));
        p.put('G', tagged("Калитка", "minecraft:fence_gates", Items.OAK_FENCE_GATE, true));
        p.put('C', item("Сундук", Items.CHEST));
        p.put('>', hopper('>'));
        return new Blueprint("meat", "Мясо", "Мясная ферма (загон)",
                "Загон 7x7 с калиткой, задняя стенка в 2 забора (иначе скот прыгает с воронок). У стенки ряд воронок собирает дроп в сундук. "
                        + "Сгоните животных к задней стенке и убивайте их там.",
                Items.COOKED_BEEF, 7, 7, p, new String[][]{{
                "FFFGFFF",
                "F.....F",
                "F.....F",
                "F.....F",
                "F.....F",
                "F>>>>>C",
                "FFFFFFF"},
                {".......",
                ".......",
                ".......",
                ".......",
                ".......",
                "F.....F",
                "FFFFFFF"}});
    }

    // ---------- 2. Зелья: грядка нароста + варочный уголок ----------
    private static Blueprint potions() {
        Map<Character, Entry> p = new HashMap<>();
        p.put('S', item("Песок душ", Items.SOUL_SAND));
        p.put('N', item("Адский нарост", Items.NETHER_WART));
        p.put('B', item("Варочная стойка", Items.BREWING_STAND));
        p.put('K', item("Котёл", Items.CAULDRON));
        p.put('C', item("Сундук", Items.CHEST));
        return new Blueprint("potions", "Зелья", "Зельеварня + грядка нароста",
                "Грядка адского нароста на песке душ (растёт сама), 3 варочные стойки, котёл и сундук. "
                        + "Зелья варите руками.",
                Items.BREWING_STAND, 7, 4, p, new String[][]{
                {"SSSSS..",
                        ".......",
                        "B.B.B.K",
                        ".C....."},
                {"NNNNN..",
                        ".......",
                        ".......",
                        "......."}});
    }

    // ---------- 3. Автоплавильня: 3 печи, 1 сундук на вход, 1 на топливо, 1 на выход ----------
    // y0: выходные воронки -> сундук справа; y1: печи + воронки топлива (с тыла);
    // y2: воронки вниз в печи + линия топлива; y3: линия подачи + сундук топлива; y4: сундук входа.
    private static Blueprint smelter() {
        Map<Character, Entry> p = new HashMap<>();
        p.put('C', item("Сундук", Items.CHEST));
        p.put('U', item("Печь", Items.FURNACE));
        p.put('>', hopper('>'));
        p.put('v', hopper('v'));
        p.put('D', hopper('D'));
        return new Blueprint("smelter", "Плавка", "Автоплавильня (3 печи)",
                "Верхний передний сундук - на плавку (руда, сырое мясо, еда; ТОЛЬКО то, что плавится). "
                        + "Верхний задний сундук - топливо (уголь и т.п., ТОЛЬКО топливо). "
                        + "Готовое придёт в нижний правый сундук. Опыт копится в печах: сломайте печь - выпадет.",
                Items.FURNACE, 4, 2, p, new String[][]{
                {">>>C",
                        "...."},
                {"UUU.",
                        "vvv."},
                {"DDD.",
                        ">>D."},
                {">>D.",
                        "C..."},
                {"C...",
                        "...."}});
    }

    public static final List<Blueprint> ALL = List.of(meat(), potions(), smelter());
}
