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
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LingCaoJueSpell(private val plugin: Hjh_database) : MedicalSpell, Listener {

    companion object {
        // 全局静态存储当前存活的所有灵草，用于多玩家释放时的“最近索敌”逻辑
        val activePlants = mutableListOf<PlantData>()
        private val activeTaunts = ConcurrentHashMap<UUID, UUID>()
        private val listenerRegistered = AtomicBoolean(false)
    }

    // 用于记录单颗灵草的数据
    data class PlantData(val id: UUID, val location: Location)

    init {
        if (listenerRegistered.compareAndSet(false, true)) {
            plugin.server.pluginManager.registerEvents(this, plugin)
        }
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        // --- 1. 读取配置与计算属性 ---
        val zfStr = data.zfStr
        val damageMultiplier = config?.getDouble("damage_multiplier", 2.0) ?: 2.0
        val finalDamage = zfStr * damageMultiplier

        val tauntRadius = config?.getDouble("taunt_radius", 5.0) ?: 5.0
        val explosionRadius = config?.getDouble("radius", 3.0) ?: 3.0
        val durationTicks = (config?.getInt("duration", 4) ?: 4) * 20

        val center = player.location.clone()

        // --- 2. 仅生成一个高性能 ItemDisplay 作为视觉实体 ---
        val display = center.world.spawn(center.clone().add(0.0, 0.5, 0.0), ItemDisplay::class.java) {
            it.setItemStack(ItemStack(Material.FERN)) // 视觉为一株蕨类灵草
            // 让灵草稍微变大一点
            val transform = it.transformation
            transform.scale.set(Vector3f(1.5f, 1.5f, 1.5f))
            it.transformation = transform
        }

        // 注册到全局存活列表
        val plantId = UUID.randomUUID()
        val plantData = PlantData(plantId, center)
        activePlants.add(plantData)

        // 播放施法音效和生成粒子
        center.world.playSound(center, Sound.BLOCK_GRASS_PLACE, 1.0f, 1.0f)
        center.world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0.0, 0.5, 0.0), 10, 0.3, 0.3, 0.3, 0.0)
        drawTauntRadius(center, tauntRadius)

        // 释放瞬间固定一次嘲讽名单；后续只维持这批怪物的仇恨，不再吸引新进入范围的怪。
        val tauntedMobIds = captureTauntTargets(center, tauntRadius, plantId)

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

                // 目标事件会阻止怪物转火；寻路只需每0.5秒刷新，避免每tick重算路径。
                if (ticks % 10 == 0) {
                    refreshTauntedMobs(tauntedMobIds, center, plantId)
                }
                ticks += 5
            }

            // 清理方法：移除实体和全局注册
            private fun cleanup() {
                activePlants.remove(plantData)
                for (mobId in tauntedMobIds) activeTaunts.remove(mobId, plantId)
                if (!display.isDead) display.remove()
            }
        }.runTaskTimer(plugin, 0L, 5L)

        return true
    }

    private fun captureTauntTargets(center: Location, tauntRadius: Double, plantId: UUID): List<UUID> {
        val targetIds = mutableListOf<UUID>()
        val radiusSquared = tauntRadius * tauntRadius
        val nearbyEntities = center.world.getNearbyEntities(center, tauntRadius, tauntRadius, tauntRadius)
        for (entity in nearbyEntities) {
            if (
                entity is Mob &&
                entity.isValid &&
                !entity.isDead &&
                entity.scoreboardTags.contains("panling") &&
                entity.scoreboardTags.contains("monster") &&
                !entity.scoreboardTags.contains("instance_boss") &&
                entity.location.distanceSquared(center) <= radiusSquared
            ) {
                targetIds.add(entity.uniqueId)
                activeTaunts[entity.uniqueId] = plantId
                redirectMobToPlant(entity, center)
            }
        }
        return targetIds
    }

    private fun refreshTauntedMobs(targetIds: List<UUID>, center: Location, plantId: UUID) {
        for (targetId in targetIds) {
            if (activeTaunts[targetId] != plantId) continue
            val mob = Bukkit.getEntity(targetId) as? Mob ?: continue
            if (!mob.isValid || mob.isDead) {
                activeTaunts.remove(targetId, plantId)
                continue
            }
            if (!mob.scoreboardTags.contains("panling") || !mob.scoreboardTags.contains("monster")) continue
            if (mob.scoreboardTags.contains("instance_boss")) {
                activeTaunts.remove(targetId, plantId)
                continue
            }
            redirectMobToPlant(mob, center)
        }
    }

    private fun redirectMobToPlant(mob: Mob, center: Location) {
        // ItemDisplay 不是 LivingEntity，不能赋给 Mob.target；取消生物目标后直接驱动寻路即可。
        if (mob.target != null) mob.target = null
        try {
            mob.pathfinder.moveTo(center)
        } catch (ignored: Exception) {
            // 个别没有寻路能力的 Mob 保持清空攻击目标，避免转火玩家。
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTauntedMobSelectTarget(event: EntityTargetLivingEntityEvent) {
        if (event.target != null && activeTaunts.containsKey(event.entity.uniqueId)) {
            // 在灵草存活期间阻止初始名单中的怪物被自身 AI 或其他普通仇恨覆盖。
            event.isCancelled = true
        }
    }

    private fun drawTauntRadius(center: Location, radius: Double) {
        if (radius <= 0.0) return
        val particle = Particle.DustOptions(org.bukkit.Color.fromRGB(105, 225, 95), 1.0f)
        val points = 72
        for (index in 0 until points) {
            val angle = Math.PI * 2.0 * index / points
            val point = center.clone().add(
                Math.cos(angle) * radius,
                0.15,
                Math.sin(angle) * radius
            )
            center.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, particle)
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
