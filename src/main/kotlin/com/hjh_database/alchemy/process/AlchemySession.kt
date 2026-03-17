package com.hjh_database.alchemy.process

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.Vector3f
import kotlin.math.min

class AlchemySession(
    private val plugin: Hjh_database,
    private val player: Player,
    private val cauldronLoc: Location,
    private val recipe: AlchemyRecipe,
    private val tier: AlchemyTier
) {
    // 进度追踪器类
    private data class RequirementTracker(
        val template: ItemStack, // 配方要求的样板物品
        val totalNeeded: Int,    // 总共需要多少个
        var current: Int = 0     // 当前已经扔进去多少个
    )

    private val trackers = ArrayList<RequirementTracker>()
    private var displayEntity: TextDisplay? = null
    private var task: BukkitRunnable? = null

    val config = recipe.tierData[tier]!!
    val resultItem = config.result

    init {
        // 初始化追踪器
        val config = recipe.tierData[tier]!!
        for (ing in config.ingredients) {
            // 这里将配方里的 Item 分拆成追踪对象
            // 注意：配方里可能有多个相同的物品占位，或者堆叠的物品
            // 我们简单处理：每个 ItemStack 视为一种需求
            trackers.add(RequirementTracker(ing.clone(), ing.amount, 0))
        }
    }

    fun start() {
        val config = recipe.tierData[tier]!!
        val resultName = config.result.itemMeta?.displayName ?: "未知丹药"
        player.sendMessage("§a[炼药] §f开始炼制 §e${resultName} (${tier.displayName})")
        player.sendMessage("§7请将材料丢入锅中... (离开5格将自动取消)")

        // 生成悬浮文字
        val displayLoc = cauldronLoc.clone().add(0.5, 1.5, 0.5)
        displayEntity = player.world.spawn(displayLoc, TextDisplay::class.java) { e ->
            e.text(Component.text("§e正在准备..."))
            e.billboard = org.bukkit.entity.Display.Billboard.CENTER
            // 放大一点显示
            e.transformation = Transformation(
                Vector3f(0f, 0f, 0f),
                org.joml.AxisAngle4f(0f, 0f, 0f, 1f),
                Vector3f(1f, 1f, 1f),
                org.joml.AxisAngle4f(0f, 0f, 0f, 1f)
            )
            // 添加标签方便清理
            e.addScoreboardTag("hjh_alchemy_display")
        }

        task = object : BukkitRunnable() {
            override fun run() {
                // 1. 玩家状态检查
                if (!player.isOnline || player.isDead) {
                    this@AlchemySession.cancel()
                    return
                }

                // 2. 距离检查 ( > 5格中断)
                if (player.location.distance(cauldronLoc) > 5.0) {
                    player.sendMessage("§c[炼药] 你离炼药锅太远，炼制中断！")
                    this@AlchemySession.cancel()
                    return
                }

                // 3. 扫描并处理物品
                scanAndPickupItems()

                // 4. 更新悬浮文字 (实时进度)
                updateDisplayText()

                // 5. 检查是否全部完成
                if (trackers.all { it.current >= it.totalNeeded }) {
                    finish()
                }
            }
        }
        task?.runTaskTimer(plugin, 0L, 5L)
    }

    private fun updateDisplayText() {
        val config = recipe.tierData[tier]!!
        val resultName = config.result.itemMeta?.displayName ?: "未知丹药"
        val sb = StringBuilder("§6正在炼制: §b${resultName}\n§f材料进度:\n")

        for (t in trackers) {
            // 获取显示的名称 (优先用 DisplayName)
            val name = t.template.itemMeta?.displayName ?: t.template.type.name
            // 颜色逻辑：如果满了显示绿色，没满显示红色
            val color = if (t.current >= t.totalNeeded) "§a" else "§c"
            sb.append("$color- $name: ${t.current}/${t.totalNeeded}\n")
        }

        displayEntity?.text(Component.text(sb.toString()))
    }

    private fun scanAndPickupItems() {
        val entities = cauldronLoc.world.getNearbyEntities(cauldronLoc.clone().add(0.5, 0.5, 0.5), 1.0, 1.0, 1.0)

        for (entity in entities) {
            if (entity is Item) {
                val droppedItem = entity.itemStack

                // 忽略本插件的成品丹药 (防止误投)
                if (plugin.alchemyManager.isAlchemyItem(droppedItem)) continue

                var matched = false

                // 遍历所有需求，看看这个掉落物是不是我们需要的
                for (tracker in trackers) {
                    // 如果这个需求已经满了，跳过
                    if (tracker.current >= tracker.totalNeeded) continue

                    // 核心判定：使用 isSimilar 严格对比 (材质、NBT、Lore等)
                    if (droppedItem.isSimilar(tracker.template)) {
                        matched = true

                        // 计算还需要多少
                        val needed = tracker.totalNeeded - tracker.current
                        // 计算实际能拿多少 (取 掉落数量 和 需求缺口 的最小值)
                        val take = min(droppedItem.amount, needed)

                        if (take > 0) {
                            // 扣除掉落物数量
                            droppedItem.amount -= take
                            // 增加进度
                            tracker.current += take

                            // 音效
                            player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
                            player.playSound(player.location, Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 1f, 1f)
                        }

                        // 只要匹配上了，就不再继续匹配其他 tracker (防止一个物品填多个坑)
                        break
                    }
                }

                // 物品处理逻辑
                if (matched) {
                    // 如果数量扣完了，移除实体；否则更新剩余数量
                    if (droppedItem.amount <= 0) {
                        entity.remove()
                    } else {
                        entity.itemStack = droppedItem
                    }
                } else {
                    // === 修改点 4：如果不匹配 (不是配方所需，或者已经不需要了) ===
                    // 自动返还给玩家并提示
                    // 为了防止在这个 scan 频率下刷屏，我们可以加个简单判断或者只提示一次
                    // 这里直接返还

                    // 先移除地上实体
                    entity.remove()

                    // 尝试加回玩家背包
                    val leftOver = player.inventory.addItem(droppedItem)
                    if (leftOver.isNotEmpty()) {
                        // 背包满了，只好丢在玩家脚下
                        player.world.dropItem(player.location, droppedItem)
                    }

                    player.sendMessage("§c这并不是配方所需的材料！(已返还)")
                    player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.5f)
                }
            }
        }
    }

    fun cancel() {
        task?.cancel()
        displayEntity?.remove() // 移除悬浮字
        displayEntity = null

        // === 修改点 4：按进度返还材料 ===
        var refunded = false
        for (t in trackers) {
            if (t.current > 0) {
                val refundItem = t.template.clone()
                refundItem.amount = t.current // 把已经吃进去的数量吐出来

                val left = player.inventory.addItem(refundItem)
                // 背包满则丢地上
                if (left.isNotEmpty()) {
                    left.values.forEach { player.world.dropItem(player.location, it) }
                }
                refunded = true
            }
        }

        if (refunded) {
            player.sendMessage("§c已中断炼制，并返还了投入的材料。")
        }

        plugin.alchemyManager.activeSessions.remove(player.uniqueId)
    }

    private fun finish() {
        task?.cancel()
        displayEntity?.remove() // 移除悬浮字
        displayEntity = null

        val resultItem = recipe.tierData[tier]!!.result

        // 【新增】计算并给予冶药经验
        val playerData = plugin.playerManager.getPlayerData(player)
        if (playerData != null) {
            // 【修复 1：经验获取逻辑】直接从你的成品读取 danyao.yml 的 base_exp
            val resourceId = resultItem.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "resource_id"), PersistentDataType.STRING)
            val resourceData = if (resourceId != null) plugin.resourceManager.getLocalResource(resourceId) else null

            // 读取 base_exp，如果没有配置默认给 5 点
            val totalExp = resourceData?.baseExp ?: 5

            if (totalExp > 0) {
                playerData.addAlchemyExp(totalExp)
                player.sendMessage("§a[冶药] 获得冶药心得 §e+$totalExp §a(Lv.${playerData.alchemyLevel})")
                // 记得异步保存数据
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    try {
                        plugin.databaseManager.dataSource?.connection?.use { conn ->
                            plugin.databaseManager.saveAlchemyData(conn, playerData)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                })
            }
        }

        // 消息与音效
        val resultName = resultItem.itemMeta?.displayName ?: "未知丹药"
        player.sendMessage("§a[炼药] 炼制成功！获得 $resultName")
        player.playSound(player.location, Sound.BLOCK_BREWING_STAND_BREW, 1f, 1f)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 2f)

        // 【修复 2：防止成品数量减少】发放物品时，必须 clone()！
        val itemToGive = resultItem.clone()
        val leftovers = player.inventory.addItem(itemToGive)
        // 如果背包满了，掉落在地上
        for (leftover in leftovers.values) {
            player.world.dropItem(player.location, leftover)
        }

        // === 修改点 5：粒子特效 (白烟) ===
        // 在锅上方一点生成云雾粒子
        player.world.spawnParticle(
            Particle.CLOUD,
            cauldronLoc.clone().add(0.5, 0.8, 0.5),
            20,  // 数量
            0.3, 0.2, 0.3, // x,y,z 扩散范围
            0.05 // 速度
        )

        plugin.alchemyManager.activeSessions.remove(player.uniqueId)
    }
}