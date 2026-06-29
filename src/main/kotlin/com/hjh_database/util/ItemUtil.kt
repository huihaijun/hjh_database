package com.hjh_database.util

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

object ItemUtil {
    fun getPublicId(item: ItemStack?): String {
        if (item == null || item.type == Material.AIR) return "AIR"

        val meta = item.itemMeta
        if (meta != null) {
            val plugin = Hjh_database.instance
            val keyId = NamespacedKey(plugin, "resource_id")
            val keyBaihuWeapon = NamespacedKey(plugin, "baihu_weapon_id")
            val keyBaihuArtifact = NamespacedKey(plugin, "baihu_artifact_id")
            val keyWeapon = NamespacedKey(plugin, "weapon_id")

            if (meta.persistentDataContainer.has(keyId, PersistentDataType.STRING)) {
                return meta.persistentDataContainer.get(keyId, PersistentDataType.STRING) ?: "AIR"
            }
            if (meta.persistentDataContainer.has(keyBaihuWeapon, PersistentDataType.STRING)) {
                return meta.persistentDataContainer.get(keyBaihuWeapon, PersistentDataType.STRING) ?: "AIR"
            }
            if (meta.persistentDataContainer.has(keyBaihuArtifact, PersistentDataType.STRING)) {
                return meta.persistentDataContainer.get(keyBaihuArtifact, PersistentDataType.STRING) ?: "AIR"
            }
            if (meta.persistentDataContainer.has(keyWeapon, PersistentDataType.STRING)) {
                return meta.persistentDataContainer.get(keyWeapon, PersistentDataType.STRING) ?: "AIR"
            }
        }
        return item.type.name
    }

    fun isMatch(recipeReq: ItemStack?, input: ItemStack?): Boolean {
        if (recipeReq == null && input == null) return true
        if (recipeReq == null || input == null) return false

        val reqId = getPublicId(recipeReq)
        val inputId = getPublicId(input)

        if (reqId == inputId) {
            if (reqId == input.type.name) {
                return input.type == recipeReq.type
            }
            return true
        }
        return false
    }
}
