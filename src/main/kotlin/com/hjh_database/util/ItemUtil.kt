package com.hjh_database.util

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

object ItemUtil {

    /**
     * 获取物品的唯一标识符
     * 1. 优先读取 NBT 中的 resource_id (新版)
     * 2. 其次读取 weapon_id / armor_id (兼容旧版)
     * 3. 如果都没有，返回材质名 (原版物品)
     */
    fun getPublicId(item: ItemStack?): String {
        if (item == null || item.type == Material.AIR) return "AIR"

        val meta = item.itemMeta
        if (meta != null) {
            // 获取插件实例 (注意 Kotlin 中获取 Java Class 的写法)
            val plugin = Hjh_database.instance
            val keyId = NamespacedKey(plugin, "resource_id")
            val keyWeapon = NamespacedKey(plugin, "weapon_id") // 兼容旧武器

            // 1. 查 resource_id
            if (meta.persistentDataContainer.has(keyId, PersistentDataType.STRING)) {
                return meta.persistentDataContainer.get(keyId, PersistentDataType.STRING) ?: "AIR"
            }
            // 2. 查 weapon_id
            if (meta.persistentDataContainer.has(keyWeapon, PersistentDataType.STRING)) {
                return meta.persistentDataContainer.get(keyWeapon, PersistentDataType.STRING) ?: "AIR"
            }
        }
        // 3. 原版物品
        return item.type.name
    }

    /**
     * 判断两个物品是否匹配
     * 逻辑：只要 ID 一样就算匹配，不需要管 Lore/耐久/附魔
     */
    fun isMatch(recipeReq: ItemStack?, input: ItemStack?): Boolean {
        if (recipeReq == null && input == null) return true
        if (recipeReq == null || input == null) return false

        val reqId = getPublicId(recipeReq)
        val inputId = getPublicId(input)

        // 如果 ID 相同
        if (reqId == inputId) {
            // 如果是原版物品 (比如都是 IRON_SWORD)，额外检查一下材质是否真的相同
            // (防止 resource_id 读取失败导致都变成了 Material 名)
            if (reqId == input.type.name) {
                return input.type == recipeReq.type
            }
            return true // RPG 物品只要 ID 对了就行
        }
        return false
    }
}