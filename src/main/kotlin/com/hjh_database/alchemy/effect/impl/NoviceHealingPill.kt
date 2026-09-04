package com.hjh_database.alchemy.effect.impl

import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.data.TierConfig
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.ArrayList

class NoviceHealingPill : AlchemyEffect {

    // 1. 将 id 与 yml 中的键名保持一致
    override val id = "NoviceHealingPill"

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id)
        recipe.displayName = "§a新手疗愈丹" // 这是配方在后台/GUI显示的名字
        // 这里不需要写 recipe.colorHex 和 sicknessTime 了，除非你的配方系统还单独需要它们

        // 2. 获取主插件实例（请将 "你的插件名" 替换为 plugin.yml 里的实际名字，比如 "Hjh_database"）
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Hjh_database") as com.hjh_database.Hjh_database

        // 3. 直接从 ResourceManager 获取在 yml 注册好的成品物品
        // "NoviceHealingPill" 就是你在 danyao.yml 里写的键名
        val resultItem = plugin.resourceManager.getItem("NoviceHealingPill")?.clone()
            ?: ItemStack(Material.POTION) // 如果没找到就给个普通水瓶兜底

        // 原材料 (这是作为默认配方生成的，你可以随便写，以后都在GUI里改)
        val ingredients = ArrayList<ItemStack>()
        ingredients.add(ItemStack(Material.WHEAT_SEEDS, 2))
        ingredients.add(ItemStack(Material.APPLE, 1))

        recipe.tierData[AlchemyTier.LOW] = TierConfig(ingredients, resultItem)
        return recipe
    }

    // 【已删除】 private fun hexToColor(hex: String) 方法，不需要了！

    // === 核心逻辑 (保持不变) ===

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        if (tier == AlchemyTier.LOW) {
            // 瞬间治疗逻辑
            player.addPotionEffect(PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 0))
        }
        // 返回 0 表示这是一个瞬间药剂，不需要持续 ticking
        return 0
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
        // 瞬间药剂不需要 tick
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
        // 结束时不需要处理
    }
}