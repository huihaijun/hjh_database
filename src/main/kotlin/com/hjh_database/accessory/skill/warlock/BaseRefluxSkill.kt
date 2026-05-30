// 路径: com.hjh_database.accessory.skill.warlock.BaseRefluxSkill.kt
package com.hjh_database.accessory.skill.warlock

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.weapon.CrystalData
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

abstract class BaseRefluxSkill(plugin: Hjh_database) : BaseAccessorySkill(plugin) {

    companion object {
        // 全局管理开启了【回流】状态的玩家
        val refluxActivePlayers = mutableSetOf<UUID>()
        fun isRefluxActive(player: Player): Boolean = refluxActivePlayers.contains(player.uniqueId)
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
            refluxActivePlayers.remove(player.uniqueId)
            return false
        }

        val uuid = player.uniqueId
        if (refluxActivePlayers.contains(uuid)) {
            refluxActivePlayers.remove(uuid)
            player.sendMessage("§c关闭【回流】模式，释放阵法将正常消耗元素。")
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 0.8f)
        } else {
            refluxActivePlayers.add(uuid)
            player.sendMessage("§a开启【回流】模式！灵力充沛时，释放阵法将消耗灵力。")
            player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f)
        }
        return true
    }
}