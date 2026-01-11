package com.hjh_database.skill.medical.gui;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.medical.MedicalManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.CompletableFuture; // 引入异步

public class MedicalEtchGui implements Listener {

    private final Hjh_database plugin;
    private final MedicalManager manager;

    private final Map<UUID, Long> deleteConfirm = new HashMap<>();

    private static final String TITLE_MAIN = "§0医术台 - 主菜单";
    private static final String TITLE_ETCH = "§0医术绘制";
    private static final String TITLE_SEPARATE = "§0医术分离";

    private static final int SLOT_ETCH_BANNER = 10;
    private static final int SLOT_ETCH_BOOK = 12;
    private static final int SLOT_ETCH_BUTTON = 14;
    private static final int SLOT_ETCH_RESULT = 16;

    private static final int SLOT_SEP_INPUT = 13;
    private static final int SLOT_SEP_BUTTON = 22;
    private static final int SLOT_SEP_OUT_BANNER = 30;
    private static final int SLOT_SEP_OUT_BOOK = 32;

    private final Set<Integer> etchInteractiveSlots = new HashSet<>(Arrays.asList(SLOT_ETCH_BANNER, SLOT_ETCH_BOOK, SLOT_ETCH_RESULT));
    private final Set<Integer> separateInteractiveSlots = new HashSet<>(Arrays.asList(SLOT_SEP_INPUT, SLOT_SEP_OUT_BANNER, SLOT_SEP_OUT_BOOK));

    public MedicalEtchGui(Hjh_database plugin) {
        this.plugin = plugin;
        this.manager = plugin.getMedicalManager();
    }

    public static class MainMenuHolder implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
    public static class EtchHolder implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
    public static class SeparateHolder implements InventoryHolder { @Override public Inventory getInventory() { return null; } }

    public void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new MainMenuHolder(), 27, TITLE_MAIN);
        inv.setItem(11, createItem(Material.LOOM, "§a§l绘制医术", "§7将医术绘制到旗帜上", "§e点击进入"));
        inv.setItem(13, createItem(Material.GRINDSTONE, "§b§l医术分离", "§7将已绘制的旗帜还原", "§7分为: 空白旗 + 秘籍", "§c需要消耗记忆！", "§e点击进入"));
        inv.setItem(15, createItem(Material.BARRIER, "§c§l遗忘所有医术", "§c慎用！", "§7清空所有已学会的医术记录", "§e双击确认"));
        fillGlass(inv, 27);
        p.openInventory(inv);
    }

    public void openEtchGui(Player p) {
        Inventory inv = Bukkit.createInventory(new EtchHolder(), 27, TITLE_ETCH);
        fillGlass(inv, 27);
        inv.setItem(SLOT_ETCH_BANNER, null);
        inv.setItem(SLOT_ETCH_BOOK, null);
        inv.setItem(SLOT_ETCH_RESULT, null);
        ItemStack[] session = manager.getLoomSession(p);
        if (session != null) {
            if (session[0] != null) inv.setItem(SLOT_ETCH_BANNER, session[0]);
            if (session[1] != null) inv.setItem(SLOT_ETCH_BOOK, session[1]);
        }
        inv.setItem(SLOT_ETCH_BUTTON, createItem(Material.LIME_DYE, "§a§l点击绘制", "§7放入 旗帜 + 秘籍"));
        inv.setItem(26, createItem(Material.OAK_DOOR, "§7返回主菜单"));
        p.openInventory(inv);
    }

    public void openSeparateGui(Player p) {
        Inventory inv = Bukkit.createInventory(new SeparateHolder(), 45, TITLE_SEPARATE);
        fillGlass(inv, 45);
        inv.setItem(SLOT_SEP_INPUT, null);
        inv.setItem(SLOT_SEP_OUT_BANNER, null);
        inv.setItem(SLOT_SEP_OUT_BOOK, null);
        inv.setItem(SLOT_SEP_BUTTON, createItem(Material.ANVIL, "§e§l点击分离", "§7放入已绘制的旗帜", "§7点击后判断是否拥有此医术"));
        inv.setItem(44, createItem(Material.OAK_DOOR, "§7返回主菜单"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() == Material.END_PORTAL_FRAME) {
            e.setCancelled(true);
            openMainMenu(e.getPlayer());
            e.getPlayer().playSound(e.getPlayer().getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        Player p = (Player) e.getPlayer();
        if (inv.getHolder() instanceof EtchHolder) {
            ItemStack[] content = new ItemStack[3];
            content[0] = inv.getItem(SLOT_ETCH_BANNER);
            content[1] = inv.getItem(SLOT_ETCH_BOOK);
            manager.saveLoomSession(p, content);
            returnItem(p, inv.getItem(SLOT_ETCH_RESULT));
        } else if (inv.getHolder() instanceof SeparateHolder) {
            returnItem(p, inv.getItem(SLOT_SEP_INPUT));
            returnItem(p, inv.getItem(SLOT_SEP_OUT_BANNER));
            returnItem(p, inv.getItem(SLOT_SEP_OUT_BOOK));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory inv = e.getInventory();
        InventoryHolder holder = inv.getHolder();
        if (holder == null) return;
        if (!(holder instanceof MainMenuHolder) && !(holder instanceof EtchHolder) && !(holder instanceof SeparateHolder)) return;

        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        boolean isTopInv = (e.getClickedInventory() == inv);

        if (isTopInv) {
            e.setCancelled(true);
        } else {
            if (e.isShiftClick()) {
                e.setCancelled(true);
                if (holder instanceof EtchHolder) {
                    ItemStack curr = e.getCurrentItem();
                    if (curr != null) {
                        if (curr.getType().name().endsWith("_BANNER")) tryPut(inv, SLOT_ETCH_BANNER, curr);
                        else if (curr.getType() == Material.PAPER || curr.getType() == Material.BOOK) tryPut(inv, SLOT_ETCH_BOOK, curr);
                    }
                } else if (holder instanceof SeparateHolder) {
                    if (e.getCurrentItem() != null && e.getCurrentItem().getType().name().endsWith("_BANNER")) {
                        tryPut(inv, SLOT_SEP_INPUT, e.getCurrentItem());
                    }
                }
            }
            return;
        }

        if (isTopInv) {
            if (holder instanceof EtchHolder && etchInteractiveSlots.contains(slot)) {
                e.setCancelled(false);
            }
            if (holder instanceof SeparateHolder && separateInteractiveSlots.contains(slot)) {
                e.setCancelled(false);
            }
        }

        if (isTopInv) {
            if (holder instanceof MainMenuHolder) {
                if (slot == 11) openEtchGui(p);
                else if (slot == 13) openSeparateGui(p);
                else if (slot == 15) handleForgetButton(p);
            }
            else if (holder instanceof EtchHolder) {
                if (slot == SLOT_ETCH_BUTTON) {
                    ItemStack res = manager.etchSkill(p, inv.getItem(SLOT_ETCH_BANNER), inv.getItem(SLOT_ETCH_BOOK));
                    if (res != null) {
                        consumeItem(inv, SLOT_ETCH_BANNER);
                        consumeItem(inv, SLOT_ETCH_BOOK);
                        inv.setItem(SLOT_ETCH_RESULT, res);
                    }
                }
                else if (slot == 26) openMainMenu(p);
            }
            else if (holder instanceof SeparateHolder) {
                if (slot == SLOT_SEP_BUTTON) {
                    if (!isEmpty(inv.getItem(SLOT_SEP_OUT_BANNER)) || !isEmpty(inv.getItem(SLOT_SEP_OUT_BOOK))) {
                        p.sendMessage("§c请先清空输出槽位！");
                        return;
                    }
                    ItemStack[] res = manager.separateSkill(p, inv.getItem(SLOT_SEP_INPUT));
                    if (res != null) {
                        consumeItem(inv, SLOT_SEP_INPUT);
                        inv.setItem(SLOT_SEP_OUT_BANNER, res[0]);
                        inv.setItem(SLOT_SEP_OUT_BOOK, res[1]);
                    }
                }
                else if (slot == 44) openMainMenu(p);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory inv = e.getInventory();
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof EtchHolder) && !(holder instanceof SeparateHolder)) return;

        Set<Integer> slots = e.getRawSlots();
        for (int slot : slots) {
            if (slot < inv.getSize()) {
                boolean isAllowed = false;
                if (holder instanceof EtchHolder && etchInteractiveSlots.contains(slot)) isAllowed = true;
                if (holder instanceof SeparateHolder && separateInteractiveSlots.contains(slot)) isAllowed = true;

                if (!isAllowed) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    private void handleForgetButton(Player p) {
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (deleteConfirm.containsKey(uuid) && (now - deleteConfirm.get(uuid) < 3000)) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(p);
            data.clearMedicalSkills();

            // 【核心修复】即时保存到数据库
            CompletableFuture.runAsync(() -> plugin.getDatabaseManager().saveMedicalData(data));

            p.sendMessage("§c§l[警告] §7你已遗忘所有医术！");
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
            deleteConfirm.remove(uuid);
        } else {
            deleteConfirm.put(uuid, now);
            p.sendMessage("§c§l[警告] §7这将清空你所有的医术记忆！");
            p.sendMessage("§c§l[警告] §7请在3秒内再次点击以确认！");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f);
        }
    }

    private void tryPut(Inventory inv, int slot, ItemStack item) {
        if (isEmpty(inv.getItem(slot))) {
            ItemStack toPut = item.clone();
            toPut.setAmount(1);
            inv.setItem(slot, toPut);
            item.setAmount(item.getAmount() - 1);
        }
    }

    private void consumeItem(Inventory inv, int slot) {
        ItemStack item = inv.getItem(slot);
        if (item != null) {
            item.setAmount(item.getAmount() - 1);
            inv.setItem(slot, item);
        }
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    private void fillGlass(Inventory inv, int size) {
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, "§7");
        for (int i = 0; i < size; i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, glass);
            }
        }
    }

    private void returnItem(Player p, ItemStack item) {
        if (!isEmpty(item)) {
            p.getInventory().addItem(item).values().forEach(i -> p.getWorld().dropItem(p.getLocation(), i));
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }
}