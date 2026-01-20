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
                // 1. 解析结果物品 (核心修改)
                val resultStr = config.getString("$id.result_id")
                val resultItem: ItemStack

                // 尝试从 RPG 库加载
                // 注意：Kotlin 中 Map 的 containsKey 语法没变，但访问属性更加直接
                resultItem = if (resultStr != null && plugin.playerManager.weaponManager.loadedWeapons.containsKey(resultStr)) {
                    plugin.playerManager.weaponManager.getItemStack(resultStr)!!
                } else if (resultStr != null && plugin.playerManager.armorManager.allIds.contains(resultStr)) {
                    plugin.playerManager.armorManager.getItemStack(resultStr)!!
                } else {
                    // 尝试原版材质
                    val mat = Material.getMaterial(resultStr ?: "STONE")
                    ItemStack(mat ?: Material.STONE)
                }

                // 2. 解析材料 List (核心修改)
                val ingredients: MutableList<ItemStack> = ArrayList()
                val ingList = config.getStringList("$id.ingredients")
                for (ingStr in ingList) {
                    val ingItem: ItemStack = if (ingStr.equals("AIR", ignoreCase = true)) {
                        ItemStack(Material.AIR)
                    } else if (plugin.playerManager.weaponManager.loadedWeapons.containsKey(ingStr)) {
                        plugin.playerManager.weaponManager.getItemStack(ingStr)!!
                    } else if (plugin.playerManager.armorManager.allIds.contains(ingStr)) {
                        plugin.playerManager.armorManager.getItemStack(ingStr)!!
                    } else {
                        val mat = Material.getMaterial(ingStr)
                        ItemStack(mat ?: Material.STONE)
                    }
                    ingredients.add(ingItem)
                }

                // 3. 基础数值
                val job = config.getInt("$id.req_job", -1)
                val lv = config.getInt("$id.req_level", 1)
                val lic = config.getInt("$id.req_license", 0)
                val exp = config.getInt("$id.exp_reward", 10)

                val recipe = DzRecipe(id, category, resultItem, ingredients, job, lv, lic, exp)
                // computeIfAbsent 的 Kotlin 写法
                recipes.computeIfAbsent(category) { HashMap() }[id] = recipe

            } catch (e: Exception) {
                plugin.logger.warning("加载配方 $id 失败: ${e.message}")
            }
        }
    }

    fun saveRecipe(recipe: DzRecipe) {
        val file = File(plugin.dataFolder, "recipes/${recipe.category}.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        val path = recipe.id

        // 核心：保存 ID 而不是 ItemStack
        config.set("$path.result_id", ItemUtil.getPublicId(recipe.result))

        val ingIds: MutableList<String> = ArrayList()
        for (item in recipe.ingredients) {
            ingIds.add(ItemUtil.getPublicId(item))
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