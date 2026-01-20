package com.hjh_database.skill.element_zf

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.impl.*
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.UseCooldown
import net.kyori.adventure.key.Key
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

            // 5. 发送提示消息
            val msg = config!!.getString("skills.$type.message")
            if (msg != null && msg.isNotEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("%player%", player.name)))
            }
        }
    }

    /**
     * 【新功能】设置视觉冷却组
     * 使用 Paper API 修改物品数据 + 发包通知客户端
     */
    private fun setVisualCooldown(player: Player, type: String, seconds: Double) {
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return

        val groupKey = groupKeys[type] ?: return
        val ticks = (seconds * 20).toInt()

        try {
            // 1. 【Paper API】给物品打上 "冷却组" 标签
            // 这一步完全是原生的，不用反射了！
            // UseCooldown 组件参数: (默认秒数, 组Key)
            // 我们把默认秒数设为 0，因为我们想手动控制冷却时间
            val cooldownComponent = UseCooldown.useCooldown(0.1f) // 1. 创建 Builder，设置默认冷却时间
                .cooldownGroup(Key.key(groupKey.toString()))      // 2. 设置冷却组
                .build()

            // 设置组件数据
            item.setData(DataComponentTypes.USE_COOLDOWN, cooldownComponent)

            // 必须把修改后的物品放回玩家手里 (物品是不可变的，setData会返回新的数据但我们要确保ItemStack对象更新)
            // *注意：Paper的API通常直接修改ItemStack，但为了保险起见建议重新set
            player.inventory.setItemInMainHand(item)

            // 2. 【Reflect】发送冷却数据包
            // 虽然有了 Paper API 改数据，但 Bukkit 目前还没有 player.setCooldown(Key) 的方法
            // 所以触发视觉效果还是得发包
            sendPacketCooldown(player, groupKey, ticks)

        } catch (e: Exception) {
            plugin.logger.warning("设置视觉冷却失败: ${e.message}")
            e.printStackTrace()
            // 降级处理：如果失败，就用普通的材质冷却
            player.setCooldown(item.type, ticks)
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
}