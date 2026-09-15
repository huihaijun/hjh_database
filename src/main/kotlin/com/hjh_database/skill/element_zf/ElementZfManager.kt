package com.hjh_database.skill.element_zf

import com.hjh_database.Hjh_database
import com.hjh_database.client.CooldownItemTarget
import com.hjh_database.client.ItemCooldownVisual
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.impl.*
import com.hjh_database.spawner.impl.NorthWetnessSkill
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.UseCooldown
import net.kyori.adventure.key.Key
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ElementZfManager(private val plugin: Hjh_database) {
    private data class ReturnReceipt(
        val data: PlayerData,
        val multiplier: Double,
        val tick: Int = org.bukkit.Bukkit.getCurrentTick(),
        var element: ItemStack? = null,
        var restored: Double = 0.0,
        var baseMana: Double = 0.0
    )
    private val castingReceipts = mutableMapOf<UUID, ReturnReceipt>()
    private val returnReceipts = mutableMapOf<UUID, MutableMap<String, ReturnReceipt>>()

    fun recordConsumedElement(player: Player, item: ItemStack) {
        castingReceipts[player.uniqueId]?.element = item.clone().apply { amount = 1 }
    }

    /** 只记录阵法自身的实际回蓝，溢出上限的部分不追扣；金元素保留原有回蓝规则。 */
    fun restoreFormationMana(player: Player, amount: Double, capAtMax: Boolean = true) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val before = data.lingli
        if (amount > 0.0 && amount.isFinite()) {
            if (!capAtMax) data.lingli += amount
            else if (before < data.maxLingli) data.lingli = minOf(data.maxLingli, before + amount)
            castingReceipts[player.uniqueId]?.let {
                it.baseMana += amount
                it.restored += (data.lingli - before).coerceAtLeast(0.0)
            }
            plugin.databaseManager.queuePlayerSave(data)
        }
    }

    private fun suguiState(player: Player, type: String, data: PlayerData): SuguiReadiness {
        val receipt = returnReceipts[player.uniqueId]?.get(type) ?: return SuguiReadiness.UNAVAILABLE
        if (!isOnCooldown(player, type) || receipt.data !== data || player.isDead) return SuguiReadiness.UNAVAILABLE
        if (receipt.tick == org.bukkit.Bukkit.getCurrentTick()) return SuguiReadiness.SAME_TICK
        val item = receipt.element ?: return SuguiReadiness.UNAVAILABLE
        val furnace = plugin.playerManager.weaponManager.checkActiveWeapon(player, player.inventory.itemInOffHand, 40)
        val inventory = player.inventory
        return suguiReadiness(
            validReceipt = true,
            sameTick = false,
            activeFurnace = data.job == 2 && furnace?.reqJob == 2 && getActiveFurnaceRarity(player) != null,
            availableMana = data.lingli,
            debit = receipt.restored + receipt.baseMana * receipt.multiplier,
            canStoreElement = inventory.firstEmpty() != -1 || inventory.storageContents.any {
                it != null && it.isSimilar(item) && it.amount < minOf(it.maxStackSize, inventory.maxStackSize)
            }
        )
    }

    /** 只读资格检查；不消费记录、灵力或元素。 */
    fun canSugui(player: Player, type: String): Boolean {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return false
        return suguiState(player, type.uppercase(), data) == SuguiReadiness.READY
    }

    /** 返回 true 表示本次右键已由溯归处理；不会重放阵法或重置冷却。 */
    fun trySugui(player: Player, type: String, data: PlayerData): Boolean {
        val receipts = returnReceipts[player.uniqueId] ?: return false
        val receipt = receipts[type] ?: return false
        if (!isOnCooldown(player, type) || receipt.data !== data || player.isDead) {
            receipts.remove(type)
            return false
        }
        val debit = receipt.restored + receipt.baseMana * receipt.multiplier
        when (suguiState(player, type, data)) {
            SuguiReadiness.UNAVAILABLE -> return false
            SuguiReadiness.SAME_TICK -> return true
            SuguiReadiness.NO_MANA -> {
                player.sendActionBar("§c溯归灵力不足，需要 ${"%.1f".format(debit)} 点（含撤销回蓝）。")
                return true
            }
            SuguiReadiness.NO_SPACE -> {
                player.sendActionBar("§c请先在背包中留出空间，再发动溯归。")
                return true
            }
            SuguiReadiness.READY -> Unit
        }
        val item = receipt.element ?: return false
        val inventory = player.inventory
        receipts.remove(type) // 先撤销资格，确保同次施法至多返还一枚。
        data.lingli -= debit
        inventory.addItem(item.clone())
        plugin.databaseManager.queuePlayerSave(data)
        player.sendActionBar("§6§l【溯归】发动 本次阵法不消耗元素")
        playSuguiFeedback(player)
        return true
    }

    /** 半秒金色光环向身体收拢，只在结算成功后播放，不生成实体。 */
    private fun playSuguiFeedback(player: Player) {
        player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.65f, 1.6f)
        val world = player.world
        val dust = Particle.DustOptions(Color.fromRGB(255, 190, 65), 0.85f)
        object : BukkitRunnable() {
            private var frame = 0

            override fun run() {
                if (!player.isOnline || player.isDead || player.world != world) {
                    cancel()
                    return
                }
                val center = player.location.add(0.0, 0.6 + frame * 0.13, 0.0)
                val radius = 1.0 - frame * 0.22
                for (point in 0 until 12) {
                    val angle = point * Math.PI / 6.0 + frame * 0.35
                    player.spawnParticle(
                        Particle.DUST, center.clone().add(kotlin.math.cos(angle) * radius, 0.0, kotlin.math.sin(angle) * radius),
                        1, 0.0, 0.0, 0.0, 0.0, dust
                    )
                }
                if (++frame >= 5) {
                    player.spawnParticle(Particle.END_ROD, center, 8, 0.15, 0.2, 0.15, 0.015)
                    player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.65f, 1.8f)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    fun clearSugui(player: Player) {
        castingReceipts.remove(player.uniqueId)
        returnReceipts.remove(player.uniqueId)
    }
    val tierEffects = ElementFormationTierEffects(plugin)
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
        plugin.clientBridge.itemCooldowns.register("formation_sugui") { player ->
            groupKeys.mapNotNull { (type, group) ->
                if (!canSugui(player, type)) return@mapNotNull null
                ItemCooldownVisual(
                    type, CooldownItemTarget.group(group.toString()), getCooldownTime(player, type),
                    durationMillis = 1L, color = 0x7F8FD9FF, tintVanilla = true
                )
            }
        }
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

    /**
     * 获取玩家当前真正激活的术士法炉稀有度。
     *
     * 这里不直接读取物品上的 rarity NBT，而是复用武器激活检查，确保副手槽位、
     * 职业和使用等级均满足要求；同时用阵法强度排除其他可能存在的术士副手物品。
     */
    fun getActiveFurnaceRarity(player: Player): Int? {
        val furnace = plugin.playerManager.weaponManager.checkActiveWeapon(
            player,
            player.inventory.itemInOffHand,
            40
        ) ?: return null

        if (furnace.reqJob != 2 || furnace.stats.getOrDefault("zf_str", 0.0) <= 0.0) {
            return null
        }
        return furnace.rarity.coerceAtLeast(1)
    }

    /**
     * 普通元素阵法的实际效果等级不能超过当前激活法炉的稀有度。
     * 返回 0 表示没有可用的激活法炉，调用方不应继续释放阵法。
     */
    fun getEffectiveElementLevel(player: Player, learnedLevel: Int): Int {
        if (learnedLevel <= 0) return 0
        val furnaceRarity = getActiveFurnaceRarity(player) ?: return 0
        return minOf(learnedLevel, furnaceRarity)
    }

    fun castSkill(player: Player, type: String, data: PlayerData) {
        val skill = skills[type] ?: return

        // 1. 检查等级
        val learnedLevel = data.getElementLevel(type)
        if (learnedLevel <= 0) {
            player.sendMessage(ChatColor.RED.toString() + "你尚未领悟 " + type + " 阵法！")
            return
        }

        // 阵法升级等级仍完整保留，但本次释放的所有数值由激活法炉稀有度封顶。
        val level = getEffectiveElementLevel(player, learnedLevel)
        if (level <= 0) {
            player.sendMessage(ChatColor.RED.toString() + "请先在副手激活可用的法炉！")
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

        if (NorthWetnessSkill.tryInterruptSkill(player) {
                setCooldown(player, type, 5.0)
                setVisualCooldown(player, type, 5.0)
            }
        ) {
            return
        }

        // 3. 执行技能逻辑。风场先生成只读计划，技能成功后才提交状态。
        returnReceipts[player.uniqueId]?.remove(type)
        val furnace = plugin.playerManager.weaponManager.checkActiveWeapon(player, player.inventory.itemInOffHand, 40)!!
        val receipt = ReturnReceipt(data, furnace.suguiManaMultiplier)
        castingReceipts[player.uniqueId] = receipt
        val accessoryPlan = plugin.accessorySkillManager.prepareElementFormationCast(player)
        val success = try {
            skill.cast(player, level, config!!.getConfigurationSection("skills.$type"))
        } catch (throwable: Throwable) {
            plugin.accessorySkillManager.completeElementFormationCast(player, accessoryPlan, false)
            throw throwable
        } finally {
            castingReceipts.remove(player.uniqueId)
        }

        if (!success) {
            plugin.accessorySkillManager.completeElementFormationCast(player, accessoryPlan, false)
            return
        }

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
            plugin.accessorySkillManager.completeElementFormationCast(player, accessoryPlan, true)
            plugin.accessorySkillManager.onElementFormationCast(player, data)
            if (receipt.element != null && receipt.baseMana > 0.0 && isOnCooldown(player, type)) {
                returnReceipts.getOrPut(player.uniqueId) { mutableMapOf() }[type] = receipt
            }

            // 5. 发送提示消息
            val msg = config!!.getString("skills.$type.message")
            if (msg != null && msg.isNotEmpty()) {
                if (!plugin.passiveSubtitleManager.showCombatEvent(player, "sorcerer.formation.${type.lowercase()}")) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("%player%", player.name)))
                }
            }
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
        // 新施法或湿气惩罚开始后，旧施法的资格不能借用新的冷却复活。
        returnReceipts[player.uniqueId]?.remove(type)
        val endTime = System.currentTimeMillis() + (seconds * 1000).toLong()
        internalCooldowns.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[type] = endTime
    }

    fun resetCooldown(player: Player, type: String) {
        returnReceipts[player.uniqueId]?.remove(type.uppercase())
        if (internalCooldowns.containsKey(player.uniqueId)) {
            internalCooldowns[player.uniqueId]!!.remove(type)
        }
        // 逻辑冷却和客户端元素组动画必须同步清除，否则玩家会看到已经可用的阵法仍在转圈。
        setVisualCooldown(player, type.uppercase(), 0.0)
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
            returnReceipts[player.uniqueId]?.remove(keyToUse)
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

        // 2. 先建立风场计划，资源消耗维持原始阵法规则。
        returnReceipts[player.uniqueId]?.remove(type)
        val accessoryPlan = plugin.accessorySkillManager.prepareElementFormationCast(player)
        val manaCost = 25.0
        if (data.lingli < manaCost) {
            player.sendMessage(ChatColor.RED.toString() + "您的灵力不足，需要 ${manaCost.toInt()} 点灵力")
            plugin.accessorySkillManager.completeElementFormationCast(player, accessoryPlan, false)
            return
        }

        if (NorthWetnessSkill.tryInterruptSkill(player) {
                setCooldown(player, type, 5.0)
                setVisualCooldown(player, type, 5.0)
            }
        ) {
            plugin.accessorySkillManager.completeElementFormationCast(player, accessoryPlan, false)
            return
        }

        // 3. 执行技能逻辑
        val success = try {
            skill.cast(player, data)
        } catch (throwable: Throwable) {
            plugin.accessorySkillManager.completeElementFormationCast(player, accessoryPlan, false)
            throw throwable
        }
        if (!success) {
            plugin.accessorySkillManager.completeElementFormationCast(player, accessoryPlan, false)
            return
        }

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
        plugin.accessorySkillManager.completeElementFormationCast(player, accessoryPlan, true)
        plugin.accessorySkillManager.onElementFormationCast(player, data)

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

    fun shutdown() {
        castingReceipts.clear()
        returnReceipts.clear()
        tierEffects.shutdown()
    }

    private fun consumeOneElementFromMainHand(player: Player) {
        val item = player.inventory.itemInMainHand
        if (!item.type.isAir) {
            item.amount = item.amount - 1
        }
    }
}
