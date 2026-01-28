package com.hjh_database.npc.manager

import com.destroystokyo.paper.entity.ai.VanillaGoal
import com.hjh_database.Hjh_database
import com.hjh_database.npc.data.CustomTrade
import com.hjh_database.npc.data.NpcInstance
import com.hjh_database.npc.data.NpcTemplate
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class NpcManager(private val plugin: Hjh_database) {

    val templates = HashMap<String, NpcTemplate>()
    val instances = HashMap<UUID, NpcInstance>()

    val npcKey = NamespacedKey(plugin, "hjh_npc_id")

    // 文件夹路径
    private val templatesFolder = File(plugin.dataFolder, "npc/templates")
    private val instancesFile = File(plugin.dataFolder, "npc/instances.yml")

    init {
        // 确保文件夹存在
        if (!templatesFolder.exists()) templatesFolder.mkdirs()
        loadData()
    }

    /**
     * 加载数据 (重载时调用此方法，不会保存)
     */
    fun loadData() {
        // 1. 清空内存
        templates.clear()
        instances.clear()

        // 2. 加载所有模板文件 (多文件模式)
        val files = templatesFolder.listFiles { _, name -> name.endsWith(".yml") }
        if (files != null) {
            for (file in files) {
                try {
                    val config = YamlConfiguration.loadConfiguration(file)
                    // 假设根节点就是 id (或者是文件名里的id，但为了稳健，我们在文件内部也存了id)
                    // 现在的结构建议：文件名任意，文件内容根节点是 templateID
                    for (key in config.getKeys(false)) {
                        val sec = config.getConfigurationSection(key) ?: continue

                        val name = sec.getString("name", "NPC")!!.replace("&", "§")
                        val profStr = sec.getString("profession", "minecraft:none")!!
                        val typeStr = sec.getString("type", "minecraft:plains")!!

                        val profession = Registry.VILLAGER_PROFESSION.get(parseKey(profStr)) ?: Villager.Profession.NONE
                        val type = Registry.VILLAGER_TYPE.get(parseKey(typeStr)) ?: Villager.Type.PLAINS

                        val dialogue = sec.getStringList("dialogue")

                        val tradeList = ArrayList<CustomTrade>()
                        val tradesSec = sec.getConfigurationSection("trades")
                        if (tradesSec != null) {
                            for (i in tradesSec.getKeys(false)) {
                                val tSec = tradesSec.getConfigurationSection(i) ?: continue
                                val r = tSec.getItemStack("result") ?: continue
                                val i1 = tSec.getItemStack("input1") ?: continue
                                val i2 = tSec.getItemStack("input2")
                                tradeList.add(CustomTrade(r, i1, i2))
                            }
                        }
                        templates[key] = NpcTemplate(key, name, profession, type, dialogue, tradeList)
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("加载 NPC 模板文件失败: ${file.name}")
                    e.printStackTrace()
                }
            }
        }

        // 3. 加载实例
        if (instancesFile.exists()) {
            val config = YamlConfiguration.loadConfiguration(instancesFile)
            for (key in config.getKeys(false)) {
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                val tid = config.getString("$key.template") ?: continue
                val loc = config.getLocation("$key.location") ?: continue
                instances[uuid] = NpcInstance(uuid, tid, loc)
            }
        }
        plugin.logger.info("已加载 ${templates.size} 个NPC模板 (来自文件夹) 和 ${instances.size} 个NPC实例。")
    }

    /**
     * 保存数据 (分散保存到多个文件)
     * 修复：保存前会自动清理同ID的旧文件名，防止文件堆积
     */
    fun saveData() {
        // === 1. 保存模板：每个模板一个文件 ===
        for (t in templates.values) {
            // A. 计算新的文件名
            // 去除颜色代码和非法字符，生成干净的文件名
            val cleanName = t.name.replace("§", "").replace("&", "").replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "")
            // 文件名格式: ID_名字.yml
            val newFileName = "${t.id}_$cleanName.yml"
            val newFile = File(templatesFolder, newFileName)

            // B. 【核心修复】清理该ID对应的旧文件
            // 扫描文件夹，找到所有以 "ID_" 开头，但文件名不是 newFileName 的文件，并删除
            val oldFiles = templatesFolder.listFiles { _, name ->
                // 逻辑：匹配前缀(ID_) + 是yml文件 + 不是当前要保存的这个新文件
                name.startsWith("${t.id}_") && name.endsWith(".yml") && name != newFileName
            }

            // 执行删除操作
            oldFiles?.forEach { oldFile ->
                // 可选：打印日志方便调试
                // plugin.logger.info("清理旧NPC文件: ${oldFile.name}")
                oldFile.delete()
            }

            // C. 写入新数据
            val tConfig = YamlConfiguration()
            val path = t.id // 根节点依然使用 ID

            tConfig.set("$path.name", t.name) // 保存带颜色的名字
            tConfig.set("$path.profession", t.profession.key.toString())
            tConfig.set("$path.type", t.type.key.toString())
            tConfig.set("$path.dialogue", t.dialogue)

            for ((index, trade) in t.trades.withIndex()) {
                tConfig.set("$path.trades.$index.result", trade.result)
                tConfig.set("$path.trades.$index.input1", trade.ingredient1)
                tConfig.set("$path.trades.$index.input2", trade.ingredient2)
            }

            try {
                tConfig.save(newFile)
            } catch (e: Exception) {
                plugin.logger.severe("无法保存 NPC 模板: ${t.id}")
                e.printStackTrace()
            }
        }

        // === 2. 保存实例 (保持单文件) ===
        val iConfig = YamlConfiguration()
        for (inst in instances.values) {
            iConfig.set("${inst.uuid}.template", inst.templateId)
            iConfig.set("${inst.uuid}.location", inst.location)
        }
        try {
            iConfig.save(instancesFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 删除模板及其文件
    fun deleteTemplate(id: String): Boolean {
        val template = templates[id] ?: return false
        templates.remove(id)

        // 尝试删除对应的文件
        // 由于文件名包含动态名字，我们遍历文件夹查找包含 ID 的文件
        val files = templatesFolder.listFiles { _, name -> name.startsWith(id) && name.endsWith(".yml") }
        files?.forEach { it.delete() }

        return true
    }

    // ... (以下方法保持不变：parseKey, getTemplate, openTrade, applyNpcAttributes, spawnNpc, stripAiGoals, removeNpc, convertVanillaRecipes) ...
    // 为了完整性，请确保保留你之前代码中的 convertVanillaRecipes 等方法
    // 这里简单列出 convertVanillaRecipes 防止遗漏

    fun convertVanillaRecipes(recipes: List<MerchantRecipe>): ArrayList<CustomTrade> {
        val list = ArrayList<CustomTrade>()
        for (recipe in recipes) {
            val result = recipe.result
            val ingredients = recipe.ingredients
            if (ingredients.isNotEmpty()) {
                val input1 = ingredients[0]
                val input2 = if (ingredients.size > 1) ingredients[1] else null
                list.add(CustomTrade(result, input1, input2))
            }
        }
        return list
    }

    private fun parseKey(input: String): NamespacedKey {
        return if (input.contains(":")) {
            val split = input.split(":")
            NamespacedKey(split[0], split[1])
        } else {
            NamespacedKey.minecraft(input.lowercase())
        }
    }

    fun getTemplate(id: String): NpcTemplate? = templates[id]

    fun openTrade(player: Player, templateId: String) {
        val template = templates[templateId] ?: return
        val merchant = Bukkit.createMerchant(template.name)
        val recipes = template.trades.map { it.toMerchantRecipe() }
        merchant.recipes = recipes
        player.openMerchant(merchant, true)
    }

    fun applyNpcAttributes(villager: Villager) {
        villager.removeWhenFarAway = false
        villager.isInvulnerable = true
        villager.isSilent = false
        villager.setAI(true)
        villager.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0
        villager.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        villager.isCollidable = false
        stripAiGoals(villager)
    }

    fun spawnNpc(location: Location, templateId: String): Villager? {
        val template = templates[templateId] ?: return null
        val world = location.world ?: return null
        val villager = world.spawn(location, Villager::class.java) { v ->
            v.profession = template.profession
            v.villagerType = template.type
            v.customName = template.name
            v.isCustomNameVisible = true
            v.persistentDataContainer.set(npcKey, PersistentDataType.STRING, templateId)
        }
        applyNpcAttributes(villager)
        instances[villager.uniqueId] = NpcInstance(villager.uniqueId, templateId, location)
        saveData()
        return villager
    }

    private fun stripAiGoals(mob: Mob) {
        val goals = Bukkit.getMobGoals()
        val allGoals = goals.getAllGoals(mob).toList()
        for (goal in allGoals) {
            if (goal.key != VanillaGoal.LOOK_AT_PLAYER &&
                goal.key != VanillaGoal.RANDOM_LOOK_AROUND) {
                goals.removeGoal(mob, goal)
            }
        }
    }

    fun removeNpc(uuid: UUID) {
        instances.remove(uuid)
        Bukkit.getEntity(uuid)?.remove()
        saveData()
    }
}