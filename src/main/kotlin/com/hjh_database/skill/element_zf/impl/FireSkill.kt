package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.ElementSkill
import org.bukkit.*
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class FireSkill(private val plugin: Hjh_database) : ElementSkill {

    override fun cast(player: Player, level: Int, config: ConfigurationSection?): Boolean {
        // 1. 读取配置
        // 【关键点】显式断言 config 非空
        val safeConfig = config!!

        var path = "levels.$level"
        if (!safeConfig.contains(path)) path = "levels.1"

        val damagePercent = safeConfig.getDouble("$path.damage_percent", 3.0)
        val range = safeConfig.getDouble("$path.range", 10.0)
        val searchRadius = safeConfig.getDouble("$path.search_radius", 4.0)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        // 【新增】2. 消耗物品
        val handItem = player.inventory.itemInMainHand
        handItem.amount = handItem.amount - 1

        // 3. 获取数据
        // 【关键点】显式使用 !! 断言，将 PlayerData? 转为 PlayerData，防止 Nullable receiver 报错
        val data = plugin.playerManager.getData(player.uniqueId)!!

        // 【新增】4. 增加灵力
        if (lingliAdd > 0) {
            val currentLingli = data.lingli
            val maxLingli = data.maxLingli
            if (currentLingli < maxLingli) {
                data.lingli = min(maxLingli, currentLingli + lingliAdd)
                plugin.databaseManager.savePlayer(data)
            }
        }

        // 5. 索敌
        val target = getTarget(player, range, searchRadius)

        // 【修改】只在有目标时造成伤害
        if (target != null) {
            val baseDamage = data.zfStr
            val finalDamage = baseDamage * damagePercent

            // 法术伤害
            target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
            target.damage(finalDamage, player)
            target.removeMetadata("hjh_magic_damage", plugin)

            // 只有打中人才播放特效
            playBurnEffect(target)
        }

        // 6. 提示
//        player.sendMessage("§c§l[流火] §f阵法释放成功！");

        return true // 总是进入冷却
    }

    /**
     * 获取目标：
     * 1. 射线直接命中怪物 -> 返回该怪物
     * 2. 射线命中方块 -> 搜索方块周围 searchRadius 内最近的怪物
     * 3. 射线射空 -> 搜索终点周围 searchRadius 内最近的怪物
     */
    private fun getTarget(player: Player, range: Double, searchRadius: Double): LivingEntity? {
        val eye = player.eyeLocation
        val direction = eye.direction

        // 射线检测
        val result = player.world.rayTrace(
            eye, direction, range,
            FluidCollisionMode.NEVER, true, 0.5
        ) { entity ->
            entity != player &&
                    entity.scoreboardTags.contains("panling") &&
                    entity.scoreboardTags.contains("monster")
        }

        val hitLocation: Location

        // 情况A: 直接指到了怪物
        if (result != null && result.hitEntity is LivingEntity) {
            return result.hitEntity as LivingEntity
        }
        // 情况B: 指到了方块
        else if (result != null && result.hitBlock != null) {
            hitLocation = result.hitPosition.toLocation(player.world)
        }
        // 情况C: 射向天空/远处，取最大射程终点
        else {
            hitLocation = eye.clone().add(direction.multiply(range))
        }

        // 在撞击点周围寻找最近的有效目标
        // hitLocation.world 可能为空，但在 Bukkit 运行时通常安全，这里使用 !! 保证调用
        val nearby = hitLocation.world!!.getNearbyEntities(hitLocation, searchRadius, searchRadius, searchRadius)

        return nearby.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != player }
            .filter { e ->
                e.scoreboardTags.contains("panling") && e.scoreboardTags.contains("monster")
            }
            // 按距离撞击点的远近排序，选最近的一个
            .minByOrNull { e -> e.location.distance(hitLocation) }
    }

    /**
     * 播放灼烧特效：从脚底向上升起的火焰螺旋
     */
    private fun playBurnEffect(target: LivingEntity) {
        val loc = target.location
        val world = loc.world ?: return
        val height = target.height

        // 1. 脚底爆发一圈火焰
        world.spawnParticle(Particle.LAVA, loc, 10, 0.5, 0.1, 0.5, 0.1)
        world.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.8f)

        // 2. 螺旋上升的火焰粒子
        object : BukkitRunnable() {
            var y = 0.0
            var angle = 0.0

            override fun run() {
                // 每次上升一点
                if (y > height + 0.5) {
                    this.cancel()
                    // 顶部冒烟
                    world.spawnParticle(
                        Particle.SMOKE,
                        loc.clone().add(0.0, height, 0.0),
                        5,
                        0.2,
                        0.2,
                        0.2,
                        0.05
                    )
                    return
                }

                // 双螺旋
                for (i in 0 until 2) {
                    val rad = angle + (i * Math.PI) // 对称
                    val x = 0.6 * cos(rad)
                    val z = 0.6 * sin(rad)

                    val particleLoc = loc.clone().add(x, y, z)
                    world.spawnParticle(Particle.FLAME, particleLoc, 1, 0.0, 0.0, 0.0, 0.02)
                }

                y += 0.2
                angle += 0.5
            }
        }.runTaskTimer(plugin, 0L, 1L) // 快速上升
    }
}