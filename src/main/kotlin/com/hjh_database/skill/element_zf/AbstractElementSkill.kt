package com.hjh_database.skill.element_zf

import com.hjh_database.Hjh_database
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

abstract class AbstractElementSkill(protected val plugin: Hjh_database) : ElementSkill {

    /**
     * 【核心改动】：这是新增的抽象方法！
     * 以后的金木水火土，不需要再写 cast 方法了，直接重写这个 onCast 写特效和伤害即可！
     */
    abstract fun onCast(player: Player, level: Int, safeConfig: ConfigurationSection, path: String): Boolean

    /**
     * 判断当前物品是否为“法宝”
     * 以后你可以根据你的 NBT 标签来判断它是不是法宝
     */
    open fun isFabao(item: ItemStack): Boolean {
        // TODO: 这里写你的法宝判断逻辑，例如：
        // val meta = item.itemMeta ?: return false
        // return meta.persistentDataContainer.has(NamespacedKey(plugin, "fabao_id"), PersistentDataType.STRING)
        return false // 暂时默认不是法宝
    }

    // 父类接管原本的 cast 方法
    override fun cast(player: Player, level: Int, config: ConfigurationSection?): Boolean {
        // 1. 读取配置
        val safeConfig = config!!
        var path = "levels.$level"
        if (!safeConfig.contains(path)) path = "levels.1"

        val handItem = player.inventory.itemInMainHand
        val data = plugin.playerManager.getData(player.uniqueId)!!

        // ==========================================
        // 2. 核心消耗判定（法宝与回流）
        // ==========================================
        var willConsumeItem = true // 默认扣除物品

        if (isFabao(handItem)) {
            willConsumeItem = false // 【修复】如果是法宝，绝对不扣除物品！
        } else {
            // 如果不是法宝，判断是否触发了回流仪
            val refluxInfo = plugin.accessorySkillManager.getActiveRefluxData(player)
            if (refluxInfo != null) {
                val refluxSkill = refluxInfo.first
                val crystalData = refluxInfo.second

                val threshold = refluxSkill.getThresholdPercent(crystalData)
                val costPerLv = refluxSkill.getCostPerLevel(crystalData)
                val prob = refluxSkill.getTriggerProbability(crystalData)

                val actualLingliCost = level * costPerLv

                // 判断灵力是否足够，并且通过概率判定
                if (data.lingli > (data.maxLingli * threshold) && data.lingli >= actualLingliCost) {
                    if (Math.random() <= prob) {
                        data.lingli -= actualLingliCost // 扣除灵力
                        plugin.databaseManager.queuePlayerSave(data)
                        val accessoryId = crystalData.skillId ?: crystalData.id
                        if (!plugin.passiveSubtitleManager.showAccessoryTrigger(player, accessoryId)) {
                            player.sendMessage("§d✨ 【回流】触发成功！本次阵法消耗了 $actualLingliCost 点灵力。")
                        }
                        willConsumeItem = false // 触发了回流，免去物品消耗
                    }
                }
            }
        }

        // 3. 执行物品扣除 (如果既不是法宝，又没触发回流)
        if (willConsumeItem) {
            handItem.amount = handItem.amount - 1
        }

        return onCast(player, level, safeConfig, path)
    }
}
