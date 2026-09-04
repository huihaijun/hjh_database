package com.hjh_database.resource

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.PillSicknessChannel
import com.hjh_database.qixiazhen.busuan.BusuanItemLore
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.FoodProperties
import io.papermc.paper.datacomponent.item.UseCooldown
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.set.RegistrySet
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.logging.Level
import kotlin.math.roundToInt

class ResourceManager(private val plugin: Hjh_database) {

    // 浠呭瓨鍌?resources 鏂囦欢澶逛笅鐨勬潅椤圭墿鍝?
    private val localResources: MutableMap<String, ResourceItem> = HashMap()

    // 鍏ㄥ眬鍚嶇О绱㈠紩 (涓枃鍚?-> ID)锛屽寘鍚?姝﹀櫒 + 鎶ょ敳 + 鏉傞」
    private val nameIndex: MutableMap<String, String> = HashMap()

    private val keyId: NamespacedKey = NamespacedKey(plugin, "resource_id")
    // 銆愭柊澧炪€戝畾涔夊厤鍒锋柊閿佺殑 Key
    private val keyIgnoreRefresh: NamespacedKey = NamespacedKey(plugin, "hjh_ignore_refresh")

    init {
        loadAll()
    }

    fun reload() {
        // 鍏堥噸杞藉彟澶栦袱涓鐞嗗櫒锛岀‘淇濇暟鎹渶鏂?
        plugin.playerManager.weaponManager.reload()
        plugin.playerManager.armorManager.reload()

        loadAll()

        val refreshedKaiWuItems = plugin.kaiWuManager.refreshNodeResourceItems()
        plugin.kaiWuAdminGui.refreshOpenMenus()
        val refreshed = refreshOnlinePlayerContainers()
        plugin.logger.info("资源物品刷新完成，共刷新 $refreshed 个在线玩家容器物品、$refreshedKaiWuItems 个开物资源点物品。")
    }

    fun refreshOnlinePlayerContainers(): Int {
        var refreshed = 0
        for (player in Bukkit.getOnlinePlayers()) {
            refreshed += refreshInventory(player.inventory)
            refreshed += refreshInventory(player.enderChest)
            refreshed += refreshInventory(player.openInventory.topInventory)

            val accessoryContents = plugin.accessoryManager.getAccessoryContents(player)
            if (accessoryContents != null) {
                var changed = false
                for (item in accessoryContents) {
                    if (refreshItem(item)) {
                        refreshed++
                        changed = true
                    }
                }
                if (changed) {
                    plugin.accessoryManager.saveAccessoryContents(player, accessoryContents)
                }
            }
        }

        for (warehouseData in plugin.warehouseManager.getAllCachedData()) {
            for (subWarehouse in warehouseData.items) {
                for (item in subWarehouse) {
                    if (refreshItem(item)) refreshed++
                }
            }
        }

        return refreshed
    }

    private fun loadAll() {
        localResources.clear()
        nameIndex.clear()

        // 1. 鍔犺浇 resources 鏂囦欢澶逛笅鐨勬潅椤?(闈?RPG 姝﹀櫒/鎶ょ敳)
        loadLocalResources()

        // 2. 绱㈠紩 WeaponManager 鐨勭墿鍝?
        val wm = plugin.playerManager.weaponManager
        for (id in wm.allIds) {
            val name = wm.getNameById(id)
            if (name != null) nameIndex[name] = id
        }

        // 3. 绱㈠紩 ArmorManager 鐨勭墿鍝?
        val am = plugin.playerManager.armorManager
        for (id in am.allIds) {
            val name = am.getNameById(id)
            if (name != null) nameIndex[name] = id
        }

        plugin.logger.info("资源系统索引构建完成，共计索引 " + (localResources.size + wm.allIds.size + am.allIds.size) + " 个物品。")
    }

