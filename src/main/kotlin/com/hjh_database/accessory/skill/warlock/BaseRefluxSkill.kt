package com.hjh_database.accessory.skill.warlock

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.accessory.skill.core.AccessoryHudValueKind
import com.hjh_database.accessory.skill.core.AccessorySkillHudState
import com.hjh_database.ui.MenuManager
import com.hjh_database.weapon.CrystalData
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

abstract class BaseRefluxSkill(plugin: Hjh_database) : BaseAccessorySkill(plugin) {
    protected abstract val accessoryId: String
    private val yuanKey = NamespacedKey(plugin, "accessory_yuan")
    private val castsKey = NamespacedKey(plugin, "accessory_yuan_casts")

    override fun getHudState(player: Player, item: ItemStack, crystalData: CrystalData): AccessorySkillHudState =
        super.getHudState(player, item, crystalData).copy(
            // 复用旧客户端已有的数量/上限显示，无需新增客户端类型。
            valueKind = AccessoryHudValueKind.ARROWS,
            currentValue = getStoredYuan(item, crystalData),
            maxValue = crystalData.yuanMaxStorage
        )

    fun depositElements(player: Player, item: ItemStack, elements: ItemStack, crystalData: CrystalData): Boolean {
        if (elements.type.isAir || elements.amount <= 0 || MenuManager.ElementType.entries.none {
                it != MenuManager.ElementType.RELIVE && plugin.menuManager.isPanlingItem(elements, it)
            }) return false
        val meta = item.itemMeta ?: return false
        val stored = (meta.persistentDataContainer.get(yuanKey, PersistentDataType.INTEGER) ?: 0)
            .coerceIn(0, crystalData.yuanMaxStorage)
        val amount = minOf(elements.amount, crystalData.yuanMaxStorage - stored)
        if (amount <= 0) {
            player.sendActionBar("§c【补元】元已达到上限：$stored/${crystalData.yuanMaxStorage}")
            return true
        }
        meta.persistentDataContainer.set(yuanKey, PersistentDataType.INTEGER, stored + amount)
        item.itemMeta = meta
        elements.amount -= amount
        player.sendActionBar("§a【补元】存入 $amount 个元素，当前元：${stored + amount}/${crystalData.yuanMaxStorage}")
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.5f)
        return true
    }

    fun getStoredYuan(item: ItemStack, crystalData: CrystalData): Int =
        (item.itemMeta?.persistentDataContainer?.get(yuanKey, PersistentDataType.INTEGER) ?: 0)
            .coerceIn(0, crystalData.yuanMaxStorage)

    fun consumeSuguiYuan(item: ItemStack, crystalData: CrystalData): Boolean {
        val meta = item.itemMeta ?: return false
        val stored = getStoredYuan(item, crystalData)
        if (stored <= 0) return false
        meta.persistentDataContainer.set(yuanKey, PersistentDataType.INTEGER, stored - 1)
        item.itemMeta = meta
        return true
    }

    fun onFormationCast(item: ItemStack, crystalData: CrystalData) {
        val meta = item.itemMeta ?: return
        var stored = (meta.persistentDataContainer.get(yuanKey, PersistentDataType.INTEGER) ?: 0)
            .coerceIn(0, crystalData.yuanMaxStorage)
        val casts = (meta.persistentDataContainer.get(castsKey, PersistentDataType.INTEGER) ?: 0)
            .coerceAtLeast(0) + 1
        if (casts >= crystalData.yuanCastInterval) {
            stored = minOf(crystalData.yuanMaxStorage, stored + crystalData.yuanCastGain)
        }
        meta.persistentDataContainer.set(castsKey, PersistentDataType.INTEGER, casts % crystalData.yuanCastInterval)

        meta.persistentDataContainer.set(yuanKey, PersistentDataType.INTEGER, stored)
        item.itemMeta = meta
    }
}
