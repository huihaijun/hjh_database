package com.hjh_database.kaiwu;

import com.hjh_database.Hjh_database;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class KaiWuListener implements Listener {
    private final Hjh_database plugin;
    private final KaiWuEditor editor;

    public KaiWuListener(Hjh_database plugin) {
        this.plugin = plugin;
        this.editor = new KaiWuEditor(plugin, plugin.getKaiWuManager());
    }

    // 1. 聊天确认监听 (删除资源点)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        KaiWuManager manager = plugin.getKaiWuManager();
        Player p = event.getPlayer();

        if (manager.deleteConfirmations.containsKey(p.getUniqueId())) {
            event.setCancelled(true); // 拦截聊天
            String msg = event.getMessage().trim();

            if (msg.equals("1")) {
                // 必须同步执行删除操作
                plugin.getServer().getScheduler().runTask(plugin, () -> manager.confirmDeleteNode(p));
            } else {
                manager.deleteConfirmations.remove(p.getUniqueId());
                p.sendMessage("§7操作已取消。");
            }
        }
    }

    // 2. 受伤打断开采
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (plugin.getKaiWuManager().isMining(p)) {
                plugin.getKaiWuManager().cancelMining(p, true);
            }
        }
    }

    // 3. 破坏方块 (触发删除确认)
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        KaiWuManager manager = plugin.getKaiWuManager();
        if (manager.isNode(event.getBlock().getLocation())) {
            event.setCancelled(true); // 无论如何先取消

            Player p = event.getPlayer();
            if (p.hasPermission("hjh.kaiwu.op")) {
                // 触发确认流程
                manager.requestDeleteNode(p, manager.serializeLoc(event.getBlock().getLocation()));
            } else {
                p.sendMessage("§c这是资源点，请右键开采！");
            }
        }
    }

    // 4. 交互 (开采 & 编辑)
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null) return;

        // 必须是右键
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        KaiWuManager manager = plugin.getKaiWuManager();
        String locKey = manager.serializeLoc(event.getClickedBlock().getLocation());

        // OP 编辑模式 (金锄头)
        if (player.hasPermission("hjh.kaiwu.op") &&
                player.getInventory().getItemInMainHand().getType() == Material.GOLDEN_HOE) {
            event.setCancelled(true);
            editor.openEditor(player, locKey);
            return;
        }

        // 玩家开采模式
        if (manager.isNode(event.getClickedBlock().getLocation())) {
            event.setCancelled(true);
            manager.startMining(player, event.getClickedBlock().getLocation());
        }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent event) { editor.handleClick(event); }
    @EventHandler
    public void onInvClose(InventoryCloseEvent event) { editor.handleClose(event); }
}