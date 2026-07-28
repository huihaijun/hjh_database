package com.hjh_database.baihu_dz

import com.google.common.collect.ArrayListMultimap
import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.equipment.activation.ActivatableEquipment
import com.hjh_database.equipment.activation.ActivationFailure
import com.hjh_database.equipment.activation.ActivationSpec
import com.hjh_database.dz.data.DzRecipe
import com.hjh_database.util.ItemUtil
import com.hjh_database.weapon.CrystalData
import com.hjh_database.weapon.WeaponManager
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

class BaihuDzManager(private val plugin: Hjh_database) {
    val stationKey = NamespacedKey(plugin, "baihu_dz_station")
    val weaponKey = NamespacedKey(plugin, "baihu_weapon_id")
    val artifactKey = NamespacedKey(plugin, "baihu_artifact_id")
    val durabilityKey = NamespacedKey(plugin, "baihu_durability")
    val maxDurabilityKey = NamespacedKey(plugin, "baihu_max_durability")

    private val resourceKey = NamespacedKey(plugin, "resource_id")
    private val legacyNormalWeaponKey = NamespacedKey(plugin, "weapon_id")
    private val normalCrystalKey = NamespacedKey(plugin, "crystal_id")
    private val rarityKey = NamespacedKey(plugin, "rarity")
    private val accessoryInvKey = NamespacedKey(plugin, "player_accessory_inv")

    val weapons: MutableMap<String, BaihuWeaponData> = linkedMapOf()
    val artifacts: MutableMap<String, BaihuArtifactData> = linkedMapOf()
    private val recipes: MutableMap<String, MutableMap<String, DzRecipe>> = linkedMapOf()

    init {
        ensureFiles()
        reload()
    }

    fun reload() {
        loadEquipment()
        loadRecipes()
    }

    private fun ensureFiles() {
        val base = File(plugin.dataFolder, "baihu_dz")
        val equipment = File(base, "equipment")
        val recipeFolder = File(base, "recipes")
        equipment.mkdirs()
        recipeFolder.mkdirs()
        copyIfMissing("baihu_dz/equipment/weapons.yml")
        copyIfMissing("baihu_dz/equipment/artifacts.yml")
        copyIfMissing("baihu_dz/recipes/weapon.yml")
        copyIfMissing("baihu_dz/recipes/artifact.yml")
        copyIfMissing("baihu_dz/recipes/material.yml")
    }

    private fun copyIfMissing(path: String) {
        val file = File(plugin.dataFolder, path)
        if (!file.exists()) {
            plugin.saveResource(path, false)
        }
    }

