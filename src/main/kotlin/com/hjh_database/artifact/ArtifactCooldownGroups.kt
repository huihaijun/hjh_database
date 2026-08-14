package com.hjh_database.artifact

import com.hjh_database.Hjh_database
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.UseCooldown
import net.kyori.adventure.key.Key
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 给同材质的法器分配独立的客户端冷却动画组。 */
internal object ArtifactCooldownGroups {
    fun reducedDurationMillis(plugin: Hjh_database, player: Player, baseDurationMillis: Long): Long {
        val reduction = (plugin.playerManager.getData(player.uniqueId)?.coolReduce ?: 0.0).coerceIn(0.0, 0.5)
        return (baseDurationMillis * (1.0 - reduction)).toLong().coerceAtLeast(50L)
    }

    fun ensure(plugin: Hjh_database, item: ItemStack, artifactId: String) {
        if (item.type.isAir) return

        val group = Key.key(NamespacedKey(plugin, "artifact_$artifactId").toString())
        val current = item.getData(DataComponentTypes.USE_COOLDOWN)
        if (current?.cooldownGroup() == group) return

        item.setData(
            DataComponentTypes.USE_COOLDOWN,
            UseCooldown.useCooldown(0.1f)
                .cooldownGroup(group)
                .build()
        )
    }
}
