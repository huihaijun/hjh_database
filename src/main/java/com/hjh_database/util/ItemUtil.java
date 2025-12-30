package com.hjh_database.util;

import com.hjh_database.Hjh_database;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class ItemUtil {

    /**
     * 获取物品的唯一标识符
     * 1. 优先读取 NBT 中的 resource_id (新版)
     * 2. 其次读取 weapon_id / armor_id (兼容旧版)
     * 3. 如果都没有，返回材质名 (原版物品)
     */
    public static String getPublicId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "AIR";

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Hjh_database plugin = Hjh_database.getPlugin(Hjh_database.class);
            NamespacedKey keyId = new NamespacedKey(plugin, "resource_id");
            NamespacedKey keyWeapon = new NamespacedKey(plugin, "weapon_id"); // 兼容旧武器

            // 1. 查 resource_id
            if (meta.getPersistentDataContainer().has(keyId, PersistentDataType.STRING)) {
                return meta.getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
            }
            // 2. 查 weapon_id
            if (meta.getPersistentDataContainer().has(keyWeapon, PersistentDataType.STRING)) {
                return meta.getPersistentDataContainer().get(keyWeapon, PersistentDataType.STRING);
            }
        }
        // 3. 原版物品
        return item.getType().name();
    }

    /**
     * 判断两个物品是否匹配
     * 逻辑：只要 ID 一样就算匹配，不需要管 Lore/耐久/附魔
     */
    public static boolean isMatch(ItemStack recipeReq, ItemStack input) {
        if (recipeReq == null && input == null) return true;
        if (recipeReq == null || input == null) return false;

        String reqId = getPublicId(recipeReq);
        String inputId = getPublicId(input);

        // 如果 ID 相同
        if (reqId.equals(inputId)) {
            // 如果是原版物品 (比如都是 IRON_SWORD)，额外检查一下材质是否真的相同
            // (防止 resource_id 读取失败导致都变成了 Material 名)
            if (reqId.equals(input.getType().name())) {
                return input.getType() == recipeReq.getType();
            }
            return true; // RPG 物品只要 ID 对了就行
        }
        return false;
    }
}