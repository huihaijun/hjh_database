package com.hjh_database.skill.element_zf

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.impl.*
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.UseCooldown
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ElementZfManager(private val plugin: Hjh_database) {
    private var config: FileConfiguration? = null

    // 存储 元素名 -> 技能逻辑 的映射
    private val skills: MutableMap<String, ElementSkill> = HashMap()
    private val enhancedSkills: MutableMap<String, EnhancedElementSkill> = HashMap()

    // 系统级冷却记录表: PlayerUUID -> (ElementType -> CooldownEndTime)
    private val internalCooldowns: MutableMap<UUID, MutableMap<String, Long>> = ConcurrentHashMap()

    // 【新增】定义不同元素的冷却组 Key
    private val groupKeys: Map<String, NamespacedKey> = mapOf(
        "METAL" to NamespacedKey(plugin, "metal_group"),
        "WOOD" to NamespacedKey(plugin, "wood_group"),
        "WATER" to NamespacedKey(plugin, "water_group"),
        "FIRE" to NamespacedKey(plugin, "fire_group"),
        "EARTH" to NamespacedKey(plugin, "earth_group")
    )

    init {
        reload()
        registerSkills()
    }

    fun reload() {
        val file = File(plugin.dataFolder, "element_zf.yml")
        if (!file.exists()) {
            plugin.saveResource("element_zf.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(file)
    }

    private fun registerSkills() {
        skills["METAL"] = MetalSkill(plugin)
        skills["WOOD"] = WoodSkill(plugin)
        skills["WATER"] = WaterSkill(plugin)
        skills["FIRE"] = FireSkill(plugin)
        skills["EARTH"] = EarthSkill(plugin)

        enhancedSkills["METAL"] = EnhancedMetalSkill(plugin)
        enhancedSkills["WOOD"] = EnhancedWoodSkill(plugin)
        enhancedSkills["WATER"] = EnhancedWaterSkill(plugin)
        enhancedSkills["FIRE"] = EnhancedFireSkill(plugin)
        enhancedSkills["EARTH"] = EnhancedEarthSkill(plugin)
    }

    fun castSkill(player: Player, type: String, data: PlayerData) {
        val skill = skills[type] ?: return

        // 1. 检查等级
        val level = data.getElementLevel(type)
        if (level <= 0) {
            player.sendMessage(ChatColor.RED.toString() + "你尚未领悟 " + type + " 阵法！")
            return
        }

        // 2. 检查冷却
        if (isOnCooldown(player, type)) {
            val remainingMillis = getCooldownTime(player, type) - System.currentTimeMillis()
            val remainingSeconds = remainingMillis / 1000.0
            val skillName = config!!.getString("skills.$type.name", type)
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent(ChatColor.translateAlternateColorCodes('&', "&c&l$skillName 阵法冷却中，剩余 ${String.format("%.1f", remainingSeconds)} 秒"))
            )
            return
        }

        // 3. 执行技能逻辑
        val success = skill.cast(player, level, config!!.getConfigurationSection("skills.$type"))

        if (success) {
            // 4. 计算冷却时间
            val baseCd = config!!.getDouble("skills.$type.levels.$level.cooldown", 5.0)
            var reduce = data.coolReduce
            if (reduce > 0.5) reduce = 0.5
            val finalCd = baseCd * (1.0 - reduce)

            // 【核心修改】应用两种冷却
            // A. 逻辑冷却 (插件内部判断用)
            setCooldown(player, type, finalCd)

            // B. 视觉冷却 (客户端转圈圈)
            // 这里不再对 Material 冷却，而是对 "元素组" 冷却
            setVisualCooldown(player, type, finalCd)

            // 水元素 Revelation/启示 触发冷却返还
            plugin.elementCrystalManager.triggerWaterSkill(player, "formation", type, finalCd)
            plugin.accessorySkillManager.onElementFormationCast(player, data)

            // 5. 发送提示消息
            val msg = config!!.getString("skills.$type.message")
            if (msg != null && msg.isNotEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("%player%", player.name)))
            }
            // ==========================================
            // 1. 构建带颜色代码的字符串 (使用 Kotlin 字符串模板更优雅)
            val rawMessage = "&6☯当前灵力值：&b${String.format("%.1f", data.lingli)} &6/ &b${String.format("%.0f", data.maxLingli)} &6☯"
            // 2. 使用 LegacyComponentSerializer 将 & 符号解析为真正的颜色组件
            val component = LegacyComponentSerializer.legacyAmpersand().deserialize(rawMessage)
            // 3. 直接发送给玩家
            player.sendActionBar(component)
        }
    }

    /**
     * 【新功能】设置视觉冷却组
     * 使用 Paper API 修改物品数据 + 发包通知客户端
     */
    private fun setVisualCooldown(player: Player, type: String, seconds: Double) {
        val item = player.inventory.itemInMainHand
        val groupKey = groupKeys[type] ?: return
        val ticks = (seconds * 20).toInt()
        try {
            // ================= 核心修改 =================
            // 【删除】了原本在这里动态组装 UseCooldown 并通过 item.setData 写入物品的代码。
            // 原因：物品在 ResourceManager 生成时已经自带了这个组件，再次修改会导致它和新物品无法堆叠。
            // ============================================
            // 【Reflect】直接发送冷却数据包
            // 客户端读取到数据包后，会自动匹配手里物品自带的 cooldownGroup 并产生视觉冷却动画
            sendPacketCooldown(player, groupKey, ticks)
        } catch (e: Exception) {
            plugin.logger.warning("设置视觉冷却失败: ${e.message}")
            e.printStackTrace()
            // 降级处理：如果失败，就用普通的材质冷却
            if (!item.type.isAir) player.setCooldown(item.type, ticks)
        }
    }

    /**
     * 发送 ClientboundCooldownPacket (依然需要反射，直到 Bukkit 增加 Key 版本的 setCooldown)
     */
    private fun sendPacketCooldown(player: Player, key: NamespacedKey, ticks: Int) {
        try {
            // 1. 获取 NMS Player Connection
            val craftPlayerMethod = player.javaClass.getMethod("getHandle")
            val nmsPlayer = craftPlayerMethod.invoke(player)
            val connectionField = nmsPlayer.javaClass.fields.firstOrNull {
                it.type.name.contains("ServerGamePacketListenerImpl") || it.name == "c" || it.name == "connection"
            } ?: throw NoSuchFieldException("No connection field")
            val connection = connectionField.get(nmsPlayer)

            // 2. 构造 ResourceLocation
            val resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation")
            val parseMethod = resourceLocationClass.getMethod("parse", String::class.java)
            val nmsKey = parseMethod.invoke(null, key.toString())

            // 3. 构造 Packet (ClientboundCooldownPacket)
            val packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundCooldownPacket")
            // 构造函数通常是 (ResourceLocation, int)
            val packetConstructor = packetClass.getConstructor(resourceLocationClass, Int::class.javaPrimitiveType)
            val packet = packetConstructor.newInstance(nmsKey, ticks)

            // 4. 发送
            val sendMethod = connection.javaClass.getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))
            sendMethod.invoke(connection, packet)

        } catch (e: Exception) {
            plugin.logger.warning("发送冷却包异常: ${e.message}")
        }
    }

    // === 逻辑冷却管理 (保持不变) ===

    fun isOnCooldown(player: Player, type: String): Boolean {
        if (!internalCooldowns.containsKey(player.uniqueId)) return false
        val pCds = internalCooldowns[player.uniqueId]!!
        if (!pCds.containsKey(type)) return false
        return pCds[type]!! > System.currentTimeMillis()
    }

    fun getCooldownTime(player: Player, type: String): Long {
        if (!internalCooldowns.containsKey(player.uniqueId)) return 0
        return internalCooldowns[player.uniqueId]!!.getOrDefault(type, 0L)
    }

    fun setCooldown(player: Player, type: String, seconds: Double) {
        val endTime = System.currentTimeMillis() + (seconds * 1000).toLong()
        internalCooldowns.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[type] = endTime
    }

    fun resetCooldown(player: Player, type: String) {
        if (internalCooldowns.containsKey(player.uniqueId)) {
            internalCooldowns[player.uniqueId]!!.remove(type)
        }
    }

    // 暴露 element_zf.yml 配置文件
    fun getConfig(): FileConfiguration? {
        return config
    }

    fun reduceCooldown(player: Player, type: String, seconds: Double) {
        val playerCds = internalCooldowns[player.uniqueId] ?: return
        val keyToUse = if (playerCds.containsKey(type.uppercase())) type.uppercase() else if (playerCds.containsKey(type.lowercase())) type.lowercase() else type
        val currentEnd = playerCds[keyToUse] ?: return
        val now = System.currentTimeMillis()
        if (currentEnd <= now) return

        val newEnd = currentEnd - (seconds * 1000.0).toLong()
        if (newEnd <= now) {
            playerCds.remove(keyToUse)
            setVisualCooldown(player, keyToUse, 0.0)
        } else {
            playerCds[keyToUse] = newEnd
            setVisualCooldown(player, keyToUse, (newEnd - now) / 1000.0)
        }
    }

    fun castEnhancedSkill(player: Player, type: String, data: PlayerData) {
        val skill = enhancedSkills[type] ?: return

        // 1. 检查冷却
        if (isOnCooldown(player, type)) {
            val remainingMillis = getCooldownTime(player, type) - System.currentTimeMillis()
            val remainingSeconds = remainingMillis / 1000.0
            val skillName = getEnhancedSkillName(type)
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent(ChatColor.translateAlternateColorCodes('&', "&c&l$skillName 阵法冷却中，剩余 ${String.format("%.1f", remainingSeconds)} 秒"))
            )
            return
        }

        // 2. 检查并消耗灵力
        val manaCost = 25.0
        if (data.lingli < manaCost) {
            player.sendMessage(ChatColor.RED.toString() + "您的灵力不足，需要 ${manaCost.toInt()} 点灵力")
            return
        }

        // 3. 执行技能逻辑
        if (!skill.cast(player, data)) return

        // 4. 扣除物品与灵力
        consumeOneElementFromMainHand(player)
        data.lingli -= manaCost
        plugin.databaseManager.queuePlayerSave(data)

        // 5. 应用冷却
        val baseCd = getEnhancedCooldown(type)
        var reduce = data.coolReduce
        if (reduce > 0.5) reduce = 0.5
        val finalCd = baseCd * (1.0 - reduce)

        setCooldown(player, type, finalCd)
        setVisualCooldown(player, type, finalCd)

        // 水元素 Revelation/启示 触发冷却返还
        plugin.elementCrystalManager.triggerWaterSkill(player, "formation", type, finalCd)
        plugin.accessorySkillManager.onElementFormationCast(player, data)

        // 6. 显示最新灵力
        val rawMessage = "&6☯当前灵力值：&b${String.format("%.1f", data.lingli)} &6/ &b${String.format("%.0f", data.maxLingli)} &6☯"
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(rawMessage))
    }

    private fun getEnhancedSkillName(type: String): String {
        return config?.getString("skills.$type.enhanced_name") ?: when (type.uppercase()) {
            "METAL" -> "暗云裂解"
            "WOOD" -> "魂灵契约"
            "WATER" -> "覆海"
            "FIRE" -> "炎蝶之舞"
            "EARTH" -> "岩星"
            else -> type
        }
    }

    private fun getEnhancedCooldown(type: String): Double {
        val normalizedType = type.uppercase()
        val fallback = when (normalizedType) {
            "METAL" -> 25.0
            "WOOD" -> 20.0
            "WATER" -> 25.0
            "FIRE" -> 30.0
            "EARTH" -> 40.0
            else -> 20.0
        }
        return config?.getDouble("skills.$normalizedType.enhanced_cooldown", fallback) ?: fallback
    }

    private fun consumeOneElementFromMainHand(player: Player) {
        val item = player.inventory.itemInMainHand
        if (!item.type.isAir) {
            item.amount = item.amount - 1
        }
    }
}
