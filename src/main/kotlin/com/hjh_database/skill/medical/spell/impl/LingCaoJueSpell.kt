package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.Vector3f
import java.util.UUID

class LingCaoJueSpell(private val plugin: Hjh_database) : MedicalSpell {

    companion object {
        // 全局静态存储当前存活的所有灵草，用于多玩家释放时的“最近索敌”逻辑
        val activePlants = mutableListOf<PlantData>()
    }

    // 用于记录单颗灵草的数据
    data class PlantData(val id: UUID, val location: Location, val targetAnchor: ArmorStand)

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        // --- 1. 读取配置与计算属性 ---
        val zfStr = data.zfStr
        val damageMultiplier = config?.getDouble("damage_multiplier", 2.0) ?: 2.0
        val finalDamage = zfStr * damageMultiplier

        val tauntRadius = config?.getDouble("taunt_radius", 5.0) ?: 5.0
        val explosionRadius = config?.getDouble("radius", 3.0) ?: 3.0
        val durationTicks = (config?.getInt("duration", 4) ?: 4) * 20

        val center = player.location.clone()

        // --- 2. 生成实体 ---
        // 生成隐形 ArmorStand 作为怪物的仇恨锚点 (ArmorStand 是 LivingEntity，怪物可以 Target 它)
        val anchor = center.world.spawn(center, ArmorStand::class.java) {
            it.isVisible = false
            it.isMarker = true // 设为 Marker 无法被破坏和碰撞
            it.setGravity(false)
            it.isSmall = true
        }

        // 生成 ItemDisplay 用于视觉展示 (冒险模式下玩家无法破坏 ItemDisplay)
        val display = center.world.spawn(center.clone().add(0.0, 0.5, 0.0), ItemDisplay::class.java) {
            it.setItemStack(ItemStack(Material.FERN)) // 视觉为一株蕨类灵草
            // 让灵草稍微变大一点
            val transform = it.transformation
            transform.scale.set(Vector3f(1.5f, 1.5f, 1.5f))
            it.transformation = transform
        }

        // 注册到全局存活列表
        val plantId = UUID.randomUUID()
        val plantData = PlantData(plantId, center, anchor)
        activePlants.add(plantData)

        // 播放施法音效和生成粒子
        center.world.playSound(center, Sound.BLOCK_GRASS_PLACE, 1.0f, 1.0f)
        center.world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0.0, 0.5, 0.0), 10, 0.3, 0.3, 0.3, 0.0)

        // 释放瞬间固定一次嘲讽名单；后续只维持这批怪物的仇恨，不再吸引新进入范围的怪。
        val tauntedMobIds = captureTauntTargets(center, tauntRadius, anchor)

        // --- 3. 核心循环任务：维持初始嘲讽名单 + 倒计时爆炸 ---
        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (ticks >= durationTicks) {
                    explode(player, center, finalDamage, explosionRadius)
                    cleanup()
                    cancel()
                    return
                }

                if (ticks % 10 == 0) {
                    refreshTauntedMobs(tauntedMobIds, anchor)
                }

                ticks += 5 // 任务本身每 5 ticks 执行一次(为了倒计时精确)
            }

            // 清理方法：移除实体和全局注册
            private fun cleanup() {
                activePlants.remove(plantData)
                if (!anchor.isDead) anchor.remove()
                if (!display.isDead) display.remove()
            }
        }.runTaskTimer(plugin, 0L, 5L)

        return true
    }

    private fun captureTauntTargets(center: Location, tauntRadius: Double, anchor: ArmorStand): List<UUID> {
        val targetIds = mutableListOf<UUID>()
        val nearbyEntities = center.world.getNearbyEntities(center, tauntRadius, tauntRadius, tauntRadius)
        for (entity in nearbyEntities) {
            if (entity is Mob && entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")) {
                targetIds.add(entity.uniqueId)
                redirectMobToAnchor(entity, anchor)
            }
        }
        return targetIds
    }

    private fun refreshTauntedMobs(targetIds: List<UUID>, anchor: ArmorStand) {
        for (targetId in targetIds) {
            val mob = Bukkit.getEntity(targetId) as? Mob ?: continue
            if (!mob.isValid || mob.isDead) continue
            if (!mob.scoreboardTags.contains("panling") || !mob.scoreboardTags.contains("monster")) continue
            redirectMobToAnchor(mob, anchor)
        }
    }

    private fun redirectMobToAnchor(mob: Mob, anchor: ArmorStand) {
        mob.target = anchor
        try {
            mob.pathfinder.moveTo(anchor.location)
        } catch (ignored: Exception) {
            // 部分服务端不支持直接改寻路目标，保留 target 即可。
        }
    }

    // 爆炸与伤害结算逻辑
    private fun explode(player: Player, center: Location, damage: Double, radius: Double) {
        val world = center.world

        // 播放爆炸特效和音效 (使用不破坏地形的粒子模拟)
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.5f)
        world.spawnParticle(Particle.EXPLOSION, center.clone().add(0.0, 0.5, 0.0), 2, 0.5, 0.5, 0.5, 0.0)
        world.spawnParticle(Particle.ITEM_SLIME, center.clone().add(0.0, 0.5, 0.0), 30, 1.5, 0.5, 1.5, 0.0)

        // 寻找范围内的怪物并造成伤害
        val nearby = world.getNearbyEntities(center, radius, radius, radius)
        for (entity in nearby) {
            if (entity is LivingEntity && entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")) {

                // 【关键要求】取消无敌帧
                entity.noDamageTicks = 0

                // 标记为法术伤害 (参考术士的伤害逻辑)
                plugin.medicalSpellManager.applyMedicalDamage(player, entity, damage, "lingcaojue")
            }
        }
    }
}
