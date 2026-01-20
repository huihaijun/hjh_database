package com.hjh_database.dz.data

import org.bukkit.inventory.ItemStack

/**
 * 锻造配方数据类
 */
class DzRecipe(
    val id: String,
    val category: String,
    val result: ItemStack,
    // 改动：不再是用 Map 存九宫格，而是用 List 存横向的5个材料 (或者更少)
    // 列表里的顺序对应 input_slots 的顺序
    val ingredients: List<ItemStack>,
    // 限制条件
    val reqJob: Int,
    val reqForgeLevel: Int,
    val reqLicense: Int,
    val expReward: Int
) {
    // Kotlin 中定义在构造函数里的 val 属性会自动生成 getter 方法。
    // Java 调用示例: recipe.getId(), recipe.getIngredients()
    // Kotlin 调用示例: recipe.id, recipe.ingredients
}