package dev.duel;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Мрачная круглая арена-клетка (строится по одному слою за тик).
 *
 *  - пол: шахматка чёрного камня, душевая земля, трещины, центральная печать из плачущего обсидиана,
 *    красные кольца, лучи из базальта, площадки для старта
 *  - стены: чёрный камень с колоннами-контрфорсами, окна с решётками (за ними светится плачущий обсидиан)
 *  - потолок: рёбра свода, сталактиты, паутина, цепи с души-фонарями, «око» в центре
 *  - снаружи, снизу и сверху — бедрок: выйти и войти нельзя
 */
public class ArenaBuilder {

    public static void build(DuelPlugin plugin, Location c, final int R, final int H, final Runnable done) {
        final World w = c.getWorld();
        final int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        final int W = R + 2;
        new BukkitRunnable() {
            int dy = -4;

            @Override
            public void run() {
                if (dy > H) { cancel(); done.run(); return; }
                for (int dx = -W; dx <= W; dx++) {
                    for (int dz = -W; dz <= W; dz++) {
                        double d = Math.sqrt(dx * dx + dz * dz);
                        if (d > W + 0.5) continue;
                        Material m = pick(dx, dy, dz, d, R, H);
                        Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                        b.setType(m, false);
                        if (m == Material.SOUL_LANTERN && dy == H - 6) {
                            Lantern l = (Lantern) b.getBlockData();
                            l.setHanging(true);
                            b.setBlockData(l, false);
                        }
                    }
                }
                dy++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---------------------------------------------------------------- helpers

    private static int hash(int x, int y, int z) {
        return ((x * 73856093) ^ (y * 83492791) ^ (z * 19349663)) & 0x7fffffff;
    }

    private static double angle(int dx, int dz) {
        double a = Math.toDegrees(Math.atan2(dz, dx));
        return a < 0 ? a + 360 : a;
    }

    /** Расстояние (в блоках по дуге) до ближайшей линии с шагом step градусов, сдвинутой на offset. */
    private static double arcFrom(int dx, int dz, double d, double step, double offset) {
        double a = (angle(dx, dz) + offset) % step;
        double diff = Math.min(a, step - a);
        return Math.toRadians(diff) * d;
    }

    private static boolean nearSpawn(int dx, int dz, int R) {
        return Math.hypot(Math.abs(dx) - (R - 6), dz) < 5;
    }

    private static boolean rib(int dx, int dz, double d, int R) {
        return arcFrom(dx, dz, d, 30, 0) <= 0.6
                || Math.abs(d - R * 0.5) <= 0.6
                || Math.abs(d - R * 0.8) <= 0.6;
    }

    // ---------------------------------------------------------------- слои

    private static Material pick(int dx, int dy, int dz, double d, int R, int H) {
        if (dy == -4 || dy == H) return Material.BEDROCK;       // дно и крыша
        if (d > R + 1.5) {                                      // внешняя оболочка
            if (dy >= 3 && dy <= H - 5 && arcFrom(dx, dz, d, 30, 15) <= 1.0) return Material.CRYING_OBSIDIAN;
            return Material.BEDROCK;
        }
        if (dy <= -2) return Material.BLACKSTONE;
        if (d > R + 0.5) return wall(dx, dy, dz, d, H);         // внутренний слой стены
        if (dy == -1) return floor(dx, dz, d, R);
        return interior(dx, dy, dz, d, R, H);
    }

    private static Material floor(int dx, int dz, double d, int R) {
        // стартовые площадки
        int off = R - 6;
        for (int s = -1; s <= 1; s += 2) {
            double pd = Math.hypot(dx - s * off, dz);
            if (pd <= 1.6) return Material.CRYING_OBSIDIAN;
            if (pd <= 2.6) return Material.OBSIDIAN;
        }
        // центральная печать
        if (d <= 2.5) return Material.CRYING_OBSIDIAN;
        if (d <= 3.5) return Material.GILDED_BLACKSTONE;
        if (d <= 5.5) return Material.POLISHED_BLACKSTONE_BRICKS;
        // края и кольца
        if (d >= R - 1.5) return Material.OBSIDIAN;
        if (d >= R - 3.5 && d <= R - 2.5) return Material.CRYING_OBSIDIAN;
        if (Math.abs(d - R * 0.5) <= 0.6) return Material.RED_NETHER_BRICKS;
        // 8 лучей из базальта
        if (d > 5.5 && d < R - 3.5 && arcFrom(dx, dz, d, 45, 0) <= 0.7) {
            return (((int) d) % 2 == 0) ? Material.POLISHED_BASALT : Material.BASALT;
        }
        int h = hash(dx, 0, dz) % 100;
        if (h < 8) return Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
        if (h < 18) return Material.SOUL_SOIL;
        if (h < 26) return Material.BLACKSTONE;
        return (((dx + dz) & 1) == 0) ? Material.POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
    }

    private static Material wall(int dx, int dy, int dz, double d, int H) {
        if (dy <= 1) return Material.OBSIDIAN;
        if (dy == 2) return Material.RED_NETHER_BRICKS;   // «кровавая» полоса у основания
        if (dy >= 3 && dy <= H - 5 && arcFrom(dx, dz, d, 30, 15) <= 1.0) return Material.IRON_BARS;
        if (dy == H - 2) return Material.CRYING_OBSIDIAN;
        if (arcFrom(dx, dz, d, 30, 0) <= 1.6) {
            return dy == H - 3 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
        }
        int h = hash(dx, dy, dz) % 100;
        if (h < 2) return Material.GILDED_BLACKSTONE;
        if (h < 28) return Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
        return Material.BLACKSTONE;
    }

    private static Material interior(int dx, int dy, int dz, double d, int R, int H) {
        // 1) контрфорсы у стены (12 штук) + души-фонари на их вершинах
        if (d > R - 1.5 && dy <= H - 2) {
            double arc = arcFrom(dx, dz, d, 30, 0);
            if (arc <= 1.5 && dy <= H - 3) {
                return dy == H - 3 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
            }
            if (arc <= 0.6 && dy == H - 2 && d <= R - 0.5) return Material.SOUL_CAMPFIRE;   // чаши с синим огнём на колоннах
        }

        // 2) 8 колонн-укрытий (2x2, высота 5) на середине радиуса
        int pr = R / 2;
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians(22.5 + 45 * k);
            int px = (int) Math.round(pr * Math.cos(a)), pz = (int) Math.round(pr * Math.sin(a));
            if ((dx == px || dx == px + 1) && (dz == pz || dz == pz + 1)) {
                if (dy <= 3) return Material.POLISHED_BLACKSTONE_BRICKS;
                if (dy == 4) return Material.CHISELED_POLISHED_BLACKSTONE;
                if (dy == 5 && dx == px && dz == pz) return Material.SOUL_LANTERN;
                return Material.AIR;
            }
        }

        // 3) 12 цепей с души-фонарями под сводом
        for (int k = 0; k < 12; k++) {
            double a = Math.toRadians(15 + 30 * k);
            int hx = (int) Math.round((R - 4) * Math.cos(a)), hz = (int) Math.round((R - 4) * Math.sin(a));
            if (dx == hx && dz == hz) {
                if (dy >= H - 5) return Material.CHAIN;
                if (dy == H - 6) return Material.SOUL_LANTERN;
            }
        }

        // 3b) подвешенные клетки-«виселицы» с души-фонарём внутри
        if (d > 8 && d < R - 4 && hash(dx, 9, dz) % 130 == 0 && !rib(dx, dz, d, R)) {
            if (dy >= H - 3) return Material.CHAIN;
            if (dy == H - 4 || dy == H - 5) return Material.IRON_BARS;
            if (dy == H - 6) return Material.SOUL_LANTERN;
        }
        // 3c) случайные обрывки цепей под потолком
        if (d > 3 && !rib(dx, dz, d, R) && hash(dx, 5, dz) % 41 == 0) {
            int clen = 2 + hash(dx, 6, dz) % 4;
            if (dy >= H - 1 - clen) return Material.CHAIN;
        }

        // 4) свод: рёбра, «око», паутина по углам, сталактиты
        if (dy == H - 1) {
            if (d <= 2.5) return Material.CRYING_OBSIDIAN;
            if (rib(dx, dz, d, R)) return Material.POLISHED_BLACKSTONE_BRICKS;
            if (d > R - 2 && hash(dx, 3, dz) % 3 == 0) return Material.COBWEB;
        } else if (dy >= H - 4 && dy < H - 1 && d > 3 && rib(dx, dz, d, R) && hash(dx, 0, dz) % 5 == 0) {
            int len = 1 + hash(dx, 1, dz) % 3;
            if (dy >= H - 1 - len) return Material.POLISHED_BLACKSTONE_BRICK_WALL;
        }

        // 5) черепа на полу
        if (dy == 0 && d > 7 && d < R - 4 && !nearSpawn(dx, dz, R)) {
            int hs = hash(dx, 7, dz) % 90;
            if (hs == 0) return Material.SKELETON_SKULL;
            if (hs == 1) return Material.WITHER_SKELETON_SKULL;
            if (hs == 2) return Material.POLISHED_BLACKSTONE_BRICK_SLAB;
            if (hs == 3) return Material.BLACKSTONE_SLAB;
        }
        return Material.AIR;
    }
}