    private fun loadLocalResources() {
        val folder = File(plugin.dataFolder, "resources/items")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        listOf("farm_seeds.yml", "farm_crops.yml", "farm_tools.yml", "farm_food.yml", "busuan.yml", "dungeon.yml").forEach { name ->
            val target = File(folder, name)
            if (!target.exists()) {
                saveBundledResource("items/$name", target)
            }
        }

        // Kotlin Lambda 鍐欐硶
        val files = folder.listFiles { _, name -> name.endsWith(".yml") }
        if (files == null) return

        for (file in files) {
            val config = YamlConfiguration.loadConfiguration(file)
            for (key in config.getKeys(false)) {
                try {
                    val sec = config.getConfigurationSection(key) ?: continue

                    // 鏋勫缓 ResourceItem 瀵硅薄 (浠呬綔涓烘暟鎹鍣?
                    val item = ResourceItem(key, sec)
                    localResources[key] = item

                    // 娣诲姞鍒板悕绉扮储寮?
                    val strippedName = ChatColor.stripColor(item.name)
                    if (strippedName != null) {
                        nameIndex[strippedName] = key
                    }

                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "鍔犺浇璧勬簮鐗╁搧 $key 澶辫触", e)
                }
            }
        }
    }

    private fun saveBundledResource(resourcePath: String, targetFile: File) {
        if (targetFile.exists()) return

        targetFile.parentFile?.mkdirs()
        val input = plugin.getResource(resourcePath)
        if (input == null) {
            plugin.logger.warning("内置资源不存在，无法释放到数据目录: $resourcePath")
            return
        }

        input.use { source ->
            targetFile.outputStream().use { target ->
                source.copyTo(target)
            }
        }
    }

    /**
     * 銆愭牳蹇冦€戣幏鍙栫墿鍝?
     * 浼樺厛绾э細WeaponManager -> ArmorManager -> LocalResources
     */
    fun getItem(idOrName: String): ItemStack? {
        // 1. 濡傛灉鏄腑鏂囧悕锛屽厛杞垚 ID
        var id = idOrName
        if (nameIndex.containsKey(idOrName)) {
            // !! 鏄畨鍏ㄧ殑锛屽洜涓轰笂闈㈡鏌ヤ簡 containsKey
            id = nameIndex[idOrName]!!
        } else {
            // 灏濊瘯妫€鏌ユ槸鍚︽槸ID (濡傛灉涓嶅湪nameIndex閲岋紝鍙兘鏄洜涓篒D鍜孨ame涓嶅尮閰嶏紝鎴栬€呮槸鐩存帴杈撳叆鐨処D)
            // 杩欓噷涓嶅仛澶勭悊锛岀洿鎺ョ敤杈撳叆鐨勫瓧绗︿覆褰揑D鍘绘煡
        }

        // 2. 灏濊瘯浠?WeaponManager 鑾峰彇 (鑷甫 RPG 灞炴€?
        val wm = plugin.playerManager.weaponManager
        if (wm.allIds.contains(id)) {
            return wm.getItemStack(id)
        }

        // 3. 灏濊瘯浠?ArmorManager 鑾峰彇 (鑷甫 RPG 灞炴€?
        val am = plugin.playerManager.armorManager
        if (am.allIds.contains(id)) {
            return am.getItemStack(id)
        }

        // 普通法宝只保存 artifact_id，不应退化成其原版材质。
        plugin.artifactManager.getItem(id)?.let { return it }

        // 4. 灏濊瘯浠?CrystalManager 鑾峰彇缁撴櫠
        val cm = plugin.playerManager.crystalManager
        if (cm.allIds.contains(id)) {
            // 璋冪敤 CrystalManager 涓殑 buildItem 鏂规硶鐢熸垚缁撴櫠
            return cm.buildItem(id)
        }

        if (plugin.isBaihuDzManagerInitialized()) {
            plugin.baihuDzManager.getItem(id)?.let { return it }
        }

        // 5. 灏濊瘯浠?LocalResources 鑾峰彇 (鏉傞」)
        val res = localResources[id]
        if (res != null) {
            return buildLocalItem(res)
        }

        return null
    }

    /**
     * 銆愭柊澧炪€戣幏鍙栫墿鍝佺殑鍘熷鏁版嵁瀵硅薄 (鐢ㄤ簬鐐间腹绯荤粺璇诲彇 yml 涓殑绛夌骇銆佽嵂姣掔瓑閰嶇疆)
     */
    fun getLocalResource(id: String): ResourceItem? {
        return localResources[id]
    }

    /**
     * 鍒锋柊宸叉湁鐗╁搧 (鐢ㄤ簬 ResourceListener)
     */
    fun refreshItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        val meta = item.itemMeta ?: return false

        // 特殊 GUI 会在资源物品的克隆上附加临时名称或 Lore（例如市场售价、购买时间）。
        // 免刷新标记必须在核心入口统一判断，否则 refreshInventory/全量资源重载会绕过
        // ResourceListener 的保护，再次用 YAML 模板覆盖整份 ItemMeta。
        if (meta.persistentDataContainer.has(keyIgnoreRefresh, PersistentDataType.INTEGER)) return false

        // 妫€鏌ユ槸鍚︽槸鏈彃浠剁殑鑷畾涔夌墿鍝?
        if (!meta.persistentDataContainer.has(keyId, PersistentDataType.STRING)) return false
        val id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING) ?: return false

        // ==========================================================================
        // 銆愭牳蹇冧慨鏀癸細鏅鸿兘鍒锋柊銆戝厛鎻愬彇鍙兘瀛樺湪鐨勫尰鏈?ID锛屼笉鐩存帴鎷︽埅锛?
        val keySkillId = NamespacedKey(plugin, "med_skill_id")
        val skillId = meta.persistentDataContainer.get(keySkillId, PersistentDataType.STRING)
        // ==========================================================================

        var isRefreshed = false

        // --- 閫昏緫鍒嗘敮 (鎺ュ彈 YML 鏈€鏂伴厤缃鐩? ---
        // A. 濡傛灉鏄鍣?
        val wm = plugin.playerManager.weaponManager
        if (wm.allIds.contains(id)) {
            val newItem = wm.getItemStack(id)
            if (newItem != null) {
                item.type = newItem.type
                item.itemMeta = newItem.itemMeta // 杩欓噷浼氳鐩栨垚 YML 鏈€鏂扮増锛屼絾鍘熸湰鐨?NBT 鍜屽浘妗堜細涓㈠け锛?
                isRefreshed = true
            }
        }
        // B. 濡傛灉鏄姢鐢?
        else if (plugin.playerManager.armorManager.allIds.contains(id)) {
            val newItem = plugin.playerManager.armorManager.getItemStack(id)
            if (newItem != null) {
                item.type = newItem.type
                item.itemMeta = newItem.itemMeta
                isRefreshed = true
            }
        }
        // C. 濡傛灉鏄潅椤?
        else {
            val res = localResources[id]
            if (res != null) {
                if (item.type != res.material) item.type = res.material
                // 銆愭柊澧炪€戝埛鏂版椂鍚屾鍫嗗彔缁勪欢
                if (res.maxStackSize != null) {
                    item.setData(DataComponentTypes.MAX_STACK_SIZE, res.maxStackSize.coerceIn(1, 99))
                } else {
                    // 濡傛灉閰嶇疆閲屽垹鎺変簡锛屽氨绉婚櫎璇ョ粍浠舵仮澶嶅師鐗堥粯璁?
                    item.resetData(DataComponentTypes.MAX_STACK_SIZE)
                }
                val newMeta = item.itemMeta!!
                applyResourceToMeta(newMeta, res)
                item.itemMeta = newMeta
                applyResourceCooldownGroup(item, res)
                applyResourceFoodComponents(item, res)
                isRefreshed = true
            }
        }

        // ==========================================================================
        // 銆愰噸濉戝尰鏃椼€戝鏋滅墿鍝佸埛鏂版垚鍔燂紝涓斿畠鍘熸湰鏄竴鎶婂埢鏈夊尰鏈殑鏃楀笢
        if (isRefreshed && skillId != null) {
            // 璋冪敤 MedicalManager 鎶婂尰鏈嫭鏈夌殑灞炴€ч噸鏂扳€滄嫾鈥濅笂鍘伙紒
            plugin.medicalManager.rebuildEtchedBanner(item, skillId)
        }
        if (isRefreshed) {
            // Resource 模板会覆盖 lore；卜算签运的获得日期保存在 PDC 中，刷新后必须重新附加。
            BusuanItemLore.restore(plugin, item, id)
        }
        // ==========================================================================

        return isRefreshed
    }

    // 鏋勫缓鏉傞」鐗╁搧
    private fun buildLocalItem(res: ResourceItem): ItemStack {
        val item = ItemStack(res.material)

        // 銆愭柊澧炪€戣缃渶澶у爢鍙犳暟閲忕粍浠?
        res.maxStackSize?.let { size ->
            // 纭繚鏁板€煎湪 1-99 涔嬮棿锛圡inecraft 闄愬埗锛?
            val validatedSize = size.coerceIn(1, 99)
            item.setData(DataComponentTypes.MAX_STACK_SIZE, validatedSize)
        }

        val meta = item.itemMeta
        if (meta != null) {
            applyResourceToMeta(meta, res)
            meta.persistentDataContainer.set(keyId, PersistentDataType.STRING, res.id!!)
            item.itemMeta = meta
        }
        applyResourceCooldownGroup(item, res)
        applyResourceFoodComponents(item, res)
        return item
    }

    private fun applyResourceFoodComponents(item: ItemStack, res: ResourceItem) {
        val food = res.food
        if (food == null) {
            // farm_food 项删除 hunger 后应立即失去食物能力，而不是退回底材的原版食物属性。
            if (res.id?.startsWith("farm_food_") == true) {
                item.unsetData(DataComponentTypes.FOOD)
                item.unsetData(DataComponentTypes.CONSUMABLE)
                item.unsetData(DataComponentTypes.USE_COOLDOWN)
            }
            return
        }

        item.setData(
            DataComponentTypes.FOOD,
            FoodProperties.food()
                // 配置按玩家可见的饥饿条格数填写；原版 nutrition 以半格为 1 点。
                .nutrition((food.hunger * 2.0).roundToInt())
                .saturation(food.saturation)
                .canAlwaysEat(food.canAlwaysEat)
                .build()
        )

        val consumable = Consumable.consumable()
            .consumeSeconds(food.eatSeconds)
            .animation(ItemUseAnimation.EAT)
            .sound(Key.key("minecraft:entity.generic.eat"))
            .hasConsumeParticles(true)

        val clearTypes = food.clearEffects.mapNotNull { configured ->
            resolvePotionEffectType(configured).also { resolved ->
                if (resolved == null) plugin.logger.warning("食物 ${res.id} 配置了无效的清除效果：$configured")
            }
        }
        if (clearTypes.isNotEmpty()) {
            consumable.addEffect(
                ConsumeEffect.removeEffects(RegistrySet.keySetFromValues(RegistryKey.MOB_EFFECT, clearTypes))
            )
        }

        food.potionEffects.forEach { configured ->
            val type = resolvePotionEffectType(configured.type)
            if (type == null) {
                plugin.logger.warning("食物 ${res.id} 配置了无效的药水效果：${configured.type}")
                return@forEach
            }
            if (configured.chancePercent <= 0.0) return@forEach
            val effect = PotionEffect(
                type,
                (configured.durationSeconds * 20.0).roundToInt().coerceAtLeast(1),
                configured.level - 1,
                configured.ambient,
                configured.particles,
                configured.icon
            )
            consumable.addEffect(
                ConsumeEffect.applyStatusEffects(listOf(effect), (configured.chancePercent / 100.0).toFloat())
            )
        }
        item.setData(DataComponentTypes.CONSUMABLE, consumable.build())

        val cooldown = food.cooldownSeconds
        if (cooldown == null) {
            item.unsetData(DataComponentTypes.USE_COOLDOWN)
        } else {
            val group = res.id.orEmpty().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9/._-]"), "_")
            item.setData(
                DataComponentTypes.USE_COOLDOWN,
                UseCooldown.useCooldown(cooldown)
                    .cooldownGroup(Key.key(plugin.name.lowercase(Locale.ROOT), group))
                    .build()
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePotionEffectType(input: String): PotionEffectType? {
        val trimmed = input.trim()
        PotionEffectType.getByName(trimmed.uppercase(Locale.ROOT))?.let { return it }
        val rawKey = if (':' in trimmed) trimmed.lowercase(Locale.ROOT) else "minecraft:${trimmed.lowercase(Locale.ROOT)}"
        val key = NamespacedKey.fromString(rawKey) ?: return null
        return Registry.POTION_EFFECT_TYPE[key]
    }

    private fun applyResourceCooldownGroup(item: ItemStack, res: ResourceItem) {
        val groupId = when {
            listOf("metal", "wood", "water", "fire", "earth").contains(res.id) -> "${res.id}_group"
            res.hasSicknessTime -> PillSicknessChannel.fromEffectId(res.id).cooldownGroup
            else -> return
        }

        val exactKeyString = "${plugin.name.lowercase()}:$groupId"
        val cooldownComponent = io.papermc.paper.datacomponent.item.UseCooldown.useCooldown(0.1f)
            .cooldownGroup(net.kyori.adventure.key.Key.key(exactKeyString))
            .build()

        item.setData(DataComponentTypes.USE_COOLDOWN, cooldownComponent)
    }

    private fun applyResourceToMeta(meta: ItemMeta, res: ResourceItem) {
        meta.setDisplayName(res.name)
        meta.lore = res.lore
        if (res.hasCustomModelData()) {
            meta.setCustomModelData(res.customModelData)
        }
        if (res.isUnbreakable) {
            meta.isUnbreakable = true
        }
        if (meta is PotionMeta) {
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ATTRIBUTES)

            // 如果配置了颜色，则应用自定义药水颜色。
            if (res.colorHex != null) {
                try {
                    val cleanHex = res.colorHex.replace("#", "")
                    val rgb = cleanHex.toInt(16)
                    meta.color = Color.fromRGB(rgb)
                } catch (e: Exception) {
                    plugin.logger.warning("鐗╁搧 ${res.id} 鐨勯鑹查厤缃敊璇? ${res.colorHex}")
                }
            }
        }
    }

    fun refreshInventory(inv: Inventory): Int {
        var refreshed = 0
        for (item in inv.contents) {
            if (refreshItem(item)) refreshed++
        }
        return refreshed
    }

    fun getAllItemNames(): List<String> {
        val list: MutableList<String> = ArrayList()
        list.addAll(nameIndex.keys) // 涓枃鍚?
        list.addAll(localResources.keys) // 鏉傞」ID
        list.addAll(plugin.playerManager.weaponManager.allIds) // 姝﹀櫒ID
        list.addAll(plugin.playerManager.armorManager.allIds) // 鎶ょ敳ID
        list.addAll(plugin.playerManager.crystalManager.allIds)
        list.addAll(plugin.artifactManager.allIds) // 普通法宝 ID
        if (plugin.isBaihuDzManagerInitialized()) {
            list.addAll(plugin.baihuDzManager.weapons.keys)
            list.addAll(plugin.baihuDzManager.artifacts.keys)
        }
        return list.distinct().sorted()
    }

}

