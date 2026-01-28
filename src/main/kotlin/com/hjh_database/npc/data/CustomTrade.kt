package com.hjh_database.npc.data

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe

/**
 * 自定义交易项
 */
data class CustomTrade(
    var result: ItemStack,
    var ingredient1: ItemStack,
    var ingredient2: ItemStack? = null,
    var maxUses: Int = 9999,
    var experienceReward: Boolean = false
) {
    fun toMerchantRecipe(): MerchantRecipe {
        val recipe = MerchantRecipe(result, maxUses)
        recipe.addIngredient(ingredient1)

        // 【修复1】使用 let 安全调用，或者赋值给局部变量
        ingredient2?.let {
            recipe.addIngredient(it)
        }

        // 【修复2】直接调用 Setter 方法
        recipe.setExperienceReward(experienceReward)

        return recipe
    }
}