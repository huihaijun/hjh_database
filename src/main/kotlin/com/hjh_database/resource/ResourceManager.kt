package com.hjh_database.resource

import com.hjh_database.Hjh_database
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.UseCooldown
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.logging.Level

class ResourceManager(private val plugin: Hjh_database) {

    // 仅存储 resources 文件夹下的杂项物品
    private val localResources: MutableMap<String, ResourceItem> = HashMap()

    // 全局名称索引 (中文名 -> ID)，包含 武器 + 护甲 + 杂项
    private val nameIndex: MutableMap<String, String> = HashMap()

    private val keyId: NamespacedKey = NamespacedKey(plugin, "resource_id")
    // 【新增】定义免刷新锁的 Key
    private val keyIgnoreRefresh: NamespacedKey = NamespacedKey(plugin, "hjh_ignore_refresh")

    init {
        loadAll()
    }

    fun reload() {
        // 先重载另外两个管理器，确保数据最新
        plugin.playerManager.weaponManager.reload()
        plugin.playerManager.armorManager.reload()

        loadAll()

        // 刷新在线玩家背包
        for (p in Bukkit.getOnlinePlayers()) {
            refreshInventory(p.inventory)
        }
    }

    private fun loadAll() {
        localResources.clear()
        nameIndex.clear()

        // 1. 加载 resources 文件夹下的杂项 (非 RPG 武器/护甲)
        loadLocalResources()

        // 2. 索引 WeaponManager 的物品
        val wm = plugin.playerManager.weaponManager
        for (id in wm.allIds) {
            val name = wm.getNameById(id)
            if (name != null) nameIndex[name] = id
        }

        // 3. 索引 ArmorManager 的物品
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

        // Kotlin Lambda 写法
        val files = folder.listFiles { _, name -> name.endsWith(".yml") }
        if (files == null) return

        for (file in files) {
            val config = YamlConfiguration.loadConfiguration(file)
            for (key in config.getKeys(false)) {
                try {
                    val sec = config.getConfigurationSection(key) ?: continue

                    // 构建 ResourceItem 对象 (仅作为数据容器)
                    val item = ResourceItem(key, sec)
                    localResources[key] = item

                    // 添加到名称索引
                    val strippedName = ChatColor.stripColor(item.name)
                    if (strippedName != null) {
                        nameIndex[strippedName] = key
                    }

                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "加载资源物品 $key 失败", e)
                }
            }
        }
    }

    /**
     * 【核心】获取物品
     * 优先级：WeaponManager -> ArmorManager -> LocalResources
     */
    fun getItem(idOrName: String): ItemStack? {
        // 1. 如果是中文名，先转成 ID
        var id = idOrName
        if (nameIndex.containsKey(idOrName)) {
            // !! 是安全的，因为上面检查了 containsKey
            id = nameIndex[idOrName]!!
        } else {
            // 尝试检查是否是ID (如果不在nameIndex里，可能是因为ID和Name不匹配，或者是直接输入的ID)
            // 这里不做处理，直接用输入的字符串当ID去查
        }

        // 2. 尝试从 WeaponManager 获取 (自带 RPG 属性)
        val wm = plugin.playerManager.weaponManager
        if (wm.allIds.contains(id)) {
            return wm.getItemStack(id)
        }

        // 3. 尝试从 ArmorManager 获取 (自带 RPG 属性)
        val am = plugin.playerManager.armorManager
        if (am.allIds.contains(id)) {
            return am.getItemStack(id)
        }

        // 4. 尝试从 LocalResources 获取 (杂项)
        val res = localResources[id]
        if (res != null) {
            return buildLocalItem(res)
        }

        return null
    }

    /**
     * 刷新已有物品 (用于 ResourceListener)
     */
    fun refreshItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        val meta = item.itemMeta ?: return false

        // 检查是否是本插件的自定义物品
        if (!meta.persistentDataContainer.has(keyId, PersistentDataType.STRING)) return false
        val id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING) ?: return false

        // ==========================================================================
        // 【核心修改：智能刷新】先提取可能存在的医术 ID，不直接拦截！
        val keySkillId = NamespacedKey(plugin, "med_skill_id")
        val skillId = meta.persistentDataContainer.get(keySkillId, PersistentDataType.STRING)
        // ==========================================================================

        var isRefreshed = false

        // --- 逻辑分支 (接受 YML 最新配置覆盖) ---
        // A. 如果是武器
        val wm = plugin.playerManager.weaponManager
        if (wm.allIds.contains(id)) {
            val newItem = wm.getItemStack(id)
            if (newItem != null) {
                item.type = newItem.type
                item.itemMeta = newItem.itemMeta // 这里会覆盖成 YML 最新版，但原本的 NBT 和图案会丢失！
                isRefreshed = true
            }
        }
        // B. 如果是护甲
        else if (plugin.playerManager.armorManager.allIds.contains(id)) {
            val newItem = plugin.playerManager.armorManager.getItemStack(id)
            if (newItem != null) {
                item.type = newItem.type
                item.itemMeta = newItem.itemMeta
                isRefreshed = true
            }
        }
        // C. 如果是杂项
        else {
            val res = localResources[id]
            if (res != null) {
                if (item.type != res.material) item.type = res.material
                // 注意：由于杂项你写的是 applyResourceToMeta，它只会改 Name 和 Lore，原有的 NBT 可能还活着，
                // 但为了统一安全，我们依然重新走一遍构建流程
                val newMeta = item.itemMeta!!
                applyResourceToMeta(newMeta, res)
                item.itemMeta = newMeta
                isRefreshed = true
            }
        }

        // ==========================================================================
        // 【重塑医旗】如果物品刷新成功，且它原本是一把刻有医术的旗帜
        if (isRefreshed && skillId != null) {
            // 调用 MedicalManager 把医术独有的属性重新“拼”上去！
            plugin.medicalManager.rebuildEtchedBanner(item, skillId)
        }
        // ==========================================================================

        return isRefreshed
    }

    // 构建杂项物品
    private fun buildLocalItem(res: ResourceItem): ItemStack {
        val item = ItemStack(res.material)
        val meta = item.itemMeta
        if (meta != null) {
            applyResourceToMeta(meta, res)
            meta.persistentDataContainer.set(keyId, PersistentDataType.STRING, res.id!!)
            item.itemMeta = meta
        }
        // ================= 新增：统一注入冷却组组件 =================
        // 如果它是五行元素，出厂就自带 Cooldown 冷却组组件，保证新老物品都能堆叠
        val elements = listOf("metal", "wood", "water", "fire", "earth")
        if (elements.contains(res.id)) {
            // 【精准匹配】：根据你的 groupKeys，这里拼接上 "_group" 后缀
            // 使用 plugin.name.lowercase() 动态获取命名空间，确保 100% 和 Bukkit 的 NamespacedKey 一致
            val exactKeyString = "${plugin.name.lowercase()}:${res.id}_group"

            val cooldownComponent = io.papermc.paper.datacomponent.item.UseCooldown.useCooldown(0.1f)
                .cooldownGroup(net.kyori.adventure.key.Key.key(exactKeyString))
                .build()

            item.setData(io.papermc.paper.datacomponent.DataComponentTypes.USE_COOLDOWN, cooldownComponent)
        }
        return item
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
    }

    fun refreshInventory(inv: Inventory) {
        for (item in inv.contents) {
            refreshItem(item)
        }
    }

    fun getAllItemNames(): List<String> {
        val list: MutableList<String> = ArrayList()
        list.addAll(nameIndex.keys) // 中文名
        list.addAll(localResources.keys) // 杂项ID
        list.addAll(plugin.playerManager.weaponManager.allIds) // 武器ID
        list.addAll(plugin.playerManager.armorManager.allIds) // 护甲ID
        return list
    }

}