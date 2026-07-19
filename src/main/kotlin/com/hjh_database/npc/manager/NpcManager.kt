package com.hjh_database.npc.manager

import com.destroystokyo.paper.entity.ai.VanillaGoal
import com.hjh_database.Hjh_database
import com.hjh_database.npc.data.CustomTrade
import com.hjh_database.npc.data.NpcInstance
import com.hjh_database.npc.data.NpcTemplate
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Mob
import org.bukkit.entity.Villager
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class NpcManager(private val plugin: Hjh_database) {

    val templates = LinkedHashMap<String, NpcTemplate>()
    val instances = LinkedHashMap<UUID, NpcInstance>()

    val npcKey = NamespacedKey(plugin, "hjh_npc_id")

    private val npcFolder = File(plugin.dataFolder, "npc")
    private val npcsFile = File(npcFolder, "npcs.yml")
    private val libraryFile = File(npcFolder, "templates.yml")

    // 旧版数据源，仅用于首次迁移。迁移成功后保留原文件作为备份。
    private val legacyTemplatesFolder = File(npcFolder, "templates")
    private val legacyInstancesFile = File(npcFolder, "instances.yml")
    private val libraryTemplateIds = LinkedHashSet<String>()

    init {
        if (!npcFolder.exists()) npcFolder.mkdirs()
        loadData()
    }

    /** 从双 YAML 存储加载数据；首次运行时自动兼容旧版分散文件。 */
    @Synchronized
    fun loadData() {
        templates.clear()
        instances.clear()
        libraryTemplateIds.clear()

        val migrated = if (npcsFile.exists()) {
            loadNpcFile(npcsFile)
            false
        } else {
            loadLegacyData()
            true
        }

        // 只有新版 templates.yml 的 templates 根节点才代表石锄模板库。
        if (!migrated && libraryFile.exists()) {
            val config = YamlConfiguration.loadConfiguration(libraryFile)
            val section = config.getConfigurationSection("templates")
            if (section != null) {
                readTemplates(section, templates)
                libraryTemplateIds.addAll(section.getKeys(false))
            }
        }

        plugin.logger.info(
            "已加载 ${templates.size} 个 NPC 配置、${instances.size} 个 NPC 实例、" +
                "${libraryTemplateIds.size} 个石锄模板。"
        )

        if (migrated && (templates.isNotEmpty() || instances.isNotEmpty())) {
            saveData()
            plugin.logger.info("旧版 NPC 数据已迁移到 npcs.yml；石锄模板库已初始化为空。")
        }
    }

    private fun loadNpcFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)
        config.getConfigurationSection("templates")?.let { readTemplates(it, templates) }
        config.getConfigurationSection("instances")?.let(::readInstances)
    }

    private fun loadLegacyData() {
        // 更旧的单文件 templates.yml 先加载，较新的分散模板文件随后覆盖同 ID。
        if (libraryFile.exists()) {
            val legacyConfig = YamlConfiguration.loadConfiguration(libraryFile)
            readTemplates(legacyConfig, templates)
        }

        legacyTemplatesFolder.listFiles { file ->
            file.isFile && file.extension.equals("yml", ignoreCase = true)
        }?.sortedBy(File::getName)?.forEach { file ->
            try {
                readTemplates(YamlConfiguration.loadConfiguration(file), templates)
            } catch (exception: Exception) {
                plugin.logger.warning("加载旧版 NPC 文件失败: ${file.name} (${exception.message})")
            }
        }

        if (legacyInstancesFile.exists()) {
            readInstances(YamlConfiguration.loadConfiguration(legacyInstancesFile))
        }
    }

    private fun readTemplates(section: ConfigurationSection, destination: MutableMap<String, NpcTemplate>) {
        for (id in section.getKeys(false)) {
            val data = section.getConfigurationSection(id) ?: continue
            // 防止把新版的容器节点误当成旧版 NPC。
            if (!data.contains("name") && !data.contains("profession") && !data.contains("type")) continue

            val name = data.getString("name", "NPC")!!.replace("&", "§")
            val profession = Registry.VILLAGER_PROFESSION.get(
                parseKey(data.getString("profession", "minecraft:none")!!)
            ) ?: Villager.Profession.NONE
            val type = Registry.VILLAGER_TYPE.get(
                parseKey(data.getString("type", "minecraft:plains")!!)
            ) ?: Villager.Type.PLAINS

            val trades = ArrayList<CustomTrade>()
            data.getConfigurationSection("trades")?.let { tradeSection ->
                val keys = tradeSection.getKeys(false).sortedWith(
                    compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }
                )
                for (key in keys) {
                    val trade = tradeSection.getConfigurationSection(key) ?: continue
                    val result = trade.getItemStack("result") ?: continue
                    val input1 = trade.getItemStack("input1") ?: continue
                    trades.add(
                        CustomTrade(
                            result = result,
                            ingredient1 = input1,
                            ingredient2 = trade.getItemStack("input2"),
                            maxUses = trade.getInt("max_uses", 9999).coerceAtLeast(1),
                            experienceReward = trade.getBoolean("experience_reward", false)
                        )
                    )
                }
            }

            destination[id] = NpcTemplate(
                id = id,
                name = name,
                profession = profession,
                type = type,
                dialogue = data.getStringList("dialogue"),
                trades = trades,
                allowRaceDiscount = data.getBoolean("allow_race_discount", false)
            )
        }
    }

    private fun readInstances(section: ConfigurationSection) {
        for (key in section.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
            val templateId = section.getString("$key.template") ?: continue
            val location = section.getLocation("$key.location") ?: continue
            instances[uuid] = NpcInstance(uuid, templateId, location)
        }
    }

    /** 原子写入两个 YAML，避免关服或写盘中断留下半份配置。 */
    @Synchronized
    fun saveData() {
        val npcConfig = YamlConfiguration()
        for (template in templates.values.sortedBy { it.id }) {
            writeTemplate(npcConfig, "templates.${template.id}", template)
        }
        for (instance in instances.values.sortedBy { it.uuid.toString() }) {
            val path = "instances.${instance.uuid}"
            npcConfig.set("$path.template", instance.templateId)
            npcConfig.set("$path.location", instance.location)
        }

        val libraryConfig = YamlConfiguration()
        for (id in libraryTemplateIds.sorted()) {
            val template = templates[id] ?: continue
            writeTemplate(libraryConfig, "templates.$id", template)
        }

        try {
            saveAtomically(npcConfig, npcsFile)
            saveAtomically(libraryConfig, libraryFile)
        } catch (exception: Exception) {
            plugin.logger.severe("无法保存 NPC 数据: ${exception.message}")
            exception.printStackTrace()
        }
    }

    private fun writeTemplate(config: YamlConfiguration, path: String, template: NpcTemplate) {
        config.set("$path.name", template.name)
        config.set("$path.profession", template.profession.key.toString())
        config.set("$path.type", template.type.key.toString())
        config.set("$path.dialogue", template.dialogue)
        config.set("$path.allow_race_discount", template.allowRaceDiscount)
        for ((index, trade) in template.trades.withIndex()) {
            val tradePath = "$path.trades.$index"
            config.set("$tradePath.result", trade.result)
            config.set("$tradePath.input1", trade.ingredient1)
            config.set("$tradePath.input2", trade.ingredient2)
            config.set("$tradePath.max_uses", trade.maxUses)
            config.set("$tradePath.experience_reward", trade.experienceReward)
        }
    }

    private fun saveAtomically(config: YamlConfiguration, target: File) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        config.save(temporary)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun getTemplate(id: String): NpcTemplate? = templates[id]

    fun getLibraryTemplates(): List<NpcTemplate> = libraryTemplateIds
        .mapNotNull(templates::get)
        .sortedWith(compareBy<NpcTemplate> { stripColors(it.name) }.thenBy { it.id })

    fun saveTemplateToLibrary(id: String): Boolean {
        if (!templates.containsKey(id)) return false
        libraryTemplateIds.add(id)
        saveData()
        return true
    }

    fun removeTemplateFromLibrary(id: String): Boolean {
        if (!libraryTemplateIds.remove(id)) return false
        if (instances.values.none { it.templateId == id } && isGeneratedTemplate(id)) {
            templates.remove(id)
        }
        saveData()
        return true
    }

    fun isLibraryTemplate(id: String): Boolean = id in libraryTemplateIds

    /** 把普通村民或数据缺失的旧 NPC 收编为可编辑 NPC。 */
    fun adoptVillager(villager: Villager): String {
        val storedId = villager.persistentDataContainer.get(npcKey, PersistentDataType.STRING)
        if (storedId != null && templates.containsKey(storedId)) {
            if (!instances.containsKey(villager.uniqueId)) {
                instances[villager.uniqueId] = NpcInstance(villager.uniqueId, storedId, villager.location)
                saveData()
            }
            applyNpcAttributes(villager)
            return storedId
        }

        val id = "converted_${UUID.randomUUID().toString().take(8)}"
        val template = NpcTemplate(
            id = id,
            name = villager.customName ?: "§e新收编村民",
            profession = villager.profession,
            type = villager.villagerType,
            dialogue = mutableListOf("我被收编了！"),
            trades = convertVanillaRecipes(villager.recipes)
        )
        templates[id] = template
        villager.persistentDataContainer.set(npcKey, PersistentDataType.STRING, id)
        instances[villager.uniqueId] = NpcInstance(villager.uniqueId, id, villager.location)
        applyNpcAttributes(villager)
        saveData()
        return id
    }

    fun convertVanillaRecipes(recipes: List<MerchantRecipe>): ArrayList<CustomTrade> {
        val trades = ArrayList<CustomTrade>(recipes.size)
        for (recipe in recipes) {
            val ingredients = recipe.ingredients
            if (ingredients.isEmpty()) continue
            trades.add(
                CustomTrade(
                    result = recipe.result.clone(),
                    ingredient1 = ingredients[0].clone(),
                    ingredient2 = ingredients.getOrNull(1)?.clone(),
                    maxUses = recipe.maxUses.coerceAtLeast(1),
                    experienceReward = recipe.hasExperienceReward()
                )
            )
        }
        return trades
    }

    fun applyNpcAttributes(villager: Villager) {
        villager.removeWhenFarAway = false
        villager.isInvulnerable = true
        villager.isSilent = false
        villager.setAI(true)
        villager.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0
        villager.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        villager.isCollidable = false
        villager.isGliding = false
        stripAiGoals(villager)
    }

    fun refreshGlobalNpcs() {
        var count = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                val villager = entity as? Villager ?: continue
                if (!villager.persistentDataContainer.has(npcKey, PersistentDataType.STRING)) continue
                applyNpcAttributes(villager)
                count++
            }
        }
        plugin.logger.info("已重新应用属性到 $count 个在线 NPC 实例。")
    }

    fun refreshTemplateEntities(templateId: String): Int {
        val template = templates[templateId] ?: return 0
        var count = 0
        for (instance in instances.values) {
            if (instance.templateId != templateId) continue
            val villager = Bukkit.getEntity(instance.uuid) as? Villager ?: continue
            villager.customName = template.name
            villager.isCustomNameVisible = true
            villager.profession = template.profession
            villager.villagerType = template.type
            applyNpcAttributes(villager)
            count++
        }
        return count
    }

    fun spawnNpc(location: Location, templateId: String): Villager? {
        val template = templates[templateId] ?: return null
        val world = location.world ?: return null
        if (!location.chunk.isLoaded) location.chunk.load()
        val villager = world.spawn(location, Villager::class.java) { entity ->
            entity.profession = template.profession
            entity.villagerType = template.type
            entity.customName = template.name
            entity.isCustomNameVisible = true
            entity.persistentDataContainer.set(npcKey, PersistentDataType.STRING, templateId)
        }
        applyNpcAttributes(villager)
        instances[villager.uniqueId] = NpcInstance(villager.uniqueId, templateId, location.clone())
        saveData()
        return villager
    }

    fun removeInstancesByTemplate(templateId: String, targetLocation: Location? = null): Int {
        val oldInstances = instances.values.filter { it.templateId == templateId }.toList()
        val checkedChunks = HashSet<String>()
        val removedUuids = HashSet<UUID>()
        var removedCount = 0

        for (instance in oldInstances) {
            if (removeNpcEntity(instance.uuid, instance.location)) {
                removedCount++
                removedUuids.add(instance.uuid)
            }

            val chunk = loadChunk(instance.location)
            if (chunk != null && checkedChunks.add(chunkKey(chunk))) {
                removedCount += removeTemplateEntitiesInChunk(chunk, templateId, removedUuids)
            }
            instances.remove(instance.uuid)
        }

        val targetChunk = targetLocation?.let(::loadChunk)
        if (targetChunk != null && checkedChunks.add(chunkKey(targetChunk))) {
            removedCount += removeTemplateEntitiesInChunk(targetChunk, templateId, removedUuids)
        }

        if (oldInstances.isNotEmpty() || removedCount > 0) saveData()
        return removedCount
    }

    fun removeNpc(uuid: UUID) {
        val instance = instances.remove(uuid)
        removeNpcEntity(uuid, instance?.location)
        if (instance != null) {
            val id = instance.templateId
            val stillUsed = instances.values.any { it.templateId == id }
            if (!stillUsed && id !in libraryTemplateIds && isGeneratedTemplate(id)) {
                templates.remove(id)
            }
        }
        saveData()
    }

    private fun removeNpcEntity(uuid: UUID, location: Location? = null): Boolean {
        location?.let(::loadChunk)
        val entity = Bukkit.getEntity(uuid) ?: return false
        entity.remove()
        return true
    }

    private fun loadChunk(location: Location): Chunk? {
        val world = location.world ?: return null
        return world.getChunkAt(location).also { if (!it.isLoaded) it.load() }
    }

    private fun chunkKey(chunk: Chunk): String = "${chunk.world.uid}:${chunk.x}:${chunk.z}"

    private fun removeTemplateEntitiesInChunk(
        chunk: Chunk,
        templateId: String,
        removedUuids: MutableSet<UUID>
    ): Int {
        var count = 0
        for (entity in chunk.entities) {
            val villager = entity as? Villager ?: continue
            if (villager.uniqueId in removedUuids) continue
            val id = villager.persistentDataContainer.get(npcKey, PersistentDataType.STRING) ?: continue
            if (id != templateId) continue
            villager.remove()
            if (removedUuids.add(villager.uniqueId)) count++
        }
        return count
    }

    private fun stripAiGoals(mob: Mob) {
        val goals = Bukkit.getMobGoals()
        for (goal in goals.getAllGoals(mob).toList()) {
            if (goal.key != VanillaGoal.LOOK_AT_PLAYER && goal.key != VanillaGoal.RANDOM_LOOK_AROUND) {
                goals.removeGoal(mob, goal)
            }
        }
    }

    private fun parseKey(input: String): NamespacedKey {
        val parts = input.split(':', limit = 2)
        return if (parts.size == 2) NamespacedKey(parts[0], parts[1])
        else NamespacedKey.minecraft(input.lowercase())
    }

    private fun stripColors(input: String): String = input.replace(Regex("§[0-9A-FK-ORa-fk-or]"), "")

    private fun isGeneratedTemplate(id: String): Boolean = id.startsWith("npc_") || id.startsWith("converted_")
}
