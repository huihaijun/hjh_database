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

class Lianxindan : AlchemyEffect {

    // 1. 将 id 与 yml 中的键名保持一致
    override val id = "lianxindan"

    override fun getDefaultRecipe(): AlchemyRecipe {
        val recipe = AlchemyRecipe(id)
        recipe.displayName = "§c莲心丹" // 这是配方在后台/GUI显示的名字

        // 2. 获取主插件实例
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Hjh_database") as com.hjh_database.Hjh_database

        // 3. 直接从 ResourceManager 获取在 yml 注册好的成品物品
        val resultItem = plugin.resourceManager.getItem("lianxindan")?.clone()
            ?: ItemStack(Material.POTION) // 兜底物品

        // 原材料 (默认配方：放点地狱疣和岩浆膏，符合抗火的主题，后续也可在GUI改)
        val ingredients = ArrayList<ItemStack>()
        ingredients.add(ItemStack(Material.NETHER_WART, 2))
        ingredients.add(ItemStack(Material.MAGMA_CREAM, 1))

        recipe.tierData[AlchemyTier.LOW] = TierConfig(ingredients, resultItem)
        return recipe
    }

    // === 核心逻辑 ===
    override fun onConsume(player: Player, data: PlayerData, tier: AlchemyTier): Long {
        if (tier == AlchemyTier.LOW) {
            // 给玩家添加抗火效果 (Fire Resistance)
            // 参数: (药水类型, 持续时间ticks, 等级amplifier)
            // 15秒 = 15 * 20 = 300 ticks，等级 0 代表抗火I
            player.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 15 * 20, 0))
        }
        // 2. 动态读取配置
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Hjh_database") as com.hjh_database.Hjh_database

        // 从你的配方管理器中找到当前这个丹药(id = "lianxindan")的配方数据
        val recipe = plugin.alchemyManager.recipes[id]

        // 如果找到了配方，就返回配方里记录的时间；如果没找到（比如配置删了），则返回 3L 保底
        // 注意：这取决于你的 AlchemyRecipe 类里是否有 sicknessTime 这个变量
        return recipe?.sicknessTime?.toLong() ?: 3L
    }
    // === 补上缺失的两个接口方法 ===

    override fun onTick(player: Player, data: PlayerData, tier: AlchemyTier, remainingSeconds: Long) {
        // 莲心丹是直接给原版抗火效果，不需要每秒额外执行自定义逻辑，所以这里直接留空即可
    }

    override fun onExpire(player: Player, data: PlayerData, tier: AlchemyTier) {
        // 药效结束时不需要做特殊处理（原版的抗火会自动消失），所以这里也留空即可
    }
}