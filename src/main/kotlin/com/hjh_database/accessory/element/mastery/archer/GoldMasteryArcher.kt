package com.hjh_database.accessory.element.mastery.archer

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalData
import com.hjh_database.data.PlayerData
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.AbstractArrow
import org.bukkit.metadata.FixedMetadataValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GoldMasteryArcher(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 10000L // 10秒冷却
    }

    private val cd = ConcurrentHashMap<UUID, Long>()

    fun onDamageDealt(
        player: Player,
        victim: LivingEntity,
        eData: ElementCrystalData,
        pData: PlayerData,
        isArrowHit: Boolean,
        arrow: AbstractArrow?
    ) {
        if (eData.goldPoints < 4) return
        if (!isArrowHit) return
        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        // 判定目标是否为合法怪物
        if (!isValidTarget(victim)) return

        // 距离判断：距离超过 10 格
        val distance = player.location.distance(victim.location)
        if (distance <= 10.0) return

        // 触发技能：进入冷却
        cd[uuid] = System.currentTimeMillis() + CD_MS
        trigger(player, victim, pData)
    }

    fun cleanup(uuid: UUID) {
        cd.remove(uuid)
    }

    private fun isOnCd(uuid: UUID): Boolean {
        val end = cd[uuid] ?: return false
        if (System.currentTimeMillis() >= end) {
            cd.remove(uuid)
            return false
        }
        return true
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        return entity !is Player &&
               entity !is ArmorStand &&
               !entity.isDead &&
               entity.isValid &&
               entity.scoreboardTags.contains("panling") &&
               entity.scoreboardTags.contains("monster")
    }

    private fun trigger(player: Player, victim: LivingEntity, pData: PlayerData) {
        // 伤害值：200% 箭矢强度 (非穿甲)
        val damage = pData.archerDamage * 2.0

        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.archer.metal")) {
            player.sendMessage("§e[弓] [金·精进] [鸣镝] §f已触发")
        }
        
        val world = victim.world
        world.playSound(victim.location, Sound.ENTITY_ARROW_HIT_PLAYER, 1.2f, 0.5f)
        world.playSound(victim.location, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0f, 1.8f)

        // 绘制流星般的尾迹粒子
        val start = player.eyeLocation.clone()
        val end = victim.location.clone().add(0.0, victim.height * 0.5, 0.0)
        val dist = start.distance(end)
        val steps = (dist * 2.0).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val loc = start.clone().add(end.clone().subtract(start).multiply(t))
            val color = if (i % 2 == 0) Color.fromRGB(255, 215, 0) else Color.fromRGB(255, 255, 255)
            world.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, 0.8f))
            if (i % 3 == 0) {
                world.spawnParticle(Particle.CRIT, loc, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }

        // 造成非穿甲魔法伤害
        armoredMagicDamage(player, victim, damage)
    }

    private fun armoredMagicDamage(attacker: Player, target: LivingEntity, damage: Double) {
        if (damage <= 0.0 || !target.isValid || target.isDead) return
        target.setMetadata("HJH_ARMORED_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        target.noDamageTicks = 0
        try {
            target.damage(damage, attacker)
        } finally {
            if (target.hasMetadata("HJH_ARMORED_MAGIC_DAMAGE")) {
                target.removeMetadata("HJH_ARMORED_MAGIC_DAMAGE", plugin)
            }
            target.noDamageTicks = 0
        }
    }
}
