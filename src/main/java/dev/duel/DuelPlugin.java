package dev.duel;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class DuelPlugin extends JavaPlugin {
    private DuelManager manager;
    private DuelMenu menu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new DuelManager(this);
        menu = new DuelMenu(this, manager);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(new DuelListener(this, manager), this);
        DuelCommand cmd = new DuelCommand(this, manager, menu);
        PluginCommand pc = getCommand("duel");
        pc.setExecutor(cmd);
        pc.setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }

    /** Сообщение без префикса, с цветами и заменой плейсхолдеров (пары ключ, значение). */
    public String raw(String key, String... kv) {
        String s = getConfig().getString("messages." + key, "&cНет сообщения: " + key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace(kv[i], kv[i + 1]);
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String msg(String key, String... kv) {
        return raw("prefix") + raw(key, kv);
    }
}
