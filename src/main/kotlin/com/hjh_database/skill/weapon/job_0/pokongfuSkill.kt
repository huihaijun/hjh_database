package com.hjh_database.skill.weapon.job_0

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class pokongfuSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)
    private val armorKey = NamespacedKey(plugin, "hjh_mob_armor")

    // 1. 蓄力状态
    private val primedPlayers = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())

    // 2. 锁定状态
    private val playerLocks = ConcurrentHashMap<UUID, Pair<UUID, Long>>()

    // 3. 怪物身上的撕裂数据
    // ★★★ 修改 1：在数据类中增加 bleedingTaskId，记录当前是否正在流血 ★★★
    private data class StackInfo(
        var stacks: Int,
        var expireTaskId: Int,
        var bleedingTaskId: Int = -1 // -1 代表没有在流血
    )
    private val mobStackMap = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, StackInfo>>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || config == null) return false

        primedPlayers.add(player.uniqueId)

        val message = config.getString("message", "&a&l武器技【裂空】发动！")
        if (!message.isNullOrEmpty()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
        }
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_THUNDER, 1f, 1.2f)

        player.world.spawnParticle(Particle.CRIT, player.location.add(0.0, 1.0, 0.0), 10)

        object : BukkitRunnable() {
            override fun run() {
                primedPlayers.remove(player.uniqueId)
            }
        }.runTaskLater(plugin, 300L)

        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager !is Player) return
        val victim = event.entity as? LivingEntity ?: return

        // 防止死循环的内部标记检查
        if (victim.hasMetadata("HJH_POKONGFU_INTERNAL")) {
            return
        }

        if (!victim.scoreboardTags.contains("panling") || !victim.scoreboardTags.contains("monster")) return

        val now = System.currentTimeMillis()

        // === 逻辑 A：第一刀触发 ===
        if (primedPlayers.contains(damager.uniqueId)) {
            primedPlayers.remove(damager.uniqueId)
            playerLocks[damager.uniqueId] = Pair(victim.uniqueId, now + 7000)
            applyRendStack(damager, victim)

            victim.world.playSound(victim.location, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1f, 1.5f)
            return
        }

        // === 逻辑 B：后续平A叠层 ===
        val lockInfo = playerLocks[damager.uniqueId]
        if (lockInfo != null) {
            if (now > lockInfo.second) {
                playerLocks.remove(damager.uniqueId)
                return
            }
            if (lockInfo.first == victim.uniqueId) {
                applyRendStack(damager, victim)
            }
        }
    }

    private fun applyRendStack(player: Player, victim: LivingEntity) {
        val mobId = victim.uniqueId
        val playerId = player.uniqueId

        mobStackMap.putIfAbsent(mobId, ConcurrentHashMap())
        val playerMap = mobStackMap[mobId]!!

        var info = playerMap[playerId]
        if (info == null) {
            info = StackInfo(0, -1, -1)
            playerMap[playerId] = info
        }

        // 重置叠层过期时间（只要攻击就续杯）
        if (info.expireTaskId != -1) {
            Bukkit.getScheduler().cancelTask(info.expireTaskId)
        }

        if (info.stacks < 5) {
            info.stacks++
        }

        // 更新 PDC 护甲
        updateMobArmorPDC(victim, playerMap)

        // 视觉特效：滴落岩浆
        victim.world.spawnParticle(
            Particle.DRIPPING_LAVA,
            victim.location.add(0.0, 1.2, 0.0),
            5, 0.2, 0.3, 0.2, 0.0
        )

        // ★★★ 修改 2：核心判定逻辑 ★★★
        // 只有当 (满5层) 且 (当前没有在流血) 时，才启动新任务
        if (info.stacks >= 5) {
            if (info.bleedingTaskId == -1) {
                startBleedingTask(player, victim, info)
            }
            // 如果 info.bleedingTaskId != -1，说明正在流血，
            // 此时我们只更新了 stacks 和 expireTime，不会触发新的伤害任务。
        }

        // 设定 Stack 过期任务 (如果10秒不打，层数消失)
        val taskId = object : BukkitRunnable() {
            override fun run() {
                playerMap.remove(playerId)
                if (playerMap.isEmpty()) {
                    mobStackMap.remove(mobId)
                    restoreOriginalArmor(victim)
                } else {
                    if (victim.isValid) {
                        updateMobArmorPDC(victim, playerMap)
                    }
                }
            }
        }.runTaskLater(plugin, 100L).taskId // 5秒 (100 ticks) 无攻击则重置

        info.expireTaskId = taskId
    }

    private fun updateMobArmorPDC(victim: LivingEntity, playerMap: Map<UUID, StackInfo>) {
        val pdc = victim.persistentDataContainer

        if (!victim.hasMetadata("HJH_BASE_ARMOR")) {
            val currentArmor = pdc.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0
            victim.setMetadata("HJH_BASE_ARMOR", FixedMetadataValue(plugin, currentArmor))
        }

        val baseArmor = victim.getMetadata("HJH_BASE_ARMOR")[0].asDouble()

        var maxStacks = 0
        for (info in playerMap.values) {
            maxStacks = max(maxStacks, info.stacks)
        }

        val reductionRatio = maxStacks * 0.1
        var newArmor = baseArmor * (1.0 - reductionRatio)
        if (newArmor < 0) newArmor = 0.0

        pdc.set(armorKey, PersistentDataType.DOUBLE, newArmor)
    }

    private fun restoreOriginalArmor(victim: LivingEntity) {
        if (victim.isValid && !victim.isDead && victim.hasMetadata("HJH_BASE_ARMOR")) {
            val baseArmor = victim.getMetadata("HJH_BASE_ARMOR")[0].asDouble()
            victim.persistentDataContainer.set(armorKey, PersistentDataType.DOUBLE, baseArmor)
            victim.removeMetadata("HJH_BASE_ARMOR", plugin)
        }
    }

    // ★★★ 修改 3：传入 info 对象，以便在任务结束时重置标记 ★★★
    private fun startBleedingTask(player: Player, victim: LivingEntity, info: StackInfo) {
        val data = (plugin as com.hjh_database.Hjh_database).playerManager.getData(player.uniqueId) ?: return
        // 【修改 1】伤害倍率从 10% (0.1) 提升至 20% (0.2)
        val damagePerTick = data.attack * 0.2
        val task = object : BukkitRunnable() {
            var count = 0
            val maxCount = 10 // 5秒内，0.5秒一次，共10次
            override fun run() {
                // 停止条件：怪物死/消失，或者次数跑完
                if (!victim.isValid || victim.isDead || count >= maxCount) {
                    info.bleedingTaskId = -1 // ★ 任务结束，重置标记，允许下一次触发
                    this.cancel()
                    return
                }
                // 标记设置
                victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
                victim.setMetadata("HJH_POKONGFU_INTERNAL", FixedMetadataValue(plugin, true))
                // 【修改 2 - 前置清除】
                // 确保这次流血伤害能打进去（不被之前的平A无敌帧挡住）
                victim.noDamageTicks = 0
                try {
                    victim.damage(damagePerTick, player)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // 清理所有标记
                    if (victim.hasMetadata("hjh_physical_skill")) {
                        victim.removeMetadata("hjh_physical_skill", plugin)
                    }
                    if (victim.hasMetadata("HJH_POKONGFU_INTERNAL")) {
                        victim.removeMetadata("HJH_POKONGFU_INTERNAL", plugin)
                    }
                    // 【修改 2 - 后置清除 - 关键！】
                    // Minecraft机制：造成伤害后怪物会自动获得0.5秒红色无敌时间。
                    // 必须在这里立刻将其归零，否则这0.5秒内玩家的平A会被系统判定无效（吞伤害）。
                    victim.noDamageTicks = 0
                }
                victim.world.playSound(victim.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 2.0f)
                count++
            }
        }
        // 启动任务并保存 ID
        task.runTaskTimer(plugin, 10L, 10L)
        info.bleedingTaskId = task.taskId
    }

    override fun deactivate(player: Player) {
        val playerId = player.uniqueId
        primedPlayers.remove(playerId)
        playerLocks.remove(playerId)

        val mobIterator = mobStackMap.iterator()
        while (mobIterator.hasNext()) {
            val entry = mobIterator.next()
            val info = entry.value.remove(playerId) ?: continue
            if (info.expireTaskId != -1) Bukkit.getScheduler().cancelTask(info.expireTaskId)
            if (info.bleedingTaskId != -1) Bukkit.getScheduler().cancelTask(info.bleedingTaskId)

            val victim = Bukkit.getEntity(entry.key) as? LivingEntity
            if (entry.value.isEmpty()) {
                mobIterator.remove()
                if (victim != null) restoreOriginalArmor(victim)
            } else if (victim != null && victim.isValid) {
                updateMobArmorPDC(victim, entry.value)
            }
        }
    }
}
