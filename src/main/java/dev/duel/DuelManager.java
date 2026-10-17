package dev.duel;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class DuelManager {

    public enum State { PREPARING, FROZEN, FIGHT, ENDING }
    public enum Mode { CAGE, RTP }

    public static class Duel {
        public final UUID a, b;
        public final Mode mode;
        public State state = State.PREPARING;
        public UUID winner;
        public BukkitTask task;
        public Location fightLoc;

        Duel(UUID a, UUID b, Mode mode) { this.a = a; this.b = b; this.mode = mode; }
        public boolean has(UUID u) { return a.equals(u) || b.equals(u); }
        public UUID other(UUID u) { return a.equals(u) ? b : a; }
        void stop() { if (task != null) { task.cancel(); task = null; } }
    }

    private final DuelPlugin plugin;
    private final Map<UUID, Long> open = new LinkedHashMap<>();
    private final Map<UUID, Mode> openMode = new HashMap<>();
    private final Set<UUID> allowedTp = new HashSet<>();
    private final Set<UUID> pendingRespawn = new HashSet<>();
    private final Set<Duel> duels = new HashSet<>();
    private final Map<UUID, Duel> byPlayer = new HashMap<>();
    private boolean building;

    private static class Pair {
        final UUID a, b;
        Pair(UUID a, UUID b) { this.a = a; this.b = b; }
        boolean has(UUID u) { return a.equals(u) || b.equals(u); }
    }
    private final LinkedList<Pair> queue = new LinkedList<>();

    public DuelManager(DuelPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------ config helpers

    private int cfg(String path, int def) { return plugin.getConfig().getInt(path, def); }
    public int radius() { return Math.max(20, cfg("arena.radius", 24)); }
    public int height() { return Math.max(10, cfg("arena.height", 14)); }

    public World world() { return Bukkit.getWorld(plugin.getConfig().getString("world", "grief-1")); }

    public Location center() {
        World w = world();
        if (w == null) return null;
        return new Location(w, cfg("arena.center.x", 0), cfg("arena.center.y", 100), cfg("arena.center.z", 0));
    }

    public Location spawnPoint(boolean first) {
        Location c = center();
        if (c == null) return null;
        int off = radius() - 6;
        return new Location(c.getWorld(), c.getX() + (first ? -off : off) + 0.5, c.getY(), c.getZ() + 0.5,
                first ? -90f : 90f, 0f);
    }

    public Location lobby() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("spawn");
        World w = s != null ? Bukkit.getWorld(s.getString("world", "world")) : null;
        if (w == null) w = Bukkit.getWorlds().get(0);
        if (s == null || !s.contains("x")) return w.getSpawnLocation();
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    /** Внутри арены (margin > 0 — сужает, margin < 0 — расширяет область). */
    public boolean inArena(Location l, double margin) {
        Location c = center();
        if (c == null || l == null || l.getWorld() == null || !l.getWorld().equals(c.getWorld())) return false;
        double dx = l.getX() - (c.getX() + 0.5), dz = l.getZ() - (c.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz) <= radius() + 0.5 - margin
                && l.getY() >= c.getY() - 1 - (margin < 0 ? 4 : 0)
                && l.getY() < c.getY() + height() + (margin < 0 ? 3 : 0);
    }

    /** Вся постройка арены, включая стены, пол и крышу. */
    public boolean inProtected(Location l) { return inArena(l, -3.5); }

    // ------------------------------------------------------------ state access

    public boolean isInDuel(Player p) { return byPlayer.containsKey(p.getUniqueId()); }
    public Duel getDuel(Player p) { return byPlayer.get(p.getUniqueId()); }

    /** Клетка занята (идёт дуэль в клетке или арена строится). RTP-дуэли клетку не занимают. */
    public boolean isArenaBusy() {
        if (building) return true;
        for (Duel d : duels) if (d.mode == Mode.CAGE) return true;
        return false;
    }

    public boolean isOpen(UUID id) { return open.containsKey(id); }

    // ------------------------------------------------------------ очередь на клетку

    public int queuePosition(UUID id) {
        int i = 1;
        for (Pair p : queue) { if (p.has(id)) return i; i++; }
        return 0;
    }
    public boolean isQueued(UUID id) { return queuePosition(id) > 0; }
    public int queueSize() { return queue.size(); }

    private void enqueue(UUID a, UUID b) {
        queue.add(new Pair(a, b));
        String pos = String.valueOf(queue.size());
        for (UUID id : new UUID[]{a, b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(plugin.msg("queued", "%pos%", pos));
        }
    }

    /** Убирает пару с этим игроком из очереди и уведомляет напарника. */
    private boolean dropFromQueue(UUID id) {
        Iterator<Pair> it = queue.iterator();
        while (it.hasNext()) {
            Pair p = it.next();
            if (p.has(id)) {
                it.remove();
                Player o = Bukkit.getPlayer(p.a.equals(id) ? p.b : p.a);
                if (o != null) o.sendMessage(plugin.msg("queue-partner-left"));
                return true;
            }
        }
        return false;
    }

    /** Если клетка свободна — запускает следующую пару из очереди. */
    private void pollQueue() {
        while (!queue.isEmpty() && !isArenaBusy()) {
            Pair p = queue.poll();
            Player pa = Bukkit.getPlayer(p.a), pb = Bukkit.getPlayer(p.b);
            if (pa == null || pb == null || isInDuel(pa) || isInDuel(pb)) {
                if (pa != null) pa.sendMessage(plugin.msg("queue-partner-left"));
                if (pb != null) pb.sendMessage(plugin.msg("queue-partner-left"));
                continue;
            }
            pa.sendMessage(plugin.msg("queue-start"));
            pb.sendMessage(plugin.msg("queue-start"));
            Duel d = new Duel(p.a, p.b, Mode.CAGE);
            register(d);
            startPreparing(d);
        }
    }
    public Mode getOpenMode(UUID id) { Mode m = openMode.get(id); return m == null ? Mode.CAGE : m; }
    public long openSince(UUID id) { Long l = open.get(id); return l == null ? 0 : l; }
    public boolean isAllowedTp(UUID id) { return allowedTp.contains(id); }
    public boolean consumeRespawn(UUID id) { return pendingRespawn.remove(id); }

    public String modeName(Mode m) { return plugin.raw(m == Mode.CAGE ? "mode-cage" : "mode-rtp"); }

    public List<UUID> getOpen() {
        List<UUID> list = new ArrayList<>();
        Iterator<UUID> it = open.keySet().iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) { it.remove(); openMode.remove(id); } else list.add(id);
        }
        return list;
    }

    public void tp(Player p, Location l) {
        allowedTp.add(p.getUniqueId());
        try { p.teleport(l); } finally { allowedTp.remove(p.getUniqueId()); }
    }

    private void register(Duel d) { duels.add(d); byPlayer.put(d.a, d); byPlayer.put(d.b, d); }

    private void unregister(Duel d) {
        duels.remove(d);
        byPlayer.remove(d.a, d);
        byPlayer.remove(d.b, d);
    }

    // ------------------------------------------------------------ open duels

    public void createOpen(Player p, Mode mode) {
        if (isInDuel(p)) { p.sendMessage(plugin.msg("already-in-duel")); return; }
        if (open.containsKey(p.getUniqueId())) { p.sendMessage(plugin.msg("already-open")); return; }
        if (isQueued(p.getUniqueId())) { p.sendMessage(plugin.msg("already-queued")); return; }
        if (mode == Mode.CAGE && world() == null) { p.sendMessage(plugin.msg("no-world")); return; }
        if (mode == Mode.RTP && rtpWorld() == null) { p.sendMessage(plugin.msg("no-world")); return; }
        final UUID id = p.getUniqueId();
        final Long stamp = System.currentTimeMillis();
        final int expire = cfg("settings.open-duel-expire", 120);
        open.put(id, stamp);
        openMode.put(id, mode);
        p.sendMessage(plugin.msg("created", "%time%", String.valueOf(expire), "%mode%", modeName(mode)));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.6f, 1f);

        TextComponent tc = new TextComponent(TextComponent.fromLegacyText(
                plugin.raw("prefix") + plugin.raw("broadcast", "%player%", p.getName(), "%mode%", modeName(mode))));
        tc.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/duel"));
        tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("Нажмите, чтобы открыть меню дуэлей").create()));
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.spigot().sendMessage(tc);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (open.remove(id, stamp)) {
                openMode.remove(id);
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) pl.sendMessage(plugin.msg("expired"));
            }
        }, expire * 20L);
    }

    public void cancelOpen(Player p) {
        if (open.remove(p.getUniqueId()) != null) {
            openMode.remove(p.getUniqueId());
            p.sendMessage(plugin.msg("cancelled"));
        } else if (dropFromQueue(p.getUniqueId())) {
            p.sendMessage(plugin.msg("queue-left"));
        } else p.sendMessage(plugin.msg("no-open"));
    }

    public void accept(Player acc, UUID creatorId) {
        if (isInDuel(acc)) { acc.sendMessage(plugin.msg("already-in-duel")); return; }
        if (creatorId.equals(acc.getUniqueId())) { acc.sendMessage(plugin.msg("self")); return; }
        Player creator = Bukkit.getPlayer(creatorId);
        if (!open.containsKey(creatorId) || creator == null || !creator.isOnline()) {
            open.remove(creatorId);
            openMode.remove(creatorId);
            acc.sendMessage(plugin.msg("gone"));
            return;
        }
        if (isQueued(acc.getUniqueId())) { acc.sendMessage(plugin.msg("already-queued")); return; }
        Mode mode = getOpenMode(creatorId);
        boolean queueIt = false;
        if (mode == Mode.CAGE) {
            if (world() == null) { acc.sendMessage(plugin.msg("no-world")); return; }
            queueIt = isArenaBusy() || !queue.isEmpty();
        } else if (rtpWorld() == null) {
            acc.sendMessage(plugin.msg("no-world"));
            return;
        }
        open.remove(creatorId);
        open.remove(acc.getUniqueId());
        openMode.remove(creatorId);
        openMode.remove(acc.getUniqueId());
        if (queueIt) { enqueue(creatorId, acc.getUniqueId()); return; }
        Duel d = new Duel(creatorId, acc.getUniqueId(), mode);
        register(d);
        startPreparing(d);
    }

    // ------------------------------------------------------------ flow

    private void startPreparing(final Duel d) {
        final int total = cfg("settings.teleport-countdown", 5);
        final boolean cancelMove = plugin.getConfig().getBoolean("settings.cancel-on-move", false);
        final boolean cancelDamage = plugin.getConfig().getBoolean("settings.cancel-on-damage", false);
        d.task = new BukkitRunnable() {
            int left = total;
            Location la, lb;
            double ha, hb;
            boolean init;

            @Override
            public void run() {
                Player pa = Bukkit.getPlayer(d.a), pb = Bukkit.getPlayer(d.b);
                if (pa == null || pb == null || pa.isDead() || pb.isDead()) {
                    abortPreparing(d, "cancel-offline");
                    return;
                }
                if (!init) {
                    la = pa.getLocation().clone(); lb = pb.getLocation().clone();
                    ha = pa.getHealth(); hb = pb.getHealth();
                    init = true;
                } else if ((cancelMove && (moved(pa, la) || moved(pb, lb)))
                        || (cancelDamage && (pa.getHealth() < ha - 0.01 || pb.getHealth() < hb - 0.01))) {
                    abortPreparing(d, "cancel-move");
                    return;
                }
                if (left <= 0) {
                    cancel();
                    beginFreeze(d, pa, pb);
                    return;
                }
                for (Player p : new Player[]{pa, pb}) {
                    p.sendTitle(plugin.raw("countdown-title", "%time%", String.valueOf(left)),
                            plugin.raw("countdown-subtitle"), 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private boolean moved(Player p, Location start) {
        Location l = p.getLocation();
        return !l.getWorld().equals(start.getWorld()) || l.getBlockX() != start.getBlockX()
                || l.getBlockY() != start.getBlockY() || l.getBlockZ() != start.getBlockZ();
    }

    private void abortPreparing(Duel d, String msgKey) {
        d.stop();
        unregister(d);
        for (UUID id : new UUID[]{d.a, d.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) { p.sendMessage(plugin.msg(msgKey)); p.resetTitle(); }
        }
        pollQueue();
    }

    @SuppressWarnings("deprecation")
    private void prepare(Player p, Location l) {
        if (p.getGameMode() != GameMode.SURVIVAL) p.setGameMode(GameMode.SURVIVAL);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.closeInventory();
        tp(p, l);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    private void beginFreeze(final Duel d, Player pa, Player pb) {
        Location sa, sb;
        if (d.mode == Mode.CAGE) {
            sa = spawnPoint(true);
            sb = spawnPoint(false);
        } else {
            Location[] pair = findRtpPair();
            if (pair == null) { abortPreparing(d, "rtp-fail"); return; }
            sa = pair[0];
            sb = pair[1];
        }
        d.fightLoc = sa.clone();
        d.state = State.FROZEN;
        prepare(pa, sa);
        prepare(pb, sb);
        final int total = cfg("settings.freeze-seconds", 0);
        if (total <= 0) { startFight(d); return; }   // без заморозки: бить можно сразу после телепорта
        final String na = pa.getName(), nb = pb.getName();
        for (Player p : new Player[]{pa, pb}) {
            p.sendTitle(plugin.raw("duel-title"), plugin.raw("duel-subtitle", "%a%", na, "%b%", nb), 5, 40, 5);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 0.8f);
        }
        d.task = new BukkitRunnable() {
            int left = total;
            @Override
            public void run() {
                if (left <= 0) { cancel(); startFight(d); return; }
                for (UUID id : new UUID[]{d.a, d.b}) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) continue;
                    p.sendTitle(plugin.raw("freeze-title", "%time%", String.valueOf(left)),
                            plugin.raw("freeze-subtitle"), 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.7f);
                }
                left--;
            }
        }.runTaskTimer(plugin, 40L, 20L);
    }

    private void startFight(final Duel d) {
        d.state = State.FIGHT;
        for (UUID id : new UUID[]{d.a, d.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.sendTitle(plugin.raw("fight-title"), plugin.raw("fight-subtitle"), 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1f);
        }
        d.task = new BukkitRunnable() {
            @Override public void run() { draw(d); }
        }.runTaskLater(plugin, cfg("settings.max-duel-seconds", 600) * 20L);
    }

    /** Вызывается из PlayerDeathEvent. */
    public void onDeath(Player victim) {
        Duel d = byPlayer.get(victim.getUniqueId());
        if (d == null) return;
        if (d.state != State.FROZEN && d.state != State.FIGHT) return;
        d.fightLoc = victim.getLocation().clone();
        pendingRespawn.add(victim.getUniqueId());
        end(d, d.other(victim.getUniqueId()), victim.getUniqueId());
    }

    public void onQuit(Player p) {
        UUID id = p.getUniqueId();
        open.remove(id);
        openMode.remove(id);
        dropFromQueue(id);
        Duel d = byPlayer.get(id);
        if (d == null) return;
        switch (d.state) {
            case PREPARING:
                abortPreparing(d, "cancel-offline");
                break;
            case FROZEN:
            case FIGHT:
                pendingRespawn.add(id);
                d.fightLoc = p.getLocation().clone();
                p.setHealth(0.0); // вещи выпадают, как при обычной смерти
                if (byPlayer.get(id) == d && d.state != State.ENDING) end(d, d.other(id), id);
                break;
            case ENDING:
                if (id.equals(d.winner)) finish(d);
                break;
        }
    }

    private void end(final Duel d, final UUID winnerId, UUID loserId) {
        if (d.state == State.ENDING) return;
        d.stop();
        d.state = State.ENDING;
        d.winner = winnerId;
        Bukkit.broadcastMessage(plugin.raw("prefix") + plugin.raw("result",
                "%winner%", nameOf(winnerId), "%loser%", nameOf(loserId)));
        Player w = Bukkit.getPlayer(winnerId);
        if (w != null) {
            w.sendTitle(plugin.raw("title-win"), plugin.raw("subtitle-win"), 5, 50, 10);
            w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        final int loot = cfg("settings.loot-seconds", 20);
        d.task = new BukkitRunnable() {
            int left = loot;
            @Override
            public void run() {
                Player ww = Bukkit.getPlayer(winnerId);
                if (ww == null || left <= 0) { finish(d); return; }
                actionBar(ww, plugin.raw("loot-actionbar", "%time%", String.valueOf(left)));
                left--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void draw(Duel d) {
        if (d.state == State.ENDING) return;
        d.stop();
        d.state = State.ENDING;
        Bukkit.broadcastMessage(plugin.raw("prefix") + plugin.raw("draw"));
        finish(d);
    }

    private void finish(Duel d) {
        d.stop();
        for (UUID id : new UUID[]{d.a, d.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !p.isDead()) {
                p.resetTitle();
                tp(p, lobby());
            }
        }
        clearDrops(d);
        unregister(d);
        pollQueue();
    }

    private void clearDrops(Duel d) {
        if (d.mode == Mode.CAGE) {
            World w = world();
            if (w == null) return;
            for (Entity e : w.getEntities()) {
                if (isTrash(e) && inArena(e.getLocation(), -2)) e.remove();
            }
        } else if (d.fightLoc != null && d.fightLoc.getWorld() != null) {
            for (Entity e : d.fightLoc.getWorld().getNearbyEntities(d.fightLoc, 45, 45, 45)) {
                if (isTrash(e)) e.remove();
            }
        }
    }

    private boolean isTrash(Entity e) {
        return e instanceof Item || e instanceof Projectile || e instanceof ExperienceOrb;
    }

    public void shutdown() {
        for (Duel d : new ArrayList<>(duels)) {
            d.stop();
            for (UUID id : new UUID[]{d.a, d.b}) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !p.isDead() && d.state != State.PREPARING) tp(p, lobby());
            }
            clearDrops(d);
        }
        duels.clear();
        byPlayer.clear();
        queue.clear();
    }

    // ------------------------------------------------------------ RTP

    private World rtpWorld() { return Bukkit.getWorld(plugin.getConfig().getString("rtp.world", "grief-1")); }

    private Location[] findRtpPair() {
        World w = rtpWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return null;
        Random rnd = new Random();
        int min = cfg("rtp.min-distance", 300), max = cfg("rtp.max-distance", 3000);
        double minD = Math.min(min, max), maxD = Math.max(min, max);
        int cx = cfg("rtp.center-x", 0), cz = cfg("rtp.center-z", 0);
        int sep = Math.max(4, cfg("rtp.separation", 10));
        WorldBorder wb = w.getWorldBorder();
        double half = wb.getSize() / 2 - 25;
        Location bc = wb.getCenter();
        Location c = center();
        for (int i = 0; i < 40; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = minD + rnd.nextDouble() * (maxD - minD);
            int x = (int) Math.round(cx + Math.cos(ang) * dist);
            int z = (int) Math.round(cz + Math.sin(ang) * dist);
            if (Math.abs(x - bc.getX()) > half || Math.abs(z - bc.getZ()) > half) continue;
            if (c != null && c.getWorld().equals(w) && Math.hypot(x - c.getX(), z - c.getZ()) < radius() + 60) continue;
            Location a = safe(w, x, z);
            if (a == null) continue;
            for (int j = 0; j < 12; j++) {
                double a2 = rnd.nextDouble() * Math.PI * 2;
                Location b = safe(w, (int) Math.round(x + Math.cos(a2) * sep), (int) Math.round(z + Math.sin(a2) * sep));
                if (b == null || Math.abs(b.getY() - a.getY()) > 6) continue;
                face(a, b);
                face(b, a);
                return new Location[]{a, b};
            }
        }
        return null;
    }

    private void face(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector());
        v.setY(0);
        if (v.lengthSquared() > 0) from.setDirection(v);
    }

    private Location safe(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        if (y < 1) return null;
        Block g = w.getBlockAt(x, y, z);
        Material t = g.getType();
        String n = t.name();
        if (g.isLiquid() || !t.isSolid() || n.contains("LEAVES") || n.contains("CAMPFIRE")
                || t == Material.MAGMA_BLOCK || t == Material.CACTUS) return null;
        Block b1 = w.getBlockAt(x, y + 1, z), b2 = w.getBlockAt(x, y + 2, z);
        if (!b1.isPassable() || !b2.isPassable() || b1.isLiquid() || b2.isLiquid()) return null;
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    // ------------------------------------------------------------ arena build

    public void build(final CommandSender s) {
        if (building) { s.sendMessage(plugin.msg("build-running")); return; }
        for (Duel d : duels) if (d.mode == Mode.CAGE) { s.sendMessage(plugin.msg("build-busy")); return; }
        Location c = center();
        if (c == null) { s.sendMessage(plugin.msg("no-world")); return; }
        building = true;
        s.sendMessage(plugin.msg("build-start"));
        ArenaBuilder.build(plugin, c, radius(), height(), () -> {
            building = false;
            s.sendMessage(plugin.msg("build-done"));
            int W = radius() + 2;
            s.sendMessage(plugin.raw("prefix") + "§7Границы арены: §f" + (c.getBlockX() - W) + " "
                    + (c.getBlockY() - 4) + " " + (c.getBlockZ() - W) + " §7→ §f" + (c.getBlockX() + W) + " "
                    + (c.getBlockY() + height()) + " " + (c.getBlockZ() + W));
            pollQueue();
        });
    }

    // ------------------------------------------------------------ utils

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    private void actionBar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }
}
