package dev.duel;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class DuelCommand implements CommandExecutor, TabCompleter {
    private final DuelPlugin plugin;
    private final DuelManager manager;
    private final DuelMenu menu;

    public DuelCommand(DuelPlugin plugin, DuelManager manager, DuelMenu menu) {
        this.plugin = plugin;
        this.manager = manager;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (!(s instanceof Player)) { s.sendMessage("Только для игроков."); return true; }
            Player p = (Player) s;
            if (!p.hasPermission("duel.use")) { p.sendMessage(plugin.msg("no-perm")); return true; }
            if (manager.isInDuel(p)) { p.sendMessage(plugin.msg("already-in-duel")); return true; }
            menu.open(p);
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("cancel") && s instanceof Player) {
            manager.cancelOpen((Player) s);
            return true;
        }
        if (!s.hasPermission("duel.admin")) { s.sendMessage(plugin.msg("no-perm")); return true; }
        switch (sub) {
            case "build":
                manager.build(s);
                break;
            case "reload":
                plugin.reloadConfig();
                s.sendMessage(plugin.msg("reloaded"));
                break;
            case "setcenter":
            case "sethome":
            case "setarena":
                if (!(s instanceof Player)) return true;
                Location l = ((Player) s).getLocation();
                plugin.getConfig().set("world", l.getWorld().getName());
                plugin.getConfig().set("arena.center.x", l.getBlockX());
                plugin.getConfig().set("arena.center.y", l.getBlockY());
                plugin.getConfig().set("arena.center.z", l.getBlockZ());
                plugin.saveConfig();
                s.sendMessage(plugin.msg("setcenter"));
                break;
            case "setspawn":
                if (!(s instanceof Player)) return true;
                Location sp = ((Player) s).getLocation();
                plugin.getConfig().set("spawn.world", sp.getWorld().getName());
                plugin.getConfig().set("spawn.x", sp.getX());
                plugin.getConfig().set("spawn.y", sp.getY());
                plugin.getConfig().set("spawn.z", sp.getZ());
                plugin.getConfig().set("spawn.yaw", (double) sp.getYaw());
                plugin.getConfig().set("spawn.pitch", (double) sp.getPitch());
                plugin.saveConfig();
                s.sendMessage(plugin.msg("setspawn"));
                break;
            default:
                s.sendMessage("§c/duel [build|setcenter|sethome|setspawn|reload|cancel]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("cancel");
            if (s.hasPermission("duel.admin")) {
                out.add("build"); out.add("setcenter"); out.add("setspawn"); out.add("reload");
            }
        }
        return out;
    }
}