    private fun loadEquipment() {
        weapons.clear()
        artifacts.clear()

        val weaponConfig = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "baihu_dz/equipment/weapons.yml"))
        val weaponSec = weaponConfig.getConfigurationSection("weapons")
        weaponSec?.getKeys(false)?.forEach { id ->
            weaponSec.getConfigurationSection(id)?.let { weapons[id.lowercase(Locale.getDefault())] = BaihuWeaponData(id.lowercase(Locale.getDefault()), it) }
        }

        val artifactConfig = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "baihu_dz/equipment/artifacts.yml"))
        val artifactSec = artifactConfig.getConfigurationSection("artifacts")
        artifactSec?.getKeys(false)?.forEach { id ->
            artifactSec.getConfigurationSection(id)?.let { artifacts[id.lowercase(Locale.getDefault())] = BaihuArtifactData(id.lowercase(Locale.getDefault()), it) }
        }

        plugin.logger.info("虎瘴装加载完成：武器 ${weapons.size} 件，法宝 ${artifacts.size} 件。")
    }

    private fun loadRecipes() {
        recipes.clear()
        loadRecipeCategory("weapon")
        loadRecipeCategory("artifact")
        loadRecipeCategory("material")
    }

    private fun loadRecipeCategory(category: String) {
        val file = File(plugin.dataFolder, "baihu_dz/recipes/$category.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        val map = linkedMapOf<String, DzRecipe>()

        for (id in config.getKeys(false)) {
            val resultRaw = config.getString("$id.result_id") ?: continue
            val result = resolveStack(resultRaw) ?: continue
            val ingredients = config.getStringList("$id.ingredients").map { raw ->
                resolveStack(raw) ?: ItemStack(Material.AIR)
            }

            map[id] = DzRecipe(
                id = id,
                category = category,
                result = result,
                ingredients = ingredients,
                reqJob = config.getInt("$id.req_job", -1),
                reqForgeLevel = config.getInt("$id.req_level", 1),
                reqLicense = config.getInt("$id.req_license", 0),
                expReward = config.getInt("$id.exp_reward", 0)
            )
        }
        recipes[category] = map
    }

    private fun resolveStack(raw: String): ItemStack? {
        val parts = raw.split(":")
        val id = parts.getOrNull(0)?.trim()?.lowercase(Locale.getDefault()) ?: return null
        val amount = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val item = when {
            id.equals("air", true) -> ItemStack(Material.AIR)
            weapons.containsKey(id) -> buildWeaponItem(id)
            artifacts.containsKey(id) -> buildArtifactItem(id)
            plugin.playerManager.weaponManager.allIds.contains(id) -> plugin.playerManager.weaponManager.getItemStack(id)
            plugin.playerManager.armorManager.allIds.contains(id) -> plugin.playerManager.armorManager.getItemStack(id)
            plugin.playerManager.crystalManager.allIds.contains(id) -> plugin.playerManager.crystalManager.buildItem(id)
            plugin.resourceManager.getItem(id) != null -> plugin.resourceManager.getItem(id)
            else -> Material.matchMaterial(id.uppercase(Locale.getDefault()))?.let { ItemStack(it) }
        } ?: return null
        if (item.type != Material.AIR) item.amount = amount
        return item
    }

    fun getRecipesByCategory(category: String): List<DzRecipe> {
        if (category == "equipment") {
            return listOf("weapon", "artifact").flatMap { recipes[it]?.values?.toList() ?: emptyList() }
        }
        return recipes[category]?.values?.toList() ?: emptyList()
    }

    fun getRecipe(category: String, id: String): DzRecipe? {
        if (category == "equipment") {
            return recipes["weapon"]?.get(id) ?: recipes["artifact"]?.get(id)
        }
        return recipes[category]?.get(id)
    }

    fun saveRecipe(recipe: DzRecipe) {
        val actualCategory = resolveSaveCategory(recipe)
        val storedRecipe = DzRecipe(
            recipe.id,
            actualCategory,
            recipe.result,
            recipe.ingredients,
            recipe.reqJob,
            recipe.reqForgeLevel,
            recipe.reqLicense,
            recipe.expReward
        )
        val file = File(plugin.dataFolder, "baihu_dz/recipes/$actualCategory.yml")
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        val config = YamlConfiguration.loadConfiguration(file)
        val path = storedRecipe.id

        val resultId = ItemUtil.getPublicId(storedRecipe.result)
        config.set("$path.result_id", "$resultId:${storedRecipe.result.amount}")

        val ingredients = storedRecipe.ingredients.map { item ->
            if (item.type == Material.AIR) {
                "AIR:1"
            } else {
                "${ItemUtil.getPublicId(item)}:${item.amount}"
            }
        }
        config.set("$path.ingredients", ingredients)
        config.set("$path.req_job", storedRecipe.reqJob)
        config.set("$path.req_level", storedRecipe.reqForgeLevel)
        config.set("$path.req_license", storedRecipe.reqLicense)
        config.set("$path.exp_reward", storedRecipe.expReward)

        try {
            config.save(file)
            recipes.computeIfAbsent(actualCategory) { linkedMapOf() }[storedRecipe.id] = storedRecipe
        } catch (e: IOException) {
            plugin.logger.warning("保存白虎锻造配方 ${storedRecipe.id} 失败: ${e.message}")
        }
    }

    fun deleteRecipe(category: String, id: String) {
        val actualCategory = if (category == "equipment") getRecipe(category, id)?.category ?: category else category
        val file = File(plugin.dataFolder, "baihu_dz/recipes/$actualCategory.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        config.set(id, null)
        try {
            config.save(file)
            recipes[actualCategory]?.remove(id)
        } catch (e: IOException) {
            plugin.logger.warning("删除白虎锻造配方 $id 失败: ${e.message}")
        }
    }

    private fun resolveSaveCategory(recipe: DzRecipe): String {
        if (recipe.category != "equipment") return recipe.category
        return when {
            getWeaponDataFromItem(recipe.result) != null -> "weapon"
            getArtifactDataFromItem(recipe.result) != null -> "artifact"
            else -> "weapon"
        }
    }

    fun getItem(id: String): ItemStack? {
        val normalized = id.lowercase(Locale.getDefault())
        return when {
            weapons.containsKey(normalized) -> buildWeaponItem(normalized)
            artifacts.containsKey(normalized) -> buildArtifactItem(normalized)
            else -> null
        }
    }

    fun isBaihuWeaponSkillId(skillId: String): Boolean {
        val normalized = skillId.lowercase(Locale.getDefault())
        return weapons.values.any { it.skillId == normalized }
    }

    fun getDataFromItem(item: ItemStack?): BaihuEquipmentData? {
        val meta = item?.itemMeta ?: return null
        val weaponId = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)
        if (weaponId != null) return weapons[weaponId]
        val artifactId = meta.persistentDataContainer.get(artifactKey, PersistentDataType.STRING)
        if (artifactId != null) return artifacts[artifactId]
        return null
    }

    fun getWeaponDataFromItem(item: ItemStack?): BaihuWeaponData? {
        val id = item?.itemMeta?.persistentDataContainer?.get(weaponKey, PersistentDataType.STRING) ?: return null
        return weapons[id]
    }

    fun getArtifactDataFromItem(item: ItemStack?): BaihuArtifactData? {
        val meta = item?.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        val id = pdc.get(artifactKey, PersistentDataType.STRING)
        if (id != null) return artifacts[id]

        // 虎志战旗曾作为武器发放；放入饰品栏时将旧物品无损迁移为法宝。
        val legacyId = pdc.get(weaponKey, PersistentDataType.STRING) ?: return null
        if (legacyId != "huzhizhanqi" || !artifacts.containsKey(legacyId)) return null
        pdc.remove(weaponKey)
        pdc.set(artifactKey, PersistentDataType.STRING, legacyId)
        item.itemMeta = meta
        return artifacts[legacyId]
    }

    fun buildWeaponItem(id: String): ItemStack? {
        val data = weapons[id] ?: return null
        val item = ItemStack(data.material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(color(data.display))
        meta.persistentDataContainer.set(resourceKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(weaponKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(durabilityKey, PersistentDataType.INTEGER, data.maxDurability)
        meta.persistentDataContainer.set(maxDurabilityKey, PersistentDataType.INTEGER, data.maxDurability)
        meta.persistentDataContainer.set(rarityKey, PersistentDataType.INTEGER, data.rarity)
        if (data.customModelData > 0) meta.setCustomModelData(data.customModelData)
        meta.isUnbreakable = true
        meta.attributeModifiers = ArrayListMultimap.create()
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
        updateWeaponLore(item, data, null)
        return item
    }

    fun buildArtifactItem(id: String): ItemStack? {
        val data = artifacts[id] ?: return null
        val item = ItemStack(data.material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(color(data.display))
        meta.persistentDataContainer.set(resourceKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(artifactKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(durabilityKey, PersistentDataType.INTEGER, data.maxDurability)
        meta.persistentDataContainer.set(maxDurabilityKey, PersistentDataType.INTEGER, data.maxDurability)
        meta.persistentDataContainer.set(rarityKey, PersistentDataType.INTEGER, data.rarity)
        if (data.customModelData > 0) meta.setCustomModelData(data.customModelData)
        meta.isUnbreakable = true
        meta.attributeModifiers = ArrayListMultimap.create()
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
        updateArtifactLore(item, data, null, null)
        return item
    }

    fun hasMiasma(player: Player): Boolean {
        return plugin.baihuMiasmaManager.getMiasma(player) > 0
    }

    fun getDurability(item: ItemStack?, data: BaihuEquipmentData): Int {
        val meta = item?.itemMeta ?: return 0
        return meta.persistentDataContainer.get(durabilityKey, PersistentDataType.INTEGER)
            ?: data.maxDurability
    }

    fun canUse(player: Player, item: ItemStack, data: BaihuEquipmentData, sendMessage: Boolean = false): Boolean {
        if (!hasMiasma(player)) {
            if (sendMessage) player.sendMessage("§c虎瘴装只有在身负虎瘴时才能发挥作用。")
            return false
        }
        if (getDurability(item, data) <= 0) {
            if (sendMessage) player.sendMessage("§c这件虎瘴装耐久已耗尽，无法释放技能。")
            return false
        }
        return true
    }

    fun consumeDurability(player: Player, item: ItemStack, data: BaihuEquipmentData): Boolean {
        val meta = item.itemMeta ?: return false
        val current = getDurability(item, data)
        if (current < data.durabilityCost) {
            player.sendMessage("§c这件虎瘴装耐久不足，无法释放技能。")
            return false
        }
        val next = (current - data.durabilityCost).coerceAtLeast(0)
        meta.persistentDataContainer.set(durabilityKey, PersistentDataType.INTEGER, next)
        item.itemMeta = meta
        if (data is BaihuWeaponData) {
            updateWeaponLore(item, data, player, findInventorySlot(player, item))
        } else if (data is BaihuArtifactData) {
            updateArtifactLore(item, data, player, null)
        }
        if (next <= 0) {
            player.sendMessage("§c${ChatColor.stripColor(color(data.display))} 的虎瘴耐久已经耗尽。")
            plugin.playerManager.updateStats(player)
        }
        return true
    }

    fun isWeaponActive(
        player: Player,
        item: ItemStack,
        data: BaihuWeaponData,
        playerData: PlayerData,
        slot: Int,
        sendMessage: Boolean = false
    ): Boolean {
        if (!data.activationSpec.isActive(playerData, inventorySlot = slot, player = player, item = item)) {
            return false
        }
        return canUse(player, item, data, sendMessage)
    }

    fun restoreDurability(player: Player, item: ItemStack, data: BaihuEquipmentData, amount: Int): Int {
        if (amount <= 0) return 0
        val meta = item.itemMeta ?: return 0
        val current = getDurability(item, data)
        val next = (current + amount).coerceAtMost(data.maxDurability)
        val restored = next - current
        if (restored <= 0) return 0

        meta.persistentDataContainer.set(durabilityKey, PersistentDataType.INTEGER, next)
        item.itemMeta = meta
        if (data is BaihuWeaponData) {
            updateWeaponLore(item, data, player, findInventorySlot(player, item))
        } else if (data is BaihuArtifactData) {
            updateArtifactLore(item, data, player, null)
        }
        if (current <= 0) plugin.playerManager.updateStats(player)
        return restored
    }

    private fun findInventorySlot(player: Player, item: ItemStack): Int {
        for (slot in 0 until player.inventory.size) {
            if (player.inventory.getItem(slot) === item) return slot
        }
        return player.inventory.heldItemSlot
    }

    fun calculateWeaponStats(player: Player, data: PlayerData): Map<String, Double> {
        val stats = linkedMapOf<String, Double>()
        var totalRarity = 0.0

        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            val wd = getWeaponDataFromItem(item) ?: continue
            if (!isWeaponActive(player, item, wd, data, slot)) continue
            data.rarityDetails.add(wd.rarity)
            totalRarity += wd.rarity
            wd.stats.forEach { (k, v) ->
                if (k != "attack_speed") stats.merge(k, v) { a, b -> a + b }
            }
        }

        if (totalRarity > 0) stats["total_rarity"] = totalRarity
        return stats
    }

    fun calculateArtifactStats(player: Player, data: PlayerData): Map<String, Double> {
        val stats = linkedMapOf<String, Double>()
        var totalRarity = 0.0

        fun process(item: ItemStack?, slotKey: String) {
            val artifact = getArtifactDataFromItem(item) ?: return
            if (item == null || !canUse(player, item, artifact)) return
            if (!artifact.activationSpec.isActive(data, slotKey = slotKey, player = player, item = item)) return
            data.rarityDetails.add(artifact.rarity)
            totalRarity += artifact.rarity
            artifact.activations[slotKey]?.stats?.forEach { (k, v) ->
                val actualKey = if (k == "power") {
                    when (data.job) {
                        1 -> "archer_damage"
                        2, 3 -> "zf_str"
                        else -> "attack"
                    }
                } else k
                stats.merge(actualKey, v) { a, b -> a + b }
            }
        }

        readAccessoryContents(player)?.forEachIndexed { i, item -> process(item, "accessory_$i") }
        process(player.inventory.itemInOffHand, "offhand")
        for (i in 0..8) process(player.inventory.getItem(i), "hotbar_$i")

        if (totalRarity > 0) stats["total_rarity"] = totalRarity
        return stats
    }

    fun refreshPlayerEquipment(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId)
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            getWeaponDataFromItem(item)?.let { updateWeaponLore(item, it, player, slot) }
            getArtifactDataFromItem(item)?.let { updateArtifactLore(item, it, player, inventorySlotKey(slot)) }
        }

        val contents = readAccessoryContents(player) ?: return
        var changed = false
        contents.forEachIndexed { i, item ->
            val artifact = getArtifactDataFromItem(item) ?: return@forEachIndexed
            if (item != null) {
                updateArtifactLore(item, artifact, player, "accessory_$i", data)
                contents[i] = item
                changed = true
            }
        }
        if (changed) saveAccessoryContents(player, contents)
    }

    private fun updateWeaponLore(item: ItemStack, data: BaihuWeaponData, player: Player?, slot: Int = -999) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.remove(legacyNormalWeaponKey)
        val lore = mutableListOf<String>()
        lore.add(rarityLore(data.rarity))
        data.lore.forEach { lore.add(color(it)) }
        lore.add("")
        lore.add("§6虎瘴耐久: §f${getDurability(item, data)}§7/§f${data.maxDurability} §8(技能-${data.durabilityCost})")
        var active = false
        if (player != null) {
            val playerData = plugin.playerManager.getData(player.uniqueId)
            active = playerData != null && isWeaponActive(player, item, data, playerData, slot)
            if (active) {
                lore.add("§a✓ 已借虎瘴激活")
            } else {
                appendInactiveLore(lore, player, item, data, slot)
            }
        } else {
            lore.add("§7需身负虎瘴方可激活")
        }
        applyCrossbowEnchantments(item, meta, active, data)
        meta.lore = lore
        item.itemMeta = meta
    }

    private fun applyCrossbowEnchantments(
        item: ItemStack,
        meta: org.bukkit.inventory.meta.ItemMeta,
        active: Boolean,
        data: BaihuWeaponData
    ) {
        if (item.type != Material.CROSSBOW) return
        if (active) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.MULTISHOT, 1, true)
            val quickChargeLevel = if (data.id == "anhuishinu") 3 else 2
            meta.addEnchant(org.bukkit.enchantments.Enchantment.QUICK_CHARGE, quickChargeLevel, true)
        } else {
            meta.removeEnchant(org.bukkit.enchantments.Enchantment.MULTISHOT)
            meta.removeEnchant(org.bukkit.enchantments.Enchantment.QUICK_CHARGE)
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
    }

    private fun updateArtifactLore(
        item: ItemStack,
        data: BaihuArtifactData,
        player: Player?,
        slotKey: String?,
        playerDataOverride: PlayerData? = null
    ) {
        val meta = item.itemMeta ?: return
        val lore = mutableListOf<String>()
        lore.add(rarityLore(data.rarity))
        data.lore.forEach { line -> lore.add(color(replaceArtifactPlaceholders(line, item, data))) }
        lore.add("")
        lore.add("§6虎瘴耐久: §f${getDurability(item, data)}§7/§f${data.maxDurability} §8(技能-${data.durabilityCost})")

        if (player != null && slotKey != null) {
            val playerData = playerDataOverride ?: plugin.playerManager.getData(player.uniqueId)
            val active = playerData != null &&
                canUse(player, item, data) &&
                data.activationSpec.isActive(playerData, slotKey = slotKey, player = player, item = item)
            if (active) {
                lore.add("§a✓ 已借虎瘴激活")
            } else {
                appendInactiveLore(lore, player, item, data, slotKey)
            }
        } else {
            lore.add("§7需身负虎瘴方可激活")
        }
        meta.lore = lore
        item.itemMeta = meta
    }

    private fun appendInactiveLore(lore: MutableList<String>, player: Player, item: ItemStack, data: BaihuEquipmentData, slot: Any) {
        val pData = plugin.playerManager.getData(player.uniqueId)
        val activationFailure = if (pData == null) {
            ActivationFailure.PLAYER_DATA_UNAVAILABLE
        } else {
            when (data) {
                is BaihuWeaponData -> data.activationSpec.firstFailure(
                    pData,
                    inventorySlot = slot as? Int ?: ActivationSpec.UNSPECIFIED_SLOT,
                    player = player,
                    item = item
                )
                is BaihuArtifactData -> data.activationSpec.firstFailure(
                    pData,
                    slotKey = slot as? String,
                    player = player,
                    item = item
                )
            }
        }
        when {
            !hasMiasma(player) -> lore.add("§c⚠ 未身负虎瘴")
            getDurability(item, data) <= 0 -> lore.add("§c⚠ 耐久耗尽")
            activationFailure == ActivationFailure.PLAYER_DATA_UNAVAILABLE -> lore.add("§c⚠ 玩家数据未加载")
            activationFailure == ActivationFailure.JOB_MISMATCH -> lore.add("§c⚠ 职业不符")
            activationFailure == ActivationFailure.LEVEL_MISMATCH && pData != null ->
                lore.add("§c⚠ 等级不足 (${pData.lv}/${data.reqLv})")
            activationFailure == ActivationFailure.SLOT_MISMATCH && data is BaihuWeaponData ->
                lore.add(color(data.activeLoreLine))
            activationFailure == ActivationFailure.SLOT_MISMATCH && data is BaihuArtifactData ->
                lore.add("§7◆ 未放入指定激活栏")
            else -> lore.add("§7◆ 未激活")
        }
    }

    private fun replaceArtifactPlaceholders(line: String, item: ItemStack, data: BaihuArtifactData): String {
        var result = line
        if (data.maxArrows > 0) {
            val arrows = item.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "quiver_arrows"), PersistentDataType.INTEGER) ?: 0
            result = result
                .replace("{arrows}", arrows.toString())
                .replace("{max_arrows}", data.maxArrows.toString())
                .replace("{threshold}", data.replenishThreshold.toString())
                .replace("{amount}", data.replenishAmount.toString())
        }
        val stored = item.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "medical_overflow_stored"), PersistentDataType.DOUBLE) ?: 0.0
        return result
            .replace("{stored}", formatNumber(stored))
            .replace("{max_storage}", formatNumber(data.medicalOverflowMaxStorage))
            .replace("{trigger_storage}", formatNumber(data.medicalOverflowTriggerStorage))
    }

    fun toCrystalData(data: BaihuArtifactData): CrystalData = data.crystalData

    fun isArtifactActiveForSkill(player: Player, item: ItemStack, data: BaihuArtifactData, slotKey: String): Boolean {
        val pData = plugin.playerManager.getData(player.uniqueId) ?: return false
        return canUse(player, item, data, true) &&
            data.activationSpec.isActive(pData, slotKey = slotKey, player = player, item = item)
    }

    fun readAccessoryContents(player: Player): Array<ItemStack?>? {
        val saved = player.persistentDataContainer.get(accessoryInvKey, PersistentDataType.BYTE_ARRAY) ?: return null
        return try {
            BukkitObjectInputStream(ByteArrayInputStream(saved)).use { ois ->
                val size = ois.readInt()
                Array(size) { ois.readObject() as? ItemStack }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveAccessoryContents(player: Player, contents: Array<ItemStack?>) {
        val baos = ByteArrayOutputStream()
        BukkitObjectOutputStream(baos).use { oos ->
            oos.writeInt(contents.size)
            contents.forEach { oos.writeObject(it) }
        }
        player.persistentDataContainer.set(accessoryInvKey, PersistentDataType.BYTE_ARRAY, baos.toByteArray())
    }

    private fun inventorySlotKey(slot: Int): String = when (slot) {
        in 0..8 -> "hotbar_$slot"
        40 -> "offhand"
        else -> "none"
    }

    private fun rarityLore(rarity: Int): String {
        val color = when (rarity) {
            1 -> "§f"
            2 -> "§a"
            3 -> "§9"
            4 -> "§d"
            5 -> "§e"
            6 -> "§c"
            else -> "§7"
        }
        return color + "稀有度: " + WeaponManager.getRarityStars(rarity)
    }

    private fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
    }
}

sealed class BaihuEquipmentData(
    val id: String,
    sec: ConfigurationSection
) : ActivatableEquipment {
    val display: String = sec.getString("display", id)!!
    val material: Material = Material.matchMaterial(sec.getString("material", "STONE")!!) ?: Material.STONE
    val customModelData: Int = sec.getInt("custom_model_data", 0)
    val lore: List<String> = sec.getStringList("lore")
    val reqJob: Int = sec.getInt("req_job", -1)
    val reqLv: Int = sec.getInt("req_lv", 1)
    val rarity: Int = sec.getInt("rarity", 1)
    val maxDurability: Int = sec.getInt("durability.max", 100).coerceAtLeast(1)
    val durabilityCost: Int = sec.getInt("durability.skill_cost", 1).coerceAtLeast(0)

    fun isActivated(playerData: PlayerData): Boolean = activationSpec.isEligible(playerData)
}

class BaihuWeaponData(id: String, sec: ConfigurationSection) : BaihuEquipmentData(id, sec) {
    val skillId: String = sec.getString("skill_id", id)!!.lowercase(Locale.getDefault())
    val activateSlot: Int = sec.getInt("activate_slot", 0)
    val activeLoreLine: String = sec.getString("active_lore_line", "&c请放入指定激活栏，并身负虎瘴。")!!
    val stats: MutableMap<String, Double> = mutableMapOf()
    override val activationSpec: ActivationSpec = ActivationSpec(
        requiredJob = reqJob,
        requiredLevel = reqLv,
        acceptedInventorySlots = if (activateSlot == -1) null else intArrayOf(activateSlot)
    )

    init {
        sec.getConfigurationSection("stats")?.getKeys(false)?.forEach { key ->
            stats[key] = sec.getDouble("stats.$key")
        }
    }
}

class BaihuArtifactData(id: String, sec: ConfigurationSection) : BaihuEquipmentData(id, sec) {
    val skillId: String? = sec.getString("skill_id")?.lowercase(Locale.getDefault())
    val activations: MutableMap<String, BaihuActivationConfig> = mutableMapOf()
    val maxArrows: Int = if (sec.isConfigurationSection("quiver_data")) sec.getInt("quiver_data.max_arrows", 1024) else 0
    val replenishThreshold: Int = sec.getInt("quiver_data.replenish_threshold", 16)
    val replenishAmount: Int = sec.getInt("quiver_data.replenish_amount", 32)
    val medicalOverflowMaxStorage: Double = sec.getDouble("taolizhi_data.max_storage", 100.0)
    val medicalOverflowTriggerStorage: Double = sec.getDouble("taolizhi_data.trigger_storage", 20.0)
    val crystalData: CrystalData = CrystalData(id, sec)
    override lateinit var activationSpec: ActivationSpec
        private set

    init {
        val actSec = sec.getConfigurationSection("activation")
        if (actSec != null) {
            for (slotKey in actSec.getKeys(false)) {
                val stats = mutableMapOf<String, Double>()
                actSec.getConfigurationSection("$slotKey.stats")?.getKeys(false)?.forEach { key ->
                    stats[key] = actSec.getDouble("$slotKey.stats.$key")
                }
                activations[slotKey] = BaihuActivationConfig(stats)
            }
        }
        activationSpec = ActivationSpec(
            requiredJob = reqJob,
            requiredLevel = reqLv,
            acceptedSlotKeys = activations.keys
        )
    }
}

data class BaihuActivationConfig(val stats: Map<String, Double>)
