package com.hjh_database.skill.weapon

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.job_0.baihuajianSkill
import com.hjh_database.skill.weapon.job_0.chitongjianSkill
import com.hjh_database.skill.weapon.job_0.jutongzhanchuiSkill
import com.hjh_database.skill.weapon.job_0.kaishandaoSkill
import com.hjh_database.skill.weapon.job_0.kunlunfeixianjianSkill
import com.hjh_database.skill.weapon.job_0.pokongfuSkill
import com.hjh_database.skill.weapon.job_0.qintongjianSkill
import com.hjh_database.skill.weapon.job_0.sanbaoyuruyiSkill
import com.hjh_database.skill.weapon.job_0.taijijianSkill
import com.hjh_database.skill.weapon.job_0.taomujianSkill
import com.hjh_database.skill.weapon.job_1.NoviceBowSkill
import com.hjh_database.skill.weapon.job_1.beidoumieshengongSkill
import com.hjh_database.skill.weapon.job_1.heitienuSkill
import com.hjh_database.skill.weapon.job_1.honglingnuSkill
import com.hjh_database.skill.weapon.job_1.jiaolongnuSkill
import com.hjh_database.skill.weapon.job_1.qingtonggongSkill
import com.hjh_database.skill.weapon.job_1.riyueliuxingnuSkill
import com.hjh_database.skill.weapon.job_1.tengmugongSkill
import com.hjh_database.skill.weapon.job_1.tingchaoSkill
import com.hjh_database.skill.weapon.job_1.yantiegongSkill
import com.hjh_database.skill.weapon.job_1.zhongchuigongSkill
import com.hjh_database.skill.weapon.job_1.zhuiyueSkill
import com.hjh_database.spawner.impl.NorthWetnessSkill
import com.hjh_database.weapon.WeaponManager
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WeaponSkillManager(private val plugin: Hjh_database) {
    private val weaponKey = NamespacedKey(plugin, "weapon_id")
    private val resourceKey = NamespacedKey(plugin, "resource_id")
    private val skillConfigCache: MutableMap<String, ConfigurationSection> = HashMap()
    private val skillRegistry: MutableMap<String, WeaponSkill> = HashMap()
    private val globalCooldowns: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val cooldownVersions: MutableMap<UUID, Long> = ConcurrentHashMap()

    // 记录由技能主动维持的持续状态。Key: 玩家 UUID, Value: 武器/技能 ID。
    private val activeToggles = ConcurrentHashMap<UUID, String>()

    // 上一 tick 满足激活要求的普通武器，用于在武器移出激活位时清理持续类技能状态。
    private val activeWeapons = ConcurrentHashMap<UUID, String>()

    init {
        registerSkills()
        reload()

        // 每 tick 检查一次即可，避免每个技能各自启动重复任务。
        object : BukkitRunnable() {
            override fun run() {
                checkActiveWeaponChanges()
                checkAllToggles()
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    fun reload() {
        skillConfigCache.clear()
        val rootDir = File(plugin.dataFolder, "weapon_skills")
        if (!rootDir.exists()) {
            rootDir.mkdirs()
            createDefaultSkillFile(rootDir)
        }
        loadSkillFiles(rootDir)
    }

    private fun loadSkillFiles(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                loadSkillFiles(file)
            } else if (file.name.endsWith(".yml")) {
                val fileName = file.name.removeSuffix(".yml").lowercase()
                skillConfigCache[fileName] = YamlConfiguration.loadConfiguration(file)
            }
        }
    }

    private fun createDefaultSkillFile(root: File) {
        // 默认技能配置已经随插件资源提供，这里只负责确保目录存在。
    }

    private fun registerSkills() {
        skillRegistry["taomujian"] = taomujianSkill()
        skillRegistry["kaishandao"] = kaishandaoSkill()
        skillRegistry["qintongjian"] = qintongjianSkill()
        skillRegistry["chitongjian"] = chitongjianSkill()
        skillRegistry["jutongzhanchui"] = jutongzhanchuiSkill()
        skillRegistry["pokongfu"] = pokongfuSkill()
        skillRegistry["taijijian"] = taijijianSkill()
        skillRegistry["sanbaoyuruyi"] = sanbaoyuruyiSkill()
        skillRegistry["kunlunfeixianjian"] = kunlunfeixianjianSkill()
        skillRegistry["baihuajian"] = baihuajianSkill()

        // 弓箭手技能
        skillRegistry["tengmugong"] = tengmugongSkill()
        skillRegistry["heitienu"] = heitienuSkill()
        skillRegistry["qingtonggong"] = qingtonggongSkill()
        skillRegistry["honglingnu"] = honglingnuSkill()
        skillRegistry["yantiegong"] = yantiegongSkill()
        skillRegistry["jiaolongnu"] = jiaolongnuSkill()
        skillRegistry["zhongchuigong"] = zhongchuigongSkill()
        skillRegistry["riyueliuxingnu"] = riyueliuxingnuSkill()
        skillRegistry["beidoumieshengong"] = beidoumieshengongSkill()
        skillRegistry["tingchao"] = tingchaoSkill()
        skillRegistry["zhuiyue"] = zhuiyueSkill()

        skillRegistry["novice_bow"] = NoviceBowSkill()
    }

    fun tryCastSkill(player: Player, rawWeaponId: String, item: ItemStack, projectile: Entity?) {
        // 白虎武器拥有独立技能系统，不进入普通武器技能管理器。
        if (plugin.baihuDzManager.getWeaponDataFromItem(item) != null) return

        val weaponId = rawWeaponId.lowercase()
        if (plugin.baihuDzManager.isBaihuWeaponSkillId(weaponId)) return

        if (!skillRegistry.containsKey(weaponId)) {
            plugin.logger.warning("未找到注册的技能 ID: $weaponId (原始 ID: $rawWeaponId)")
            return
        }

        val config = skillConfigCache[weaponId]
        if (config == null || !config.getBoolean("active.enable", false)) return

        val weaponData = plugin.playerManager.weaponManager.getWeaponData(weaponId)
        if (weaponData == null) {
            plugin.logger.warning("未找到武器数据配置: $weaponId")
            return
        }

        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        if (!isWeaponActive(player, weaponData, data)) {
            player.sendMessage(ChatColor.RED.toString() + weaponData.activeLoreLine)
            return
        }

        if (isOnCooldown(player)) {
            val preciseTime = (globalCooldowns[player.uniqueId]!! - System.currentTimeMillis()) / 1000.0
            val cdMsg = String.format("&c&l武器技处于冷却中，剩余 %.1f 秒", preciseTime)
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent(ChatColor.translateAlternateColorCodes('&', cdMsg))
            )
            return
        }

        val skill = skillRegistry[weaponId] ?: return
        val activeConfig = config.getConfigurationSection("active") ?: return

        if (NorthWetnessSkill.tryInterruptSkill(player) {
                applyCooldown(player, data, item.type, 5.0, ignoreReduction = true)
            }
        ) {
            return
        }

        if (skill.castActive(player, data, activeConfig, projectile)) {
            val baseCd = activeConfig.getDouble("cooldown", 10.0)
            applyCooldown(player, data, item.type, baseCd)
            plugin.elementCrystalManager.triggerWaterSkill(
                player,
                "weapon",
                weaponId,
                baseCd * (1.0 - data.coolReduce),
                item.type
            )

            val successMsg = activeConfig.getString("message")
            if (!successMsg.isNullOrEmpty()) {
                player.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    TextComponent(ChatColor.translateAlternateColorCodes('&', successMsg))
                )
            }
        }
    }

    private fun isWeaponActive(
        player: Player,
        weaponData: WeaponManager.WeaponData,
        data: PlayerData
    ): Boolean {
        return weaponData.activationSpec.isActive(
            playerData = data,
            inventorySlot = player.inventory.heldItemSlot,
            player = player,
            item = player.inventory.itemInMainHand
        )
    }

    private fun applyCooldown(
        player: Player,
        data: PlayerData,
        mat: Material,
        baseSeconds: Double,
        ignoreReduction: Boolean = false
    ) {
        val reduce = if (ignoreReduction) 0.0 else data.coolReduce.coerceAtMost(0.5)
        val finalSeconds = baseSeconds * (1.0 - reduce)
        val ticks = (finalSeconds * 20).toInt()

        // 弓和弩不设置原版物品冷却，否则会影响拉弓/装填手感。
        if (mat != Material.BOW && mat != Material.CROSSBOW) {
            player.setCooldown(mat, ticks)
        }

        globalCooldowns[player.uniqueId] = System.currentTimeMillis() + (finalSeconds * 1000).toLong()
        val cooldownVersion = cooldownVersions.merge(player.uniqueId, 1L) { current, increment ->
            current + increment
        } ?: 1L

        object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline) return
                if (cooldownVersions[player.uniqueId] != cooldownVersion) return
                if (!isOnCooldown(player)) {
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 2.0f)
                    player.spigot().sendMessage(
                        ChatMessageType.ACTION_BAR,
                        TextComponent(ChatColor.translateAlternateColorCodes('&', "&a&l武器技冷却完毕！"))
                    )
                }
            }
        }.runTaskLater(plugin, (ticks + 1).toLong())
    }

    private fun isOnCooldown(player: Player): Boolean {
        return (globalCooldowns[player.uniqueId] ?: return false) > System.currentTimeMillis()
    }

    fun registerToggle(player: Player, weaponId: String) {
        activeToggles[player.uniqueId] = weaponId.lowercase()
    }

    fun unregisterToggle(player: Player) {
        activeToggles.remove(player.uniqueId)
    }

    private fun checkActiveWeaponChanges() {
        val online = HashSet<UUID>()
        for (player in plugin.server.onlinePlayers) {
            val uuid = player.uniqueId
            online.add(uuid)

            val current = findActiveWeaponId(player)
            val previous = activeWeapons[uuid]

            if (previous != null && previous != current) {
                skillRegistry[previous]?.deactivate(player)
                activeToggles.remove(uuid)
            }

            if (current == null) {
                activeWeapons.remove(uuid)
            } else {
                activeWeapons[uuid] = current
            }
        }

        activeWeapons.keys.removeIf { it !in online }
        activeToggles.keys.removeIf { it !in online }
    }

    private fun findActiveWeaponId(player: Player): String? {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return null
        for (slot in 0..8) {
            val item = player.inventory.getItem(slot) ?: continue

            // 白虎武器不参与普通武器持续状态生命周期。
            if (plugin.baihuDzManager.getWeaponDataFromItem(item) != null) continue

            val id = getWeaponIdFromItem(item) ?: continue
            if (plugin.baihuDzManager.isBaihuWeaponSkillId(id)) continue

            val weapon = plugin.playerManager.weaponManager.loadedWeapons[id] ?: continue
            if (weapon.activateSlot == -1 && player.inventory.heldItemSlot != slot) continue
            if (weapon.activationSpec.isActive(data, inventorySlot = slot, player = player, item = item)) return id
        }
        return null
    }

    fun isWeaponActivated(player: Player, weaponId: String): Boolean {
        return findActiveWeaponId(player) == weaponId.lowercase()
    }

    private fun checkAllToggles() {
        if (activeToggles.isEmpty()) return

        val iterator = activeToggles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val player = plugin.server.getPlayer(entry.key)
            val weaponId = entry.value

            if (player == null || !player.isOnline) {
                iterator.remove()
                continue
            }

            if (findActiveWeaponId(player) != weaponId) {
                deactivateAndRemove(iterator, player, weaponId)
            }
        }
    }

    private fun deactivateAndRemove(iterator: MutableIterator<*>, player: Player, weaponId: String) {
        skillRegistry[weaponId]?.deactivate(player)
        iterator.remove()
    }

    private fun getWeaponIdFromItem(item: ItemStack?): String? {
        if (item == null || !item.hasItemMeta()) return null
        val pdc = item.itemMeta!!.persistentDataContainer
        return (
            pdc.get(weaponKey, PersistentDataType.STRING)
                ?: pdc.get(resourceKey, PersistentDataType.STRING)
            )?.lowercase()
    }

    fun getPlugin(): Hjh_database {
        return plugin
    }

    fun reduceCooldown(player: Player, seconds: Double) {
        reduceCooldown(player, seconds, player.inventory.itemInMainHand.type)
    }

    fun resetCooldown(player: Player, material: Material? = null) {
        globalCooldowns.remove(player.uniqueId)
        cooldownVersions.merge(player.uniqueId, 1L) { current, increment -> current + increment }
        material?.let { player.setCooldown(it, 0) }
    }

    fun reduceCooldown(player: Player, seconds: Double, material: Material?) {
        val uuid = player.uniqueId
        val currentEnd = globalCooldowns[uuid] ?: return
        val now = System.currentTimeMillis()
        if (currentEnd <= now) return

        val newEnd = currentEnd - (seconds * 1000.0).toLong()
        if (newEnd <= now) {
            globalCooldowns.remove(uuid)
            cooldownVersions.merge(uuid, 1L) { current, increment -> current + increment }
            material?.let { player.setCooldown(it, 0) }
        } else {
            globalCooldowns[uuid] = newEnd
            val remainingTicks = ((newEnd - now) / 50L).toInt()
            val cooldownType = material ?: player.inventory.itemInMainHand.type
            if (cooldownType != Material.BOW && cooldownType != Material.CROSSBOW) {
                player.setCooldown(cooldownType, remainingTicks)
            }
        }
    }
}
