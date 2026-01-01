package com.hjh_database.listener;

import com.hjh_database.Hjh_database;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class WeaponSkillListener implements Listener {
    private final Hjh_database plugin;
    private final NamespacedKey weaponKey;

    public WeaponSkillListener(Hjh_database plugin) {
        this.plugin = plugin;
        this.weaponKey = new NamespacedKey(plugin, "weapon_id");
    }

    // 战士触发
    @EventHandler
    public void onWarriorTrigger(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        String weaponId = getWeaponId(item);
        if (weaponId == null) return;

        String typeName = item.getType().toString();
        if (typeName.contains("SWORD") || typeName.contains("AXE")) {
            // 战士没有投射物，传 null
            plugin.getWeaponSkillManager().tryCastSkill(player, weaponId, item, null);
        }
    }

    // 弓箭手触发
    @EventHandler
    public void onArcherTrigger(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!player.isSneaking()) return;

        ItemStack bow = event.getBow();
        if (bow == null || !bow.hasItemMeta()) return;

        String weaponId = getWeaponId(bow);
        if (weaponId == null) return;

        // 【关键】传入射出的箭矢 (event.getProjectile())
        plugin.getWeaponSkillManager().tryCastSkill(player, weaponId, bow, event.getProjectile());
    }

    private String getWeaponId(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
    }
}