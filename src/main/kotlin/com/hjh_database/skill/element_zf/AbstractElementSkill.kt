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

        // ==========================================
        // 2. 法宝不消耗元素，普通元素仍按原规则扣除一枚。
        // ==========================================
        var willConsumeItem = true // 默认扣除物品

        if (isFabao(handItem)) {
            willConsumeItem = false // 【修复】如果是法宝，绝对不扣除物品！
        }

        // 3. 执行物品扣除；补元在阵法成功释放后统一处理。
        if (willConsumeItem) {
            if (handItem.type.isAir || handItem.amount <= 0) return false
            plugin.elementZfManager.recordConsumedElement(player, handItem)
            handItem.amount = handItem.amount - 1
        }

        return onCast(player, level, safeConfig, path)
    }
}
