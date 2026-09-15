package com.hjh_database.client

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** 客户端只按通用选择器匹配物品，无需知道武器或技能的名字。 */
data class CooldownItemTarget(val kind: Int, val key: String, val value: String) {
    companion object {
        fun group(group: String) = CooldownItemTarget(2, "", group)

        fun item(item: ItemStack): CooldownItemTarget {
            val pdc = item.itemMeta?.persistentDataContainer
            for (key in listOf("hjh_database:baihu_weapon_id", "hjh_database:weapon_id", "hjh_database:resource_id")) {
                val value = pdc?.get(NamespacedKey.fromString(key)!!, PersistentDataType.STRING) ?: continue
                return CooldownItemTarget(1, key, value)
            }
            return CooldownItemTarget(0, "", item.type.key.toString())
        }
    }
}

data class ItemCooldownVisual(
    val id: String,
    val target: CooldownItemTarget,
    val endMillis: Long,
    val durationMillis: Long,
    val color: Int = 0x7FFFFFFF,
    /** true 只更改原版已有冷却的颜色，false 使用纯渲染计时器。 */
    val tintVanilla: Boolean = false
)

fun interface ItemCooldownVisualProvider {
    fun snapshot(player: Player): List<ItemCooldownVisual>
}

/** 主线程采集。来源读取自身的实际技能冷却，不调用原版 setCooldown。 */
class ItemCooldownVisualRegistry(private val onError: (String, Exception) -> Unit) {
    private val providers = linkedMapOf<String, ItemCooldownVisualProvider>()
    private val failed = hashSetOf<String>()

    fun register(id: String, provider: ItemCooldownVisualProvider) {
        require(id.isNotBlank() && id !in providers) { "Duplicate/blank cooldown provider: $id" }
        providers[id] = provider
    }

    fun snapshot(player: Player, now: Long): List<ItemCooldownVisual> = providers.flatMap { (id, provider) ->
        try {
            val result = provider.snapshot(player).filter { it.endMillis > now && it.durationMillis > 0 }
            failed.remove(id)
            result.map { it.copy(id = "$id/${it.id}") }
        } catch (error: Exception) {
            if (failed.add(id)) onError(id, error)
            emptyList()
        }
    }.take(128)

    fun clear() {
        providers.clear()
        failed.clear()
    }
}
