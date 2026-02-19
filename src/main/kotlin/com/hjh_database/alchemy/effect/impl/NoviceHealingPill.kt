package com.hjh_database.alchemy.effect.impl

import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.data.TierConfig
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.data.PlayerData
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.ArrayList

class NoviceHealingPill : AlchemyEffect {

    override val id = "novice_healing"

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id)
        recipe.displayName = "§a新手疗愈丹" // 改个名字应景
        recipe.sicknessTime = 3
        recipe.colorHex = "#FF5555" // 这个颜色现在会作用于药水颜色
        recipe.requiredLevel = 0
        recipe.onlyDoctor = false

        // === 关键修改：生成药水物品 ===
        val resultItem = ItemStack(Material.POTION)
        val meta = resultItem.itemMeta as PotionMeta

        meta.setDisplayName("§a新手疗愈丹")

        // 【新增】设置最大堆叠数量为 99
        try {
            meta.setMaxStackSize(99)
        } catch (e: NoSuchMethodError) {
            // 防止低版本报错
        }

        // 设置药水颜色 (解析 Hex)
        val color = hexToColor(recipe.colorHex)
        meta.color = color

        // 隐藏原版药水效果提示 (让它看起来像自定义物品)
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP)

        val lore = ArrayList<String>()
        lore.add("§7§o最基础的治愈丹药")
        lore.add("§7§o能帮助新手快速简单的愈合伤势")
        lore.add("§8===========§2§l丹药效果§8============")
        lore.add("§e[右键] §f药丹疾病：§b3§f秒")
        lore.add("§f瞬间恢复自身§b4§f点生命值")
        meta.lore = lore
        resultItem.itemMeta = meta

        // 原材料
        val ingredients = ArrayList<ItemStack>()
        ingredients.add(ItemStack(Material.WHEAT_SEEDS, 2))
        ingredients.add(ItemStack(Material.APPLE, 1))

        recipe.tierData[AlchemyTier.LOW] = TierConfig(ingredients, resultItem)
        return recipe
    }

    // 辅助：Hex 转 Bukkit Color
    private fun hexToColor(hex: String): Color {
        try {
            val cleanHex = hex.replace("#", "")
            val rgb = cleanHex.toInt(16)
            return Color.fromRGB(rgb)
        } catch (e: Exception) {
            return Color.GREEN // 默认兜底
        }
    }

    // === 核心逻辑 ===

    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        if (tier == AlchemyTier.LOW) {
            // 瞬间治疗逻辑
            player.addPotionEffect(PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 0))
        }
        // 返回 0 表示这是一个瞬间药剂，不需要持续 ticking
        // 如果你返回 10，那么 onTick 就会被执行 10 次
        return 0
    }

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
        // 如果这是一个持续回血药，可以在这里写逻辑，例如：
        // if (remainingSeconds % 5 == 0L) player.heal(1.0)
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
        // 持续时间结束
        // player.sendMessage("药效结束了")
    }
}