package com.example.farmbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public record Entry(String label, Predicate<ItemStack> match, Item icon, char dir, char yawDir) {}
    public record Cell(BlockPos pos, Entry entry, Direction hopperDir) {}
    public record Req(Entry entry, int count) {}

    private static Entry item(String label, Item it) {
        return new Entry(label, s -> s.is(it), it, '\0', '\0');
    }

    private static Entry tagged(String label, String tagId, Item icon, char yawDir) {
        TagKey<Item> t = TagKey.create(Registries.ITEM, ResourceLocation.parse(tagId));
        return new Entry(label, s -> s.is(t), icon, '\0', yawDir);
    }

    private static Entry hopper(char dir) {
        return new Entry("Воронка", s -> s.is(Items.HOPPER), Items.HOPPER, dir, '\0');
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
        p.put('F', tagged("Забор (деревянный)", "minecraft:wooden_fences", Items.OAK_FENCE, '\0'));
        p.put('G', tagged("Калитка", "minecraft:fence_gates", Items.OAK_FENCE_GATE, '^'));
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

    // ---------- 4. Зимний дом 11x9 (спруса + каменные кирпичи), открытый потолок, крыльцо, фонари ----------
    // Ступени крыши ставятся с поворотом камеры (yawDir): B - скат вперёд (^), b - скат назад (v).
    private static Blueprint house() {
        Map<Character, Entry> p = new HashMap<>();
        p.put('S', item("Каменные кирпичи", Items.STONE_BRICKS));
        p.put('P', tagged("Доски (любые)", "minecraft:planks", Items.SPRUCE_PLANKS, '\0'));
        p.put('L', tagged("Брёвна (любые)", "minecraft:logs", Items.SPRUCE_LOG, '\0'));
        p.put('G', item("Стеклянная панель", Items.GLASS_PANE));
        p.put('B', tagged("Ступеньки (дерево)", "minecraft:wooden_stairs", Items.SPRUCE_STAIRS, '^'));
        p.put('b', tagged("Ступеньки (дерево)", "minecraft:wooden_stairs", Items.SPRUCE_STAIRS, 'v'));
        p.put('R', tagged("Плиты (дерево)", "minecraft:wooden_slabs", Items.SPRUCE_SLAB, '\0'));
        p.put('T', item("Фонарь", Items.LANTERN));
        String e = "...........";
        return new Blueprint("house", "Дом", "Зимний дом",
                "Дом 11x9 из спруса и каменных кирпичей: высокая крыша с открытым потолком, окна, крыльцо, "
                        + "фонари внутри. Дверь поставьте сами. Для высоты бот строит леса из земли "
                        + "(положите 20+ блоков земли), потом сам их убирает. Ровная площадка, снег лучше расчистить.",
                Items.SPRUCE_STAIRS, 11, 9, p, new String[][]{
                // y0: фундамент, пол, крыльцо
                {"....SSS....",
                        ".SSSSSSSSS.",
                        ".SPPPPPPPS.",
                        ".SPPPPPPPS.",
                        ".SPPPPPPPS.",
                        ".SPPPPPPPS.",
                        ".SPPPPPPPS.",
                        ".SSSSSSSSS.",
                        e},
                // y1: стены, проём двери, фонари
                {e,
                        ".LPPP.PPPL.",
                        ".PT.....TP.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".PT.....TP.",
                        ".LPPPPPPPL.",
                        e},
                // y2: окна
                {e,
                        ".LPGP.PGPL.",
                        ".P.......P.",
                        ".P.......P.",
                        ".G.......G.",
                        ".P.......P.",
                        ".P.......P.",
                        ".LPPPGPPPL.",
                        e},
                // y3: окна, перемычка над проёмом
                {e,
                        ".LPGPPPGPL.",
                        ".P.......P.",
                        ".P.......P.",
                        ".G.......G.",
                        ".P.......P.",
                        ".P.......P.",
                        ".LPPPGPPPL.",
                        e},
                // y4: верх стен
                {e,
                        ".LPPPPPPPL.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".LPPPPPPPL.",
                        e},
                // y5: обвязка, свесы крыши
                {"BBBBBBBBBBB",
                        ".PPPPPPPPP.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".PPPPPPPPP.",
                        "bbbbbbbbbbb"},
                // y6
                {e,
                        "BBBBBBBBBBB",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        "bbbbbbbbbbb",
                        e},
                // y7
                {e,
                        e,
                        "BBBBBBBBBBB",
                        ".P.......P.",
                        ".P.......P.",
                        ".P.......P.",
                        "bbbbbbbbbbb",
                        e,
                        e},
                // y8
                {e,
                        e,
                        e,
                        "BBBBBBBBBBB",
                        ".P.......P.",
                        "bbbbbbbbbbb",
                        e,
                        e,
                        e},
                // y9: конёк
                {e,
                        e,
                        e,
                        e,
                        "RRRRRRRRRRR",
                        e,
                        e,
                        e,
                        e}});
    }

    // ---------- 5. Баня 9x9 (только ванильные блоки): предбанник + парилка с каменкой, дымоход через крышу ----------
    private static Blueprint bathhouse() {
        Map<Character, Entry> p = new HashMap<>();
        p.put('S', item("Каменные кирпичи", Items.STONE_BRICKS));
        p.put('P', tagged("Доски (любые)", "minecraft:planks", Items.SPRUCE_PLANKS, '\0'));
        p.put('L', tagged("Брёвна (любые)", "minecraft:logs", Items.SPRUCE_LOG, '\0'));
        p.put('G', item("Стеклянная панель", Items.GLASS_PANE));
        p.put('B', tagged("Ступеньки (дерево)", "minecraft:wooden_stairs", Items.SPRUCE_STAIRS, '^'));
        p.put('b', tagged("Ступеньки (дерево)", "minecraft:wooden_stairs", Items.SPRUCE_STAIRS, 'v'));
        p.put('R', tagged("Плиты (дерево)", "minecraft:wooden_slabs", Items.SPRUCE_SLAB, '\0'));
        p.put('W', tagged("Плиты (дерево)", "minecraft:wooden_slabs", Items.SPRUCE_SLAB, '\0'));
        p.put('T', item("Фонарь", Items.LANTERN));
        p.put('N', item("Бочка", Items.BARREL));
        p.put('K', item("Котёл", Items.CAULDRON));
        p.put('F', new Entry("Печь", s -> s.is(Items.FURNACE), Items.FURNACE, '\0', '>'));
        p.put('Y', item("Костёр", Items.CAMPFIRE));
        return new Blueprint("bathhouse", "Баня", "Баня",
                "Небольшая баня 9x9, только ванильные блоки: предбанник с лавкой и бочкой, парилка с каменкой "
                        + "(печь с горящим костром сверху - от него идёт дым, рядом котёл с водой), полками и фонарём, дымоход через крышу, окна. Дверь поставьте сами. "
                        + "Для высоты бот строит леса из земли (положите 20+ блоков земли) и потом убирает их.",
                Items.CAULDRON, 9, 9, p, new String[][]{
                {"...SSS...",
                        ".SSSSSSS.",
                        ".SPPPPPS.",
                        ".SPPPPPS.",
                        ".SPPPPPS.",
                        ".SPPPPPS.",
                        ".SPPPPPS.",
                        ".SSSSSSS.",
                        "........."},
                {".........",
                        ".LPP.PPL.",
                        ".PW...NP.",
                        ".PW...KP.",
                        ".PPP.PPP.",
                        ".PWWWKFP.",
                        ".PPPPPSP.",
                        ".LPPPPPL.",
                        "........."},
                {".........",
                        ".LGP.PGL.",
                        ".P....TP.",
                        ".G.....P.",
                        ".PPP.PPP.",
                        ".P....YG.",
                        ".P.T..SP.",
                        ".LPPPPPL.",
                        "........."},
                {".........",
                        ".LPPPPPL.",
                        ".P.....P.",
                        ".P.....P.",
                        ".PPPPPPP.",
                        ".P.....P.",
                        ".P....SP.",
                        ".LPPPPPL.",
                        "........."},
                {"BBBBBBBBB",
                        ".PPPPPPP.",
                        ".P.....P.",
                        ".P.....P.",
                        ".P.....P.",
                        ".P.....P.",
                        ".P....SP.",
                        ".PPPPPPP.",
                        "bbbbbbbbb"},
                {".........",
                        "BBBBBBBBB",
                        ".P.....P.",
                        ".P.....P.",
                        ".P.....P.",
                        ".P.....P.",
                        ".P....SP.",
                        "bbbbbbbbb",
                        "........."},
                {".........",
                        ".........",
                        "BBBBBBBBB",
                        ".P.....P.",
                        ".P.....P.",
                        ".P.....P.",
                        "bbbbbbSbb",
                        ".........",
                        "........."},
                {".........",
                        ".........",
                        ".........",
                        "BBBBBBBBB",
                        ".P.....P.",
                        "bbbbbbbbb",
                        "......S..",
                        ".........",
                        "........."},
                {".........",
                        ".........",
                        ".........",
                        ".........",
                        "RRRRRRRRR",
                        ".........",
                        "......S..",
                        ".........",
                        "........."}});
    }

    private static final List<Blueprint> BUILTIN = List.of(meat(), potions(), smelter(), house(), bathhouse());
    private static List<Blueprint> loaded = List.of();

    /** Встроенные фермы + чертежи из config/farmbuilder/*.json (статуи и т.п.). */
    public static List<Blueprint> all() {
        List<Blueprint> l = new ArrayList<>(BUILTIN);
        l.addAll(loaded);
        return l;
    }

    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve("farmbuilder");
    }

    /** Перечитывает папку с чертежами. Битые файлы пропускаются. */
    public static void reload() {
        List<Blueprint> out = new ArrayList<>();
        try {
            Path dir = folder();
            Files.createDirectories(dir);
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream.filter(x -> x.toString().endsWith(".json")).sorted().toList();
            }
            for (Path p : files) {
                try (Reader r = Files.newBufferedReader(p)) {
                    out.add(fromJson(JsonParser.parseReader(r).getAsJsonObject()));
                } catch (Exception e) {
                    System.err.println("[farmbuilder] bad blueprint " + p + ": " + e);
                }
            }
        } catch (Exception e) {
            System.err.println("[farmbuilder] cannot read blueprints folder: " + e);
        }
        loaded = out;
    }

    /**
     * Формат файла:
     * { "id": "...", "name": "Короткое", "title": "Длинное", "desc": "...", "icon": "minecraft:stone",
     *   "palette": { "a": "minecraft:white_concrete", ... },
     *   "layers": [ ["ряд z=0", "ряд z=1", ...], ... ] }   // layers: снизу вверх
     */
    private static Blueprint fromJson(JsonObject o) {
        String id = o.get("id").getAsString();
        String name = o.has("name") ? o.get("name").getAsString() : id;
        String title = o.has("title") ? o.get("title").getAsString() : name;
        String desc = o.has("desc") ? o.get("desc").getAsString() : "";
        Item icon = Items.STONE;
        if (o.has("icon")) {
            Item i = BuiltInRegistries.ITEM.get(ResourceLocation.parse(o.get("icon").getAsString()));
            if (i != Items.AIR) icon = i;
        }
        Map<Character, Entry> pal = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("palette").entrySet()) {
            if (e.getKey().isEmpty()) continue;
            final Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.getValue().getAsString()));
            if (it == Items.AIR) throw new IllegalArgumentException("unknown item " + e.getValue());
            pal.put(e.getKey().charAt(0), new Entry(it.getDescription().getString(), s -> s.is(it), it, '\0', '\0'));
        }
        JsonArray ly = o.getAsJsonArray("layers");
        String[][] layers = new String[ly.size()][];
        int w = 0, d = 0;
        for (int y = 0; y < ly.size(); y++) {
            JsonArray rows = ly.get(y).getAsJsonArray();
            layers[y] = new String[rows.size()];
            d = Math.max(d, rows.size());
            for (int z = 0; z < rows.size(); z++) {
                String r = rows.get(z).getAsString();
                layers[y][z] = r;
                w = Math.max(w, r.length());
            }
        }
        return new Blueprint(id, name, title, desc, icon, w, d, pal, layers);
    }
}
