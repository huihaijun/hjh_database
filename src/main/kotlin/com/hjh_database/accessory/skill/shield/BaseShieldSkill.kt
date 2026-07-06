// 路径: com.hjh_database.accessory.skill.shield.BaseShieldSkill.kt
package com.hjh_database.accessory.skill.shield

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.weapon.CrystalData
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import java.util.UUID

abstract class BaseShieldSkill(plugin: Hjh_database) : BaseAccessorySkill(plugin) {

    companion object {
        // 全局共享的盾牌技能CD控制
        private val sharedCooldowns = HashMap<UUID, Long>()
        fun getCooldown(player: Player): Long = sharedCooldowns[player.uniqueId] ?: 0L
        fun setCooldown(player: Player, cooldownMillis: Long) {
            sharedCooldowns[player.uniqueId] = System.currentTimeMillis() + cooldownMillis
        }
    }

    /**
     * 子类需实现：获取该盾牌格挡成功后的自定义冷却时间 (毫秒)
     */
    abstract fun getBlockCooldownMillis(crystalData: CrystalData): Long

    /**
     * 子类需实现：当成功抵挡伤害时的特有效果 (例如：发送不同消息、给予特殊Buff、或者反弹伤害等)
     */
    abstract fun onBlockSuccess(player: Player, event: EntityDamageByEntityEvent, crystalData: CrystalData)

    /**
     * 通用的核心举盾抵挡业务逻辑（已被抽象封装）
     */
    fun handleShieldBlock(event: EntityDamageByEntityEvent, player: Player, crystalData: CrystalData) {
        // 1. 自定义特殊盾牌技能CD校验
        val now = System.currentTimeMillis()
        if (now < getCooldown(player)) return

        // 2. 计算攻击方向（防止背刺也被当做格挡成功进入冷却）
        val damager = event.damager
        val playerDir = player.location.direction.normalize()
        val damagerDir = damager.location.subtract(player.location).toVector().normalize()
        val dot = playerDir.dot(damagerDir)

        // dot > 0 说明攻击者在玩家视角的正前方 (原版盾牌只能挡前方)
        if (dot > 0) {
            // 3. 设置双端冷却 (原版盾牌置灰CD + 饰品技能CD由子类决定)
            val cooldownMillis = getBlockCooldownMillis(crystalData)
            player.setCooldown(Material.SHIELD, (cooldownMillis / 50L).toInt().coerceAtLeast(1))
            setCooldown(player, cooldownMillis)

            // 4. 强制打断当前的举盾动作
            player.clearActiveItem()
            // 5. 播放原版盾牌被斧头破防时的“铛”视觉/物理打断特效
            player.playEffect(org.bukkit.EntityEffect.SHIELD_BREAK)

            // 6. 路由给具体的具体盾牌子类去执行它独一无二的效果
            onBlockSuccess(player, event, crystalData)
        }
    }
}
