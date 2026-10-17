package dev.duel;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class DuelMenu implements Listener {

    public static class Holder implements InventoryHolder {
        final Map<Integer, UUID> slots = new HashMap<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};
    private static final int CREATE_CAGE = 46, CREATE_RTP = 48, REFRESH = 49, CANCEL = 51;

    private final DuelPlugin plugin;
    private final DuelManager manager;

    public DuelMenu(DuelPlugin plugin, DuelManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        // живое обновление списка раз в 2 секунды
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                InventoryHolder ih = p.getOpenInventory().getTopInventory().getHolder();
                if (ih instanceof Holder) render((Holder) ih, p);
            }
        }, 40L, 40L);
    }

    public void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 54, plugin.raw("menu-title"));
        render(h, p);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.5f, 0.8f);
    }

    private void render(Holder h, Player p) {
        Inventory inv = h.inv;
        inv.clear();
        h.slots.clear();

        ItemStack black = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack gray = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack red = item(Material.RED_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            boolean border = i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8;
            inv.setItem(i, border ? (i % 2 == 0 ? black : gray) : black);
        }
        inv.setItem(3, red);
        inv.setItem(5, red);
        inv.setItem(47, red);
        inv.setItem(50, red);

        List<UUID> list = manager.getOpen();
        boolean busy = manager.isArenaBusy();
        inv.setItem(4, item(Material.WITHER_SKELETON_SKULL, "&4&lАрена дуэлей",
                "&8▪ &7Клетка: " + (busy ? "&cзанята" : "&aсвободна"),
                "&8▪ &7Вызовов: &f" + list.size(),
                "&8▪ &7В очереди: &f" + manager.queueSize(),
                "",
                "&7Выберите противника или",
                "&7создайте свой вызов."));

        int idx = 0;
        for (UUID id : list) {
            if (idx >= SLOTS.length) break;
            Player owner = Bukkit.getPlayer(id);
            if (owner == null) continue;
            boolean mine = id.equals(p.getUniqueId());
            long sec = (System.currentTimeMillis() - manager.openSince(id)) / 1000;
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) skull.getItemMeta();
            sm.setOwningPlayer(owner);
            sm.setDisplayName(c("&c&l⚔ &f" + owner.getName()));
            List<String> lore = new ArrayList<>();
            DuelManager.Mode md = manager.getOpenMode(id);
            lore.add(c("&8▪ &7Режим: &f" + manager.modeName(md)));
            lore.add(c("&8▪ &7Ждёт уже: &f" + sec + " сек."));
            lore.add("");
            boolean wait = md == DuelManager.Mode.CAGE && (busy || manager.queueSize() > 0);
            lore.add(c(mine ? "&7Это ваш вызов." : (wait ? "&eКлетка занята — нажмите, чтобы встать в очередь" : "&eНажмите, чтобы принять вызов")));
            sm.setLore(lore);
            skull.setItemMeta(sm);
            inv.setItem(SLOTS[idx], skull);
            h.slots.put(SLOTS[idx], id);
            idx++;
        }
        if (idx == 0) {
            inv.setItem(22, item(Material.COBWEB, "&8Здесь пусто",
                    "&7Активных вызовов нет.", "&7Создайте первый!"));
        }

        boolean mine = manager.isOpen(p.getUniqueId());
        boolean queued = manager.isQueued(p.getUniqueId());
        if (queued) {
            inv.setItem(CREATE_CAGE, item(Material.CLOCK, "&e&lВы в очереди",
                    "&7Позиция: &f" + manager.queuePosition(p.getUniqueId()),
                    "&7Дуэль начнётся автоматически."));
            inv.setItem(CANCEL, item(Material.BARRIER, "&c&lПокинуть очередь", "&7Нажмите, чтобы выйти из очереди."));
        } else if (mine) {
            inv.setItem(CREATE_CAGE, item(Material.REDSTONE_TORCH, "&c&lВызов активен",
                    "&7Режим: &f" + manager.modeName(manager.getOpenMode(p.getUniqueId())),
                    "&7Ожидаем соперника..."));
            inv.setItem(CANCEL, item(Material.BARRIER, "&c&lОтменить вызов", "&7Нажмите, чтобы убрать свой вызов."));
        } else {
            inv.setItem(CREATE_CAGE, item(Material.IRON_BARS, "&4&lДуэль в клетке",
                    "&7Мрачная арена, из которой",
                    "&7нельзя выйти.", "", "&eНажмите, чтобы создать"));
            inv.setItem(CREATE_RTP, item(Material.ENDER_PEARL, "&4&lДуэль на RTP",
                    "&7Вас обоих телепортирует в",
                    "&7случайное место в мире и",
                    "&7вы дерётесь там.", "", "&eНажмите, чтобы создать"));
        }
        inv.setItem(REFRESH, item(Material.CLOCK, "&8Обновить", "&7Список обновляется сам."));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        Player p = (Player) e.getWhoClicked();
        Holder h = (Holder) e.getInventory().getHolder();
        int slot = e.getRawSlot();

        if ((slot == CREATE_CAGE || slot == CREATE_RTP) && !manager.isOpen(p.getUniqueId())
                && !manager.isQueued(p.getUniqueId())) {
            manager.createOpen(p, slot == CREATE_CAGE ? DuelManager.Mode.CAGE : DuelManager.Mode.RTP);
            render(h, p);
        } else if (slot == CANCEL && (manager.isOpen(p.getUniqueId()) || manager.isQueued(p.getUniqueId()))) {
            manager.cancelOpen(p);
            render(h, p);
        } else if (slot == REFRESH) {
            render(h, p);
        } else {
            UUID id = h.slots.get(slot);
            if (id != null) {
                p.closeInventory();
                manager.accept(p, id);
            }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta im = i.getItemMeta();
        im.setDisplayName(c(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(c(s));
        im.setLore(l);
        im.addItemFlags(ItemFlag.values());
        i.setItemMeta(im);
        return i;
    }
}
