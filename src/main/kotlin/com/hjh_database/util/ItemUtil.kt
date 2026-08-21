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
            val idKeys = listOf(
                "artifact_id",
                "baihu_artifact_id",
                "baihu_weapon_id",
                "crystal_id",
                "weapon_id",
                "armor_id",
                "resource_id"
            )

            for (keyName in idKeys) {
                val id = meta.persistentDataContainer.get(
                    NamespacedKey(plugin, keyName),
                    PersistentDataType.STRING
                )
                if (!id.isNullOrBlank()) return id
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
