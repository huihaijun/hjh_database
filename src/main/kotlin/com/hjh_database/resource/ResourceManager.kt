package com.hjh_database.resource

import com.hjh_database.Hjh_database
import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.logging.Level

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

        // 鍒锋柊鍦ㄧ嚎鐜╁鑳屽寘
        for (p in Bukkit.getOnlinePlayers()) {
            refreshInventory(p.inventory)
        }
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
                isRefreshed = true
            }
        }

        // ==========================================================================
        // 銆愰噸濉戝尰鏃椼€戝鏋滅墿鍝佸埛鏂版垚鍔燂紝涓斿畠鍘熸湰鏄竴鎶婂埢鏈夊尰鏈殑鏃楀笢
        if (isRefreshed && skillId != null) {
            // 璋冪敤 MedicalManager 鎶婂尰鏈嫭鏈夌殑灞炴€ч噸鏂扳€滄嫾鈥濅笂鍘伙紒
            plugin.medicalManager.rebuildEtchedBanner(item, skillId)
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
        return item
    }

    private fun applyResourceCooldownGroup(item: ItemStack, res: ResourceItem) {
        val groupId = when {
            listOf("metal", "wood", "water", "fire", "earth").contains(res.id) -> "${res.id}_group"
            res.hasSicknessTime -> "alchemy_pill_sickness"
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

    fun refreshInventory(inv: Inventory) {
        for (item in inv.contents) {
            refreshItem(item)
        }
    }

    fun getAllItemNames(): List<String> {
        val list: MutableList<String> = ArrayList()
        list.addAll(nameIndex.keys) // 涓枃鍚?
        list.addAll(localResources.keys) // 鏉傞」ID
        list.addAll(plugin.playerManager.weaponManager.allIds) // 姝﹀櫒ID
        list.addAll(plugin.playerManager.armorManager.allIds) // 鎶ょ敳ID
        list.addAll(plugin.playerManager.crystalManager.allIds)
        if (plugin.isBaihuDzManagerInitialized()) {
            list.addAll(plugin.baihuDzManager.weapons.keys)
            list.addAll(plugin.baihuDzManager.artifacts.keys)
        }
        return list
    }

}

