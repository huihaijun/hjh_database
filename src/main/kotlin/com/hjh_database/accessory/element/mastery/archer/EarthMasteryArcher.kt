package com.hjh_database.accessory.element.mastery.archer

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.element.ElementCrystalData
import com.hjh_database.data.PlayerData
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.AbstractArrow
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EarthMasteryArcher(private val plugin: Hjh_database) {
    companion object {
        const val CD_MS = 15000L // 15秒冷却
        const val META_ARMOR_REDUCE = "HJH_ARCHER_EARTH_ARMOR_REDUCE"
    }

    private val cd = ConcurrentHashMap<UUID, Long>()

    fun onDamageDealt(
        player: Player,
        victim: LivingEntity,
        eData: ElementCrystalData,
        pData: PlayerData,
        isArrowHit: Boolean
    ) {
        if (eData.earthPoints < 4) return
        if (!isArrowHit) return
        if (!isValidTarget(victim)) return

        val uuid = player.uniqueId
        if (isOnCd(uuid)) return

        // 触发岩钉：进入冷却
        cd[uuid] = System.currentTimeMillis() + CD_MS
        player.sendMessage("§6[弓] [土·精进] [岩钉] §f已触发")

        val world = victim.world
        world.playSound(victim.location, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1.2f, 0.7f)

        // 施加定身 (1.5秒 = 30 ticks)
        victim.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30, 127, false, false, false))
        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 30, 0, false, false, false))

        // 生成滴水石 ItemDisplay 钉住双腿
        val spawnLoc = victim.location.clone()
        val display = world.spawn(spawnLoc, ItemDisplay::class.java) {
            it.setItemStack(ItemStack(Material.POINTED_DRIPSTONE))
            val transform = it.transformation
            transform.scale.set(Vector3f(1.3f, 1.3f, 1.3f))
            it.transformation = transform
        }

        // 持续 1.5 秒定身与追踪，结束后爆裂减防
        object : BukkitRunnable() {
            var tick = 0

            override fun run() {
                if (!victim.isValid || victim.isDead || tick >= 30) {
                    display.remove()
                    if (tick >= 30 && victim.isValid && !victim.isDead) {
                        // 1.5秒定身结束：爆开
                        victim.setMetadata(META_ARMOR_REDUCE, FixedMetadataValue(plugin, System.currentTimeMillis() + 5000L)) // 5秒减防 30%
                        
                        // 岩钉爆裂特效 (无 EXPLODE)
                        victim.world.playSound(victim.location, Sound.BLOCK_STONE_BREAK, 1.2f, 0.8f)
                        victim.world.playSound(victim.location, Sound.ENTITY_WITHER_BREAK_BLOCK, 0.8f, 0.6f)
                        
                        victim.world.spawnParticle(
                            Particle.BLOCK,
                            victim.location.add(0.0, 0.2, 0.0),
                            20,
                            0.2, 0.2, 0.2,
                            0.05,
                            Material.POINTED_DRIPSTONE.createBlockData()
                        )
                        victim.world.spawnParticle(
                            Particle.CRIT,
                            victim.location.add(0.0, 0.5, 0.0),
                            10,
                            0.3, 0.3, 0.3,
                            0.05
                        )
                    }
                    cancel()
                    return
                }

                // 强制重置速度，确保钉住
                victim.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
                victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 5, 127, false, false, false))
                victim.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 5, 0, false, false, false))
                
                // 钉子位置跟随怪物脚部
                display.teleport(victim.location)

                // 滴水石滴水粒子效果
                if (tick % 5 == 0) {
                    world.spawnParticle(Particle.DRIPPING_DRIPSTONE_WATER, victim.location.add(0.0, 1.5, 0.0), 3, 0.2, 0.2, 0.2, 0.0)
                }

                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
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
}
