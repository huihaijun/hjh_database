package com.hjh_database.qixiazhen.busuan

import com.hjh_database.Hjh_database
import com.hjh_database.qixiazhen.busuan.model.Fortune
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 重新应用不能写入 Resource YAML 模板的卜算物品动态信息。 */
object BusuanItemLore {
    private val displayDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
    private val acquisitionLore = Regex("^§f于\\d{4}年\\d{1,2}月\\d{1,2}日获得，请在一天内右键使用$")

    fun restore(plugin: Hjh_database, item: ItemStack, resourceId: String? = null): Boolean {
        val meta = item.itemMeta ?: return false
        val id = resourceId ?: meta.persistentDataContainer.get(
            NamespacedKey(plugin, "resource_id"),
            PersistentDataType.STRING
        )
        if (Fortune.byResourceId(id) == null) return false

        val rawDate = meta.persistentDataContainer.get(
            NamespacedKey(plugin, "busuan_fortune_date"),
            PersistentDataType.STRING
        ) ?: return false
        val date = runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: return false
        val line = "§f于${date.format(displayDateFormatter)}获得，请在一天内右键使用"
        val lore = (meta.lore ?: emptyList()).filterNot(acquisitionLore::matches).toMutableList()
        lore.add(line)
        meta.lore = lore
        item.itemMeta = meta
        return true
    }
}
