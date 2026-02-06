package com.hjh_database.race.impl

import com.hjh_database.race.RaceBase
import com.hjh_database.race.RaceManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.inventory.MerchantRecipe
import kotlin.math.floor

class HumanRace(manager: RaceManager) : RaceBase(manager) {

    override val raceId: Int = 2
    override val requiredQuestId: String = "main_ren_5"

    private val minFoodLevel = 6
    private val discountPercentage = 0.20 // 20% 折扣
    private val moneyItemIds = setOf("hjh_tongqian") // 定义哪些物品ID算钱

    // 被动技能 1: 饱食度锁定 (保持不变)
    @EventHandler
    fun onFoodChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (!isRaceActive(player)) return
        if (event.foodLevel < minFoodLevel) {
            event.foodLevel = minFoodLevel
        }
    }

    /**
     * 对外接口：处理配方列表，应用人族折扣
     * 该方法会在打开商店前被调用
     */
    fun applyDiscounts(recipes: MutableList<MerchantRecipe>) {
        for (recipe in recipes) {
            val ingredient = recipe.ingredients.firstOrNull() ?: continue

            // 获取原料的 Resource ID
            val resourceId = manager.getResourceId(ingredient)

            // 如果原料是定义的货币
            if (resourceId != null && moneyItemIds.contains(resourceId)) {
                val originalCost = ingredient.amount

                // 计算折扣量 (向下取整)
                // 例如: 原价 10, 20% off -> 优惠 2 个 -> 实付 8 个
                val discountAmount = floor(originalCost * discountPercentage).toInt()

                if (discountAmount > 0) {
                    // 原版机制：设置负数代表减少的价格
                    // 界面上会显示: [原价划线] -> [新价格]
                    recipe.specialPrice = -discountAmount
                }
            }
        }
    }

    override fun castActiveSkill(player: Player) {
        player.sendMessage("§e[人族] §f你举起了人族证明，感到一股浩然正气... (主动技能开发中)")
    }
}