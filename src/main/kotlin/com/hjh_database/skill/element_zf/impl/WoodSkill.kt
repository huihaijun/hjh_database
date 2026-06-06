package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.AbstractElementSkill
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import kotlin.math.ceil
import kotlin.math.min

// 【改动1】继承 AbstractElementSkill，去掉 private val
class WoodSkill(plugin: Hjh_database) : AbstractElementSkill(plugin) {

    // 【改动2】将 cast 改为 onCast
    override fun onCast(player: Player, level: Int, safeConfig: ConfigurationSection, path: String): Boolean {

        // 1. 读取配置 (父类已处理非空和路径检查)
        val damagePercent = safeConfig.getDouble("$path.damage_percent", 2.5)
        val healPercent = safeConfig.getDouble("$path.heal_percent", 0.2)
        val range = safeConfig.getDouble("$path.range", 5.0)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        // ！！！ 消耗物品逻辑已交由父类处理，此处彻底删除 ！！！

        // 2. 获取玩家数据并增加灵力 (空放也加)
        val data = plugin.playerManager.getData(player.uniqueId)!!

        if (lingliAdd > 0) {
            val currentLingli = data.lingli
            val maxLingli = data.maxLingli
            if (currentLingli < maxLingli) {
                data.lingli = min(maxLingli, currentLingli + lingliAdd)
                plugin.databaseManager.queuePlayerSave(data)
            }
        }

        // 3. 寻找目标
        val target = findTarget(player, range)

        // 4. 造成伤害与吸血
        // 如果有目标才造成伤害
        if (target != null) {
            // 计算伤害
            val baseDamage = data.zfStr
            val finalDamage = baseDamage * damagePercent
            // 法术伤害逻辑
            target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
            target.damage(finalDamage, player)
            target.removeMetadata("hjh_magic_damage", plugin)

            // 吸血逻辑
            val healAmount = ceil(finalDamage * healPercent)
            val currentHp = player.health
            // getAttribute 可能返回 null，必须使用 !! 断言
            val maxHp = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value
            if (currentHp < maxHp) {
                player.health = min(maxHp, currentHp + healAmount)
            }
            // 只有打中人才播放针对目标的特效
            playEffects(player, target)
        } else {
            // (可选) 如果空放，可以播放一个失败的音效或者只在脚下播点特效，这里暂不处理，保持安静
        }

        // 返回 true 代表释放成功，进入冷却
        return true
    }

    // ============================================
    // 下方的 findTarget 和 playEffects 完完全全保持你源码的原样！
    // ============================================

    /**
     * 寻找前方最近的有效目标
     */
    private fun findTarget(player: Player, range: Double): LivingEntity? {
        val entities = player.getNearbyEntities(range, range, range)
        val playerLoc = player.location
        val direction = playerLoc.direction

        // 过滤并排序
        val validTargets = entities.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != player } // 不是自己
            .filter { e ->
                // 核心要求：必须同时拥有 panling 和 monster 标签
                val tags = e.scoreboardTags
                tags.contains("panling") && tags.contains("monster")
            }
            .filter { e ->
                // 简单的视线判断 (点积 > 0 表示在前方 180度，> 0.5 表示前方 60度左右)
                val toEntity = e.location.toVector().subtract(playerLoc.toVector()).normalize()
                direction.dot(toEntity) > 0.5
            }
            .sortedBy { it.location.distance(playerLoc) } // 按距离排序
            .toList()

        return validTargets.firstOrNull()
    }

    private fun playEffects(player: Player, target: LivingEntity) {
        val world = player.world
        val pLoc = player.location.add(0.0, 1.0, 0.0)
        val tLoc = target.location.add(0.0, 1.0, 0.0)

        // 连线特效 (绿色粒子从怪物飞向玩家，表现"吸取")
        val dir = pLoc.toVector().subtract(tLoc.toVector())
        val dist = pLoc.distance(tLoc)
        val step = dir.normalize().multiply(0.5)

        var d = 0.0
        while (d < dist) {
            world.spawnParticle(
                Particle.HAPPY_VILLAGER,
                tLoc.clone().add(step.clone().multiply(d)),
                1, 0.0, 0.0, 0.0, 0.0
            )
            d += 0.5
        }

        // 声音
        player.playSound(player.location, Sound.ENTITY_WITCH_DRINK, 1.0f, 1.2f)
        world.playSound(tLoc, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.8f)

        // 目标身上爆绿光
        world.spawnParticle(Particle.COMPOSTER, tLoc, 20, 0.5, 1.0, 0.5, 0.1)
    }
}
