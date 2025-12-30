package com.hjh_database.listener;

import com.hjh_database.Hjh_database;
import com.hjh_database.ui.MenuManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class SpellListener implements Listener {
    private final Hjh_database plugin;

    public SpellListener(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCastSpell(PlayerInteractEvent event) {
        // 1. 只监听主手的交互
        if (event.getHand() != EquipmentSlot.HAND) return;

        // 2. 必须是右键动作
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack mainHandItem = player.getInventory().getItemInMainHand();

        // 3. 【核心判定】检查副手是否持有“已激活”的术士法器
        // 这一步代替了数据库查询，直接从内存判断，非常高效
        String weaponId = plugin.getPlayerManager().getWeaponManager().getActiveOffHandWeaponId(player);
        if (weaponId == null) {
            return; // 副手不合格，不触发
        }

        // 4. 检查主手是否持有元素
        MenuManager.ElementType elementType = getElementType(mainHandItem);
        if (elementType == null) {
            return; // 主手拿的不是元素，不触发
        }

        event.setCancelled(true); // 阻止原版交互（防止把元素物品当方块放下来）

        // 5. 【连接后端】调用 ElementZfManager 执行技能
        plugin.getElementZfManager().castSkill(
                player,
                elementType.name(), // 获取枚举名称，例如 "METAL"
                plugin.getPlayerManager().getData(player.getUniqueId())
        );
    }

    // 辅助方法：判断手中的物品属于哪种元素
    private MenuManager.ElementType getElementType(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;

        for (MenuManager.ElementType type : MenuManager.ElementType.values()) {
            if (type == MenuManager.ElementType.RELIVE) continue; // 重生石不是施法材料
            // 复用 MenuManager 的判断逻辑
            if (plugin.getMenuManager().isPanlingItem(item, type)) {
                return type;
            }
        }
        return null;
    }
}