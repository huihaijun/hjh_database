package com.hjh_database.accessory.element.mastery.warrior

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
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class FireMasteryWarrior(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 15000L
        const val CRITICAL_MULTIPLIER = 1.5
        const val EXECUTE_DAMAGE_CAP = 150.0
    }

    private val cd = ConcurrentHashMap<UUID, Long>()
    private val stacks = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, StackData>>()

    data class StackData(
        var count: Int,
        var expireTime: Long
    )

    fun onDamageDealt(player: Player, victim: LivingEntity, eData: ElementCrystalData, pData: PlayerData, isNormalAttack: Boolean) {
        if (eData.firePoints < 4) return
        if (!isNormalAttack) return
        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        // 判定目标是否为合法怪物
        if (!isValidTarget(victim)) return

        val playerStacks = stacks.getOrPut(uuid) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()

        // 清理已过期的叠层
        playerStacks.entries.removeIf { it.value.expireTime < now }

        val stackData = playerStacks.getOrPut(victim.entityId) { StackData(0, 0L) }
        if (stackData.expireTime < now) {
            stackData.count = 0
        }

        stackData.count++
        stackData.expireTime = now + 5000L // 持续5秒

        if (stackData.count >= 3) {
            // 叠满3层，移除标记，触发斩击并进入冷却
            playerStacks.remove(victim.entityId)
            cd[uuid] = now + CD_MS
            trigger(player, victim, pData)
        } else {
            if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warrior.fire.stack_${stackData.count}")) {
                player.sendMessage("§c[火·炎斩] 叠层中... 当前层数: §b${stackData.count}/3")
            }
            // 叠层粒子
            victim.world.spawnParticle(Particle.FLAME, victim.location.add(0.0, victim.height * 0.5, 0.0), 3, 0.15, 0.15, 0.15, 0.05)
            victim.world.playSound(victim.location, Sound.BLOCK_FIRE_AMBIENT, 1.0f, 1.5f)
        }
    }

    fun cleanup(uuid: UUID) {
        cd.remove(uuid)
        stacks.remove(uuid)
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
        if (!plugin.passiveSubtitleManager.showCombatEvent(player, "element.mastery.warrior.fire")) {
            player.sendMessage("§c[火·精进] [炎斩] §f已触发")
        }
        val targetMaxHealth = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        // 炎斩触发伤害必定暴击；8%最大生命的斩杀段在暴击后仍最多贡献150点。
        val criticalAttackDamage = pData.attack * 2.5 * CRITICAL_MULTIPLIER
        val criticalExecuteDamage = (targetMaxHealth * 0.08 * CRITICAL_MULTIPLIER).coerceAtMost(EXECUTE_DAMAGE_CAP)
        val damage = criticalAttackDamage + criticalExecuteDamage
        val world = victim.world

        // 从天而降的火柱动画 (4 ticks 递降)
        object : BukkitRunnable() {
            var currentHeight = 8.0

            override fun run() {
                if (!victim.isValid || victim.isDead) {
                    cancel()
                    return
                }

                if (currentHeight <= 0.0) {
                    // 砸到地面，触发伤害与范围粒子
                    magicDamage(player, victim, damage)
                    world.spawnParticle(Particle.CRIT, victim.location.add(0.0, victim.height * 0.55, 0.0), 24, 0.35, 0.45, 0.35, 0.12)
                    world.playSound(victim.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.75f)
                    world.playSound(victim.location, Sound.ITEM_FIRECHARGE_USE, 1.5f, 0.8f)
                    world.playSound(victim.location, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 1.2f)

                    // 溅射火焰波 (无 EXPLODE 粒子)
                    val loc = victim.location.add(0.0, 0.5, 0.0)
                    for (r in listOf(0.5, 1.5, 2.5)) {
                        val count = (r * 8).toInt()
                        for (i in 0 until count) {
                            val angle = Math.PI * 2.0 * i / count
                            val point = loc.clone().add(cos(angle) * r, 0.0, sin(angle) * r)
                            world.spawnParticle(Particle.FLAME, point, 1, 0.05, 0.05, 0.05, 0.02)
                            world.spawnParticle(Particle.LAVA, point, 1, 0.05, 0.05, 0.05, 0.0)
                        }
                    }
                    cancel()
                    return
                }

                // 绘制下落火圈
                val stepLoc = victim.location.add(0.0, currentHeight, 0.0)
                for (i in 0 until 12) {
                    val angle = Math.PI * 2.0 * i / 12.0
                    val point = stepLoc.clone().add(cos(angle) * 0.6, 0.0, sin(angle) * 0.6)
                    world.spawnParticle(Particle.FLAME, point, 1, 0.02, 0.02, 0.02, 0.01)
                }
                world.spawnParticle(
                    Particle.DUST,
                    stepLoc,
                    4,
                    0.2, 0.02, 0.2,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(255, 60, 0), 1.3f)
                )

                currentHeight -= 2.0
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun magicDamage(attacker: Player, target: LivingEntity, damage: Double) {
        if (damage <= 0.0 || !target.isValid || target.isDead) return
        target.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        target.noDamageTicks = 0
        try {
            target.damage(damage, attacker)
        } finally {
            if (target.hasMetadata("HJH_MAGIC_DAMAGE")) {
                target.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
            }
            target.noDamageTicks = 0
        }
    }
}
