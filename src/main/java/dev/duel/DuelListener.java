package dev.duel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;

import java.util.List;

public class DuelListener implements Listener {
    private final DuelPlugin plugin;
    private final DuelManager m;

    public DuelListener(DuelPlugin plugin, DuelManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    /** Обход привата: только если включено в конфиге и есть право duel.admin. */
    private boolean bypass(Player p) {
        return plugin.getConfig().getBoolean("protection.admin-bypass", false) && p.hasPermission("duel.admin");
    }

    // ---------------------------------------------------------------- бой

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        DuelManager.Duel d = m.getDuel((Player) e.getEntity());
        if (d == null) return;
        if (d.state != DuelManager.State.FIGHT) { e.setCancelled(true); return; }
        if (e instanceof EntityDamageByEntityEvent) {
            Entity dmg = ((EntityDamageByEntityEvent) e).getDamager();
            if (dmg instanceof Projectile && ((Projectile) dmg).getShooter() instanceof Entity) {
                dmg = (Entity) ((Projectile) dmg).getShooter();
            }
            if (dmg instanceof Player && !d.has(dmg.getUniqueId())) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        DuelManager.Duel d = m.getDuel(p);
        if (d == null || (d.state != DuelManager.State.FIGHT && d.state != DuelManager.State.FROZEN)) return;
        e.setKeepInventory(false);   // вещи проигравшего выпадают
        e.setKeepLevel(false);
        e.setDeathMessage(null);
        m.onDeath(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (m.consumeRespawn(e.getPlayer().getUniqueId())) e.setRespawnLocation(m.lobby());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { m.onQuit(e.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.isDead() && !m.isInDuel(p) && m.inProtected(p.getLocation()) && !bypass(p)) {
                m.tp(p, m.lobby());
            }
        }, 2L);
    }

    // ---------------------------------------------------------------- границы / телепорты

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;
        Player p = e.getPlayer();
        DuelManager.Duel d = m.getDuel(p);
        if (d == null) {
            // посторонний: в арену нельзя ни пешком, ни в полёте, ни в спектаторе
            if (!bypass(p) && m.inProtected(to)) {
                if (m.inProtected(from)) m.tp(p, m.lobby()); else e.setTo(from);
            }
            return;
        }
        if (d.state == DuelManager.State.FROZEN) {
            Location f = from.clone();
            f.setYaw(to.getYaw());
            f.setPitch(to.getPitch());
            e.setTo(f);
        } else if ((d.state == DuelManager.State.FIGHT || d.state == DuelManager.State.ENDING)
                && d.mode == DuelManager.Mode.CAGE) {
            if (!m.inArena(to, 0.5)) e.setTo(from);
        }
    }

    // HIGHEST: перебивает отмену от AntiRelog (disable-teleports-in-pvp) для наших телепортов
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (m.isAllowedTp(p.getUniqueId())) { e.setCancelled(false); return; }
        Location to = e.getTo();
        DuelManager.Duel d = m.getDuel(p);
        if (d != null && d.state != DuelManager.State.PREPARING) {
            if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                    && (d.mode == DuelManager.Mode.RTP || m.inArena(to, 0.5))) return;
            e.setCancelled(true);
            p.sendMessage(plugin.msg("tp-denied"));
            return;
        }
        if (to != null && m.inProtected(to) && !bypass(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (m.isInDuel(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        DuelManager.Duel d = m.getDuel(p);
        if (d == null || d.state == DuelManager.State.PREPARING || p.hasPermission("duel.admin")) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        List<String> wl = plugin.getConfig().getStringList("settings.command-whitelist");
        for (String s : wl) if (s.equalsIgnoreCase(cmd)) return;
        e.setCancelled(true);
        p.sendMessage(plugin.msg("cmd-denied"));
    }

    // ---------------------------------------------------------------- приват арены

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (m.inProtected(e.getBlock().getLocation()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (m.inProtected(e.getBlock().getLocation()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (m.inProtected(e.getBlockClicked().getLocation()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (m.inProtected(e.getBlockClicked().getLocation()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (m.inProtected(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (m.inProtected(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (m.inProtected(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFluid(BlockFromToEvent e) {
        if (m.inProtected(e.getToBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (m.inProtected(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonTouches(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonTouches(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    private boolean pistonTouches(Block piston, List<Block> blocks, BlockFace dir) {
        if (m.inProtected(piston.getLocation()) || m.inProtected(piston.getRelative(dir).getLocation())) return true;
        for (Block b : blocks) {
            if (m.inProtected(b.getLocation()) || m.inProtected(b.getRelative(dir).getLocation())) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> m.inProtected(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> m.inProtected(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM && m.inProtected(e.getLocation())) {
            e.setCancelled(true);
        }
    }
}
