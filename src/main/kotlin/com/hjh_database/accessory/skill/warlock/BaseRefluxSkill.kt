// 路径: com.hjh_database.accessory.skill.warlock.BaseRefluxSkill.kt
package com.hjh_database.accessory.skill.warlock

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.weapon.CrystalData
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

abstract class BaseRefluxSkill(plugin: Hjh_database) : BaseAccessorySkill(plugin) {

    protected abstract val accessoryId: String

    companion object {
        // 玩家 PDC 会随退服保存，且 /reload 后仍由同一在线 Player 实体保留。
        private val REFLUX_ACTIVE_KEY = NamespacedKey.fromString("hjh_database:reflux_active")!!

        fun isRefluxActive(player: Player): Boolean =
            player.persistentDataContainer.get(REFLUX_ACTIVE_KEY, PersistentDataType.BYTE)?.toInt() == 1

        private fun setRefluxActive(player: Player, active: Boolean) {
            if (active) {
                player.persistentDataContainer.set(REFLUX_ACTIVE_KEY, PersistentDataType.BYTE, 1.toByte())
            } else {
                player.persistentDataContainer.remove(REFLUX_ACTIVE_KEY)
            }
        }
    }

    // ==========================================
    // 抽象方法：由具体的饰品子类来实现它们各自的数值
    // ==========================================

    /**
     * 获取触发回流所需的灵力百分比阈值 (例如 0.5 代表 50%)
     */
    abstract fun getThresholdPercent(crystalData: CrystalData): Double

    /**
     * 获取回流状态下，每级阵法需要消耗的灵力值 (例如 10.0)
     */
    abstract fun getCostPerLevel(crystalData: CrystalData): Double

    /**
     * 【新增】获取触发回流的概率 (0.0 到 1.0，例如 0.35 代表 35%)
     */
    abstract fun getTriggerProbability(crystalData: CrystalData): Double


    // ==========================================
    // 通用的开启/关闭逻辑，子类不需要再重写了
    // ==========================================
    override fun handleShiftClick(player: Player, item: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        if (isExtract) {
            setRefluxActive(player, false)
            return false
        }

        if (isRefluxActive(player)) {
            setRefluxActive(player, false)
            if (!plugin.passiveSubtitleManager.showAccessoryTrigger(player, accessoryId, "disabled")) {
                player.sendMessage("§c关闭【回流】模式，释放阵法将正常消耗元素。")
            }
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 0.8f)
        } else {
            setRefluxActive(player, true)
            if (!plugin.passiveSubtitleManager.showAccessoryTrigger(player, accessoryId, "enabled")) {
                player.sendMessage("§a开启【回流】模式！灵力充沛时，释放阵法将消耗灵力。")
            }
            player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f)
        }
        return true
    }
}
