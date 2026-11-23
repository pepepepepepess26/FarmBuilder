package com.example.farmbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/** Простой поиск пути по блокам: ходьба, подъём на 1, спуск до 3, леса вверх (PILLAR), спуск по лесам (DIG). */
public final class Nav {
    public enum Move { WALK, STEP_UP, DROP, PILLAR, DIG }

    public record Step(BlockPos pos, Move move) {}

    private static final Direction[] H = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private static final class Node {
        final BlockPos pos;
        final double cost;
        final Node parent;
        final Move move;

        Node(BlockPos pos, double cost, Node parent, Move move) {
            this.pos = pos;
            this.cost = cost;
            this.parent = parent;
            this.move = move;
        }
    }

    private Nav() {}

    /** Клетка, через которую можно пройти (нет коллизии, воды и опасных блоков). */
    public static boolean passable(Level lvl, BlockPos pos) {
        BlockState s = lvl.getBlockState(pos);
        if (!s.getFluidState().isEmpty()) return false;
        if (!s.getCollisionShape(lvl, pos).isEmpty()) return false;
        return !(s.is(BlockTags.FIRE) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.COBWEB) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.CAMPFIRE)
                || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL));
    }

    /** Блок под ногами: целый куб, не жидкость, не магма. */
    public static boolean floor(Level lvl, BlockPos below) {
        BlockState s = lvl.getBlockState(below);
        return s.isCollisionShapeFullBlock(lvl, below) && s.getFluidState().isEmpty()
                && !s.is(Blocks.MAGMA_BLOCK);
    }

    public static boolean standable(Level lvl, BlockPos feet) {
        return passable(lvl, feet) && passable(lvl, feet.above()) && floor(lvl, feet.below());
    }

    private static boolean far(BlockPos start, BlockPos b, int radius) {
        return Math.abs(b.getX() - start.getX()) > radius || Math.abs(b.getZ() - start.getZ()) > radius
                || Math.abs(b.getY() - start.getY()) > radius + 12;
    }

    private static void relax(PriorityQueue<Node> pq, Map<BlockPos, Double> best, Node from, BlockPos to,
                              Move move, double add, Set<BlockPos> forb) {
        if (forb.contains(to) || forb.contains(to.above())) return;
        double c = from.cost + add;
        if (c < best.getOrDefault(to, Double.MAX_VALUE)) {
            best.put(to, c);
            pq.add(new Node(to, c, from, move));
        }
    }

    /**
     * Ищет дешёвый путь до клетки, удовлетворяющей goal. null - пути нет, пустой список - уже на месте.
     * forb - клетки, куда нельзя вставать/ставить леса (ещё не построенные блоки чертежа).
     */
    public static List<Step> find(Level lvl, BlockPos start, Predicate<BlockPos> goal, Set<BlockPos> forb,
                                  boolean canPillar, Set<BlockPos> scaffold, int maxNodes, int radius) {
        PriorityQueue<Node> pq = new PriorityQueue<>(Comparator.comparingDouble((Node n) -> n.cost));
        Map<BlockPos, Double> best = new HashMap<>();
        pq.add(new Node(start, 0, null, null));
        best.put(start, 0.0);
        int expanded = 0;
        while (!pq.isEmpty() && expanded < maxNodes) {
            Node n = pq.poll();
            if (n.cost > best.getOrDefault(n.pos, Double.MAX_VALUE)) continue;
            if (goal.test(n.pos)) return build(n);
            expanded++;
            BlockPos p = n.pos;

            for (Direction d : H) {
                BlockPos b = p.relative(d);
                if (far(start, b, radius)) continue;
                if (standable(lvl, b)) {
                    relax(pq, best, n, b, Move.WALK, 1.0, forb);
                    continue;
                }
                BlockPos up = b.above();
                if (passable(lvl, p.above(2)) && standable(lvl, up)) {
                    relax(pq, best, n, up, Move.STEP_UP, 2.0, forb);
                } else if (passable(lvl, b) && passable(lvl, b.above())) {
                    for (int k = 1; k <= 3; k++) {
                        BlockPos c = b.below(k);
                        if (!passable(lvl, c)) break;
                        if (standable(lvl, c)) {
                            relax(pq, best, n, c, Move.DROP, 2.0 + k, forb);
                            break;
                        }
                    }
                }
            }

            // леса вверх: ставим блок в свою клетку на прыжке
            if (canPillar && !forb.contains(p) && (n.move == Move.PILLAR || floor(lvl, p.below()))
                    && passable(lvl, p.above(2)) && passable(lvl, p.above(3))
                    && !far(start, p.above(), radius)) {
                relax(pq, best, n, p.above(), Move.PILLAR, 5.0, forb);
            }

            // вниз по своим лесам: ломаем блок под ногами
            BlockPos f = p.below();
            if (n.move != Move.PILLAR && scaffold.contains(f) && floor(lvl, f.below())) {
                relax(pq, best, n, f, Move.DIG, 3.0, forb);
            }
        }
        return null;
    }

    private static List<Step> build(Node end) {
        List<Step> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) out.add(new Step(n.pos, n.move));
        Collections.reverse(out);
        return out;
    }
}
