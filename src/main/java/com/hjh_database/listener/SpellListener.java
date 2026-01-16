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
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack mainHandItem = player.getInventory().getItemInMainHand();

        // 【优化】直接调用 Manager 判断副手是否激活
        // 这一步代替了之前的长串逻辑
        if (plugin.getPlayerManager().getWeaponManager().getActiveOffHandWeaponId(player) == null) {
            return; // 副手没激活，直接撤
        }

        // 检查主手是否持有元素
        MenuManager.ElementType elementType = getElementType(mainHandItem);
        if (elementType == null) {
            return;
        }

        event.setCancelled(true);

        plugin.getElementZfManager().castSkill(
                player,
                elementType.name(),
                plugin.getPlayerManager().getData(player.getUniqueId())
        );
    }

    private MenuManager.ElementType getElementType(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        for (MenuManager.ElementType type : MenuManager.ElementType.values()) {
            if (type == MenuManager.ElementType.RELIVE) continue;
            if (plugin.getMenuManager().isPanlingItem(item, type)) {
                return type;
            }
        }
        return null;
    }
}