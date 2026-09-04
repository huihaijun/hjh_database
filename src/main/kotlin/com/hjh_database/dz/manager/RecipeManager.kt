package com.hjh_database.dz.manager

import com.hjh_database.Hjh_database
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.util.ItemUtil
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.HashMap

class RecipeManager(private val plugin: Hjh_database) {
    // 存储结构: 分类 -> (配方ID -> 配方对象)
    private val recipes: MutableMap<String, MutableMap<String, DzRecipe>> = HashMap()

    init {
        loadAllRecipes()
    }

    fun loadAllRecipes() {
        recipes.clear()
        val folder = File(plugin.dataFolder, "recipes")
        if (!folder.exists()) folder.mkdirs()

        val categories = arrayOf("weapon", "armor", "artifact", "misc")

        // 【核心修复】启动时强制创建4个空配方文件，确保首次保存也能成功
        for (cat in categories) {
            val file = File(folder, "$cat.yml")
            if (!file.exists()) {
                try {
                    val config = YamlConfiguration()
                    // 可选：加一行注释方便查看
                    config.set("info", "=== $cat 分类配方文件 ===")
                    config.save(file)
                    plugin.logger.info("已自动创建配方文件: recipes/$cat.yml")
                } catch (e: IOException) {
                    plugin.logger.warning("创建配方文件 $cat.yml 失败: ${e.message}")
                }
            }
        }

        for (cat in categories) {
            loadCategory(cat)
        }
        plugin.logger.info("锻造系统加载完成，共加载 ${getTotalRecipeCount()} 个配方。")
    }

    private fun loadCategory(category: String) {
        val file = File(plugin.dataFolder, "recipes/$category.yml")
        if (!file.exists()) return

        val config = YamlConfiguration.loadConfiguration(file)
        for (id in config.getKeys(false)) {
            try {
                // 1. 解析结果物品 (修复：支持解析数量，并增加 ResourceManager 检查)
                val resultRaw = config.getString("$id.result_id") ?: "STONE:1"
                val resParts = resultRaw.split(":")
                val resStr = resParts[0]
                val resAmount = if (resParts.size > 1) resParts[1].toIntOrNull() ?: 1 else 1

                val resultItem = resolveItem(resStr)
                resultItem.amount = resAmount

                // 2. 解析材料 List (修复：支持解析数量，并增加 ResourceManager 检查)
                val ingredients: MutableList<ItemStack> = ArrayList()
                val ingList = config.getStringList("$id.ingredients")
                for (ingRaw in ingList) {
                    val parts = ingRaw.split(":")
                    val ingStr = parts[0]
                    val ingAmount = if (parts.size > 1) parts[1].toIntOrNull() ?: 1 else 1

                    val ingItem = resolveItem(ingStr)

                    if (ingItem.type != Material.AIR) {
                        ingItem.amount = ingAmount
                    }
                    ingredients.add(ingItem)
                }

                // 3. 基础数值
                val job = config.getInt("$id.req_job", -1)
                val lv = config.getInt("$id.req_level", 1)
                val lic = config.getInt("$id.req_license", 0)
                val exp = config.getInt("$id.exp_reward", 10)

                val recipe = DzRecipe(id, category, resultItem, ingredients, job, lv, lic, exp)
                recipes.computeIfAbsent(category) { HashMap() }[id] = recipe

            } catch (e: Exception) {
                plugin.logger.warning("加载配方 $id 失败: ${e.message}")
            }
        }
    }

    /** 将配方中的公开 ID 还原为对应的自定义物品，最后才按原版材质解析。 */
    private fun resolveItem(id: String): ItemStack {
        if (id.equals("AIR", ignoreCase = true)) return ItemStack(Material.AIR)

        plugin.playerManager.weaponManager.getItemStack(id)?.let { return it }
        plugin.playerManager.armorManager.getItemStack(id)?.let { return it }
        plugin.artifactManager.getItem(id)?.let { return it }
        plugin.resourceManager.getItem(id)?.let { return it }

        return ItemStack(Material.matchMaterial(id) ?: Material.STONE)
    }

    fun saveRecipe(recipe: DzRecipe) {
        val file = File(plugin.dataFolder, "recipes/${recipe.category}.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        val path = recipe.id

        // 核心修改：保存时追加物品数量，格式为 ID:Amount
        val resId = ItemUtil.getPublicId(recipe.result)
        config.set("$path.result_id", "$resId:${recipe.result.amount}")

        val ingIds: MutableList<String> = ArrayList()
        for (item in recipe.ingredients) {
            if (item.type == Material.AIR) {
                ingIds.add("AIR:1")
            } else {
                val ingId = ItemUtil.getPublicId(item)
                ingIds.add("$ingId:${item.amount}")
            }
        }
        config.set("$path.ingredients", ingIds)

        config.set("$path.req_job", recipe.reqJob)
        config.set("$path.req_level", recipe.reqForgeLevel)
        config.set("$path.req_license", recipe.reqLicense)
        config.set("$path.exp_reward", recipe.expReward)

        try {
            config.save(file)
            recipes.computeIfAbsent(recipe.category) { HashMap() }[recipe.id] = recipe
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun deleteRecipe(category: String, id: String) {
        val file = File(plugin.dataFolder, "recipes/$category.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        config.set(id, null)
        try {
            config.save(file)
            if (recipes.containsKey(category)) {
                recipes[category]?.remove(id)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun getRecipesByCategory(category: String): List<DzRecipe> {
        if (!recipes.containsKey(category)) return ArrayList()
        // 将 MutableCollection 转换为 ArrayList 返回
        return ArrayList(recipes[category]?.values ?: emptyList())
    }

    fun getRecipe(category: String, id: String): DzRecipe? {
        if (!recipes.containsKey(category)) return null
        return recipes[category]?.get(id)
    }

    private fun getTotalRecipeCount(): Int {
        var count = 0
        for (map in recipes.values) {
            count += map.size
        }
        return count
    }
}
