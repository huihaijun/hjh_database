package com.hjh_database.alchemy.manager

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.data.TierConfig
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.alchemy.process.AlchemySession
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AlchemyManager(private val plugin: Hjh_database) {

    // 缓存配方数据 ID -> Recipe
    val recipes = HashMap<String, AlchemyRecipe>()

    // 注册的效果 ID -> Effect Logic
    val effects = HashMap<String, AlchemyEffect>()

    // 正在炼药的会话 Player UUID -> Session
    val activeSessions = ConcurrentHashMap<UUID, AlchemySession>()

    private var tickTask: BukkitTask? = null
    private val recipeFile = File(plugin.dataFolder, "alchemy/recipes.yml")

    init {
        loadRecipes()
        startGlobalTick() // 启动心跳
    }

    // === 效果注册接口 ===
    fun registerEffect(effect: AlchemyEffect) {
        effects[effect.id] = effect

        // 获取代码默认配置
        val defaultRecipe = effect.getDefaultRecipe()

        // 1. 如果内存/文件中完全没有这个配方，直接使用代码默认值并保存
        if (!recipes.containsKey(effect.id)) {
//            recipes[effect.id] = defaultRecipe
//            saveRecipes() // 立即保存默认配置到文件
//            plugin.logger.info("§a[丹药] 载入并保存默认配置: ${effect.id}")
        } else {
            // 2. 如果文件里有配置，检查是否缺失某些品质，只做增量补充，不覆盖已有修改
            val existing = recipes[effect.id]!!
            var updated = false

            for ((tier, config) in defaultRecipe.tierData) {
                if (!existing.tierData.containsKey(tier)) {
                    existing.tierData[tier] = config
                    updated = true
                    plugin.logger.info("§e[丹药] 为 ${effect.id} 补充默认 $tier 配置")
                }
            }
            if (updated) saveRecipes()
        }
    }

    fun getEffect(id: String): AlchemyEffect? = effects[id]

    // === 配方存取 (修复重点：实现真正的保存和读取) ===
    fun loadRecipes() {
        if (!recipeFile.exists()) return
        val config = YamlConfiguration.loadConfiguration(recipeFile)
        val section = config.getConfigurationSection("recipes") ?: return

        for (id in section.getKeys(false)) {
            val path = "recipes.$id"
            val recipe = AlchemyRecipe(id)

            // 读取基础属性
            recipe.displayName = config.getString("$path.displayName", id) ?: id
            // 【在这里添加读取药毒时间的逻辑】
            recipe.sicknessTime = config.getInt("$path.sickness_time", 0)

            // 读取各品质配置
            val tiersSec = config.getConfigurationSection("$path.tiers")
            if (tiersSec != null) {
                for (tierName in tiersSec.getKeys(false)) {
                    val tier = try { AlchemyTier.valueOf(tierName) } catch (e: Exception) { continue }
                    val tPath = "$path.tiers.$tierName"

                    val ingredients = config.getList("$tPath.ingredients") as? List<ItemStack> ?: ArrayList()
                    val result = config.getItemStack("$tPath.result")

                    if (result != null) {
                        recipe.tierData[tier] = TierConfig(ArrayList(ingredients), result)
                    }
                }
            }
            recipes[id] = recipe
        }
    }

    fun saveRecipes() {
        val config = YamlConfiguration.loadConfiguration(recipeFile)
        config.set("recipes", null) // 清空旧数据，防止残留

        for ((id, recipe) in recipes) {
            val path = "recipes.$id"
            // 保存基础属性
            // ======== 替换为 ========
            config.set("$path.displayName", recipe.displayName)
            // 【在这里添加保存药毒时间的逻辑】
            config.set("$path.sickness_time", recipe.sicknessTime)
            // 保存各品质配置
            for ((tier, data) in recipe.tierData) {
                val tPath = "$path.tiers.${tier.name}"
                config.set("$tPath.ingredients", data.ingredients)
                config.set("$tPath.result", data.result)
            }
        }
        config.save(recipeFile)
    }

    /**
     * 创建一个成品丹药物品（附带完整的 NBT 数据）
     */
    fun createPillItem(id: String, tier: AlchemyTier): ItemStack? {
        val recipe = recipes[id] ?: return null
        val tierConfig = recipe.tierData[tier]
        if (tierConfig == null) return null

        val item = tierConfig.result.clone()
        val meta = item.itemMeta ?: return item

        // 确保生成的丹药隐藏原版文本 & 堆叠限制
        try {
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES)
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP) // 1.20.5+
        } catch (e: Error) { /* 忽略版本差异 */ }

        // 写入 NBT 数据
        val idKey = NamespacedKey(plugin, "hjh_alchemy_id")
        val tierKey = NamespacedKey(plugin, "hjh_alchemy_tier")

        meta.persistentDataContainer.set(idKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(tierKey, PersistentDataType.STRING, tier.name)

        item.itemMeta = meta
        return item
    }

    // === 会话管理 ===
    fun startSession(player: Player, cauldronLoc: Location, recipe: AlchemyRecipe, tier: AlchemyTier) {
        if (activeSessions.containsKey(player.uniqueId)) return

        val session = AlchemySession(plugin, player, cauldronLoc, recipe, tier)
        activeSessions[player.uniqueId] = session
        session.start()
    }

    fun stopSession(player: Player) {
        activeSessions[player.uniqueId]?.cancel()
        activeSessions.remove(player.uniqueId)
    }

    private fun startGlobalTick() {
        tickTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in plugin.server.onlinePlayers) {
                val data = plugin.playerManager.getPlayerData(player)
                data?.tickAlchemyEffects(plugin, player)
            }
        }, 20L, 20L)
    }

    fun isAlchemyItem(item: ItemStack?): Boolean {
        if (item == null || item.type == org.bukkit.Material.AIR) return false
        val meta = item.itemMeta ?: return false
        val key = NamespacedKey(plugin, "hjh_alchemy_id")
        return meta.persistentDataContainer.has(key, PersistentDataType.STRING)
    }

    fun shutdown() {
        tickTask?.cancel()
    }
}
