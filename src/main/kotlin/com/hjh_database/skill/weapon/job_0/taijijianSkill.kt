package com.hjh_database.skill.weapon.job_0

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class taijijianSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    enum class TaijiState {
        YANG, YIN
    }

    private data class TaijiData(
        var state: TaijiState = TaijiState.YANG,
        val yangSwords: MutableList<ItemDisplay> = ArrayList(),
        val yinSwords: MutableList<ItemDisplay> = ArrayList()
    ) {
        fun getTotalCount(): Int = yangSwords.size + yinSwords.size
    }

    private val playerDataMap = ConcurrentHashMap<UUID, TaijiData>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 全局特效同步任务 (1 Tick刷新)
        object : BukkitRunnable() {
            override fun run() {
                if (playerDataMap.isEmpty()) return
                // 遍历所有开启技能的玩家更新剑的位置
                val iter = playerDataMap.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    val player = Bukkit.getPlayer(entry.key)
                    if (player == null || !player.isOnline) {
                        // 玩家离线在 onQuit 处理，这里不做移除防止并发修改异常
                        continue
                    }
                    updateSwordPositions(player, entry.value)
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null) return false
        val uuid = player.uniqueId

        playerDataMap.putIfAbsent(uuid, TaijiData())
        val taijiData = playerDataMap[uuid]!!

        val manager = (plugin as com.hjh_database.Hjh_database).weaponSkillManager
        // 注册到管理器，确保切武器时能触发 deactivate
        manager?.registerToggle(player, "taijijian")

        if (taijiData.state == TaijiState.YANG) {
            taijiData.state = TaijiState.YIN
            notify(player, "&b&l☯ 切换至【太极·阴】")
            player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.5f)
        } else {
            taijiData.state = TaijiState.YANG
            notify(player, "&6&l☯ 切换至【太极·阳】")
            player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f)
        }

        player.world.spawnParticle(Particle.WAX_OFF, player.location.add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5)
        return true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager !is Player) return
        val taijiData = playerDataMap[damager.uniqueId] ?: return

        // 防止爆发伤害死循环
        if (event.entity.hasMetadata("HJH_TAIJI_FINISHER")) return

        val victim = event.entity as? LivingEntity ?: return

        if (taijiData.getTotalCount() >= 5) {
            triggerFinisher(damager, victim, taijiData, event.damage)
            return
        }

        addStack(damager, taijiData)
    }

    private fun addStack(player: Player, data: TaijiData) {
        val currentCount = data.getTotalCount()
        if (currentCount >= 5) return

        val swordItem = if (data.state == TaijiState.YANG) {
            ItemStack(Material.IRON_AXE)
        } else {
            ItemStack(Material.STONE_SWORD)
        }

        val display = player.world.spawn(player.location, ItemDisplay::class.java) { e ->
            e.setItemStack(swordItem)
            e.transformation = Transformation(
                Vector3f(0f, 0f, 0f),
                AxisAngle4f(0f, 0f, 0f, 1f),
                Vector3f(0.6f, 0.6f, 0.6f), // 稍微缩小一点
                AxisAngle4f(0f, 0f, 0f, 1f)
            )
            // 修复亮度报错：使用全类名或确保 import 正确
            // 这里为了稳妥不设置亮度，或者用 e.brightness = Display.Brightness(15, 15)
        }

        if (data.state == TaijiState.YANG) {
            data.yangSwords.add(display)
        } else {
            data.yinSwords.add(display)
        }

        // 更新属性
        updateAttributes(player, data)
        player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 2.0f)
    }

    // ★★★ 核心修复：更新属性到 tempBonuses 并刷新 ★★★
    private fun updateAttributes(player: Player, taijiData: TaijiData) {
        val pluginMain = plugin as com.hjh_database.Hjh_database
        val pData = pluginMain.playerManager.getData(player.uniqueId) ?: return

        val yangCount = taijiData.yangSwords.size
        val yinCount = taijiData.yinSwords.size

        // 1. 阳剑：攻击力百分比 (每把 10%)
        if (yangCount > 0) {
            pData.tempBonuses["attack_percent"] = yangCount * 0.1
        } else {
            pData.tempBonuses.remove("attack_percent")
        }

        // 2. 阴剑：最大生命百分比 (每把 10%)
        if (yinCount > 0) {
            pData.tempBonuses["max_health_percent"] = yinCount * 0.1
        } else {
            pData.tempBonuses.remove("max_health_percent")
        }

        // 3. 强制刷新面板 (这样菜单就能看到了)
        pluginMain.playerManager.updateStats(player)
    }

    private fun triggerFinisher(player: Player, victim: LivingEntity, taijiData: TaijiData, baseDamage: Double) {
        val pluginMain = plugin as com.hjh_database.Hjh_database
        val pData = pluginMain.playerManager.getData(player.uniqueId) ?: return

        val yangCount = taijiData.yangSwords.size
        val yinCount = taijiData.yinSwords.size

        // 此时玩家身上有高额属性加成 (pData.attack 是满buff状态)
        // 阳剑: 70%, 阴剑: 50%
        val damageMultiplier = (yangCount * 0.7) + (yinCount * 0.5)
        val extraDamage = pData.attack * damageMultiplier

        // 阴剑回血：本次总伤害的 10% * 阴剑数量
        // 这里我们用 计算出的额外伤害 + 基础伤害 作为基数
        val totalSnapshotDamage = baseDamage + extraDamage
        val healAmount = totalSnapshotDamage * (yinCount * 0.1)

        // ★★★ 核心修复：添加技能标签 & 清除无敌帧 ★★★
        victim.setMetadata("HJH_TAIJI_FINISHER", FixedMetadataValue(plugin, true))
        victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true)) // 穿透武器检查
        victim.noDamageTicks = 0 // 清除无敌帧，防止吞伤害

        try {
            victim.damage(extraDamage, player)
        } finally {
            victim.removeMetadata("HJH_TAIJI_FINISHER", plugin)
            if (victim.hasMetadata("hjh_physical_skill")) {
                victim.removeMetadata("hjh_physical_skill", plugin)
            }
            victim.noDamageTicks = 0
        }

        // 回血逻辑
        if (healAmount > 0) {
            val maxHp = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
            val newHealth = (player.health + healAmount).coerceAtMost(maxHp)
            player.health = newHealth
            player.sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§a§l☯ 阴阳调和 恢复生命: ${String.format("%.1f", healAmount)}"))
        }

        // 特效
        player.world.spawnParticle(Particle.SWEEP_ATTACK, victim.location.add(0.0, 1.0, 0.0), 5)
        player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.5f)

        // 清理所有状态
        clearAll(player, taijiData, pData)
    }

    private fun clearAll(player: Player, taijiData: TaijiData, pData: PlayerData?) {
        if (pData != null) {
            // 移除临时加成
            pData.tempBonuses.remove("attack_percent")
            pData.tempBonuses.remove("max_health_percent")
            // 刷新面板 (属性还原)
            val pluginMain = plugin as com.hjh_database.Hjh_database
            pluginMain.playerManager.updateStats(player)
        }

        // 清除实体
        taijiData.yangSwords.forEach { it.remove() }
        taijiData.yinSwords.forEach { it.remove() }
        taijiData.yangSwords.clear()
        taijiData.yinSwords.clear()
    }

    override fun deactivate(player: Player) {
        val taijiData = playerDataMap.remove(player.uniqueId) ?: return
        val pluginMain = plugin as com.hjh_database.Hjh_database
        val pData = pluginMain.playerManager.getData(player.uniqueId)

        clearAll(player, taijiData, pData)

        // 从管理器注销
        pluginMain.weaponSkillManager?.unregisterToggle(player)

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§7[太极] 剑意已散"))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        deactivate(event.player)
    }

    // === 视觉修复：垂直排列在身后 ===
    private fun updateSwordPositions(player: Player, data: TaijiData) {
        val total = data.getTotalCount()
        if (total == 0) return

        val allSwords = ArrayList<ItemDisplay>()
        allSwords.addAll(data.yangSwords)
        allSwords.addAll(data.yinSwords)

        // 1. 获取玩家身后的基准点
        val playerLoc = player.location
        val direction = playerLoc.direction.clone().setY(0).normalize() // 水平方向

        // 背后 0.6 格的位置 (太远了看不到，太近了穿模，0.6 比较合适)
        val baseLoc = playerLoc.clone().subtract(direction.multiply(0.6))

        // 2. 垂直排列参数
        val startHeight = 0.6 // 第一把剑的高度 (腰部附近)
        val gap = 0.45        // 每把剑的垂直间距

        for (i in 0 until total) {
            val sword = allSwords[i]
            if (!sword.isValid) continue

            // 计算目标位置：基准点 + 垂直高度
            val targetLoc = baseLoc.clone().add(0.0, startHeight + (i * gap), 0.0)

            // 设置朝向：跟随玩家的朝向，或者让剑尖朝下？
            // 这里设置为：位置在背后，旋转跟随玩家，看起来像是背着的
            targetLoc.yaw = playerLoc.yaw

            // 可以在这里微调 Transformation 让剑倒立
            // 目前 ItemDisplay 默认是物品拿在手里的角度，铁斧和剑通常是斜着的
            // 这里我们不做复杂矩阵变换，仅做位置同步

            sword.teleport(targetLoc)
        }
    }

    private fun notify(player: Player, msg: String) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', msg)))
    }
}