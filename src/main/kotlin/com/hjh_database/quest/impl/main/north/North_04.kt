package com.hjh_database.quest.impl.main.north

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Hopper
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class North_04 : QuestBase("main_north_4", "[主线]唤醒玄武珊瑚", QuestType.MAIN, 22), Listener {

    override val raceLimit: Int? = null
    override val requiredCompletedQuestIds = setOf("main_north_3")

    override val description = listOf(
        "§7祭司找到了唤醒玄武珊瑚的方法。",
        "§7以金木水火土五行之力启动唤生阵法，",
        "§7再让玄武洞的门锁认可你的气息。"
    )

    private val plugin = Hjh_database.instance
    private val resourceKey = NamespacedKey(plugin, "resource_id")
    private val talkProgress = HashMap<UUID, Int>()
    private val activeRituals = ConcurrentHashMap<UUID, CoralRitual>()
    private val lastLockFailMessage = ConcurrentHashMap<UUID, Long>()

    private val altarContainers = listOf(
        AltarContainer(-341, 18, -694, "metal", "金色", Particle.DustOptions(Color.fromRGB(255, 215, 40), 1.15f)),
        AltarContainer(-342, 18, -696, "wood", "绿色", Particle.DustOptions(Color.fromRGB(70, 205, 80), 1.15f)),
        AltarContainer(-340, 18, -698, "water", "蓝色", Particle.DustOptions(Color.fromRGB(40, 180, 255), 1.15f)),
        AltarContainer(-338, 18, -697, "fire", "红色", Particle.DustOptions(Color.fromRGB(255, 65, 30), 1.15f)),
        AltarContainer(-339, 18, -695, "earth", "土色", Particle.DustOptions(Color.fromRGB(190, 125, 45), 1.15f))
    )

    private val scriptRevival = listOf(
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f太好了！我方才翻遍前人留下的典籍，总算找到了激活这块珊瑚的法子。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f书上说，只需将§e五种元素各取一枚§f，分别放入角落那处祭台里§e对应颜色的容器正中§f。放好之后，拉动祭台外任意一根§e拉杆§f，便能启动唤生阵法。届时将这块珊瑚置于那§e金色方块§f之上，它便会自行吸纳五行之力，重新焕发生机。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f更巧的是，书中还记载——§e成功唤新珊瑚之人§f，便可获得激活§b玄武神庙§f入口门锁的资格。如此一来，你想进神庙的愿望，自然也就实现了。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f……什么？你问我为何之前还要你去抓鱼？咳，这不是得先看看你的本事嘛。连几条鲑鱼都搞不定，我怎么敢把这么要紧的事托付给你？",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f好了好了，快去试试吧。成功了拿回来让我瞧瞧。"
    )

    private val scriptAwakened = listOf(
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f对！没错！就是它！这股沉稳而厚重的力量——正是§4§n玄武大人§f的气息！",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f快，带着这块珊瑚前往§b玄武洞口§f，将它放入门锁之中。自此之后，这洞门便认了你了。日后想进入神庙，只需用§e右手轻抚洞口的门锁§f，便能通行无阻。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f§4§n玄武大人§f的试炼想必非同小可。愿你也能如先前一般，顺利通过。我在这里等你的好消息。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f若你通过了§4§n玄武试炼§f，就尽快回来找我。我还有些关于北方湖泊的事要告诉你。"
    )

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getScheduler().runTask(plugin, Runnable { secureLoadedXuanwuLock() })
    }

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与水族祭司 §e洛禾 §c对话")
        1 -> listOf(
            "§c将金、木、水、火、土元素各一枚",
            "§c依次放入对应颜色漏斗的 §e第三格§c，再拉动祭台外的拉杆"
        )
        2 -> listOf("§c将 §e失活的珊瑚 §c丢到祭台中央的金色方块上")
        3 -> listOf("§c主手持有 §b焕发生机的珊瑚 §c回去找祭司洛禾")
        4 -> listOf("§c将焕发生机的珊瑚放入 §b玄武洞口的门锁")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        if (npcId != StoryNpcs.SHUIZUJISI.id) return false

        return when (currentProgress) {
            0 -> {
                playDialogue(player, scriptRevival) {
                    giveQuestItem(player, INACTIVE_CORAL_ID, 1, Material.DEAD_TUBE_CORAL, "§7失活的珊瑚")
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] 将五种元素各一枚放入对应容器的第三格，再拉动祭台外的拉杆。")
                }
                true
            }

            1 -> {
                player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f五种元素要放在对应颜色容器的正中，也就是第三格。放好后拉动外面的拉杆。")
                true
            }

            2 -> {
                player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f阵法已经启动，把失活的珊瑚丢到祭台中央的金色方块上。")
                true
            }

            3 -> {
                if (!isAwakenedCoral(player.inventory.itemInMainHand)) {
                    player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f珊瑚唤醒了吗？拿在主手上让我瞧瞧。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    return true
                }

                playDialogue(player, scriptAwakened) {
                    plugin.questManager.updateProgress(player, id, 4)
                    player.sendMessage("§e[任务提醒] §f必须将焕发生机的珊瑚放入玄武洞口的门锁，才能完成任务。")
                }
                true
            }

            4 -> {
                player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f快去玄武洞口，把焕发生机的珊瑚放入门锁吧。")
                true
            }

            else -> false
        }
    }

    private fun playDialogue(player: Player, script: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)
        if (index >= script.size) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)
        if (index == script.lastIndex) {
            talkProgress.remove(player.uniqueId)
            onFinish()
        } else {
            talkProgress[player.uniqueId] = index + 1
        }
    }

    @EventHandler
    fun onAltarLever(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.LEVER || !isAltarLever(block.location)) return

        val player = event.player
        if (!isQuestAtProgress(player, 1)) return

        val invalid = validateAltarContents()
        if (invalid != null) {
            player.sendMessage("§c[唤生阵法] ${invalid.colorName}容器的第三格需要放入一枚§e${elementName(invalid.resourceId)}§c。")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.75f)
            return
        }

        consumeAltarElements()
        plugin.questManager.updateProgress(player, id, 2)

        val center = altarSurface()
        center.world?.spawnParticle(Particle.ENCHANT, center, 80, 0.8, 0.25, 0.8, 0.35)
        center.world?.spawnParticle(Particle.END_ROD, center, 30, 0.65, 0.25, 0.65, 0.08)
        center.world?.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 0.8f)
        player.sendMessage("§a[唤生阵法] §f五行之力已经汇聚。请将§e失活的珊瑚§f扔到金色方块上，开始唤生。")
    }

    private fun validateAltarContents(): AltarContainer? {
        val world = Bukkit.getWorld(WORLD_NAME) ?: return altarContainers.first()
        return altarContainers.firstOrNull { container ->
            val hopper = world.getBlockAt(container.x, container.y, container.z).state as? Hopper
            getResourceId(hopper?.inventory?.getItem(HOPPER_CENTER_SLOT)) != container.resourceId
        }
    }

    private fun consumeAltarElements() {
        val world = Bukkit.getWorld(WORLD_NAME) ?: return
        altarContainers.forEach { container ->
            val hopper = world.getBlockAt(container.x, container.y, container.z).state as? Hopper ?: return@forEach
            val item = hopper.inventory.getItem(HOPPER_CENTER_SLOT) ?: return@forEach
            if (item.amount <= 1) {
                hopper.inventory.setItem(HOPPER_CENTER_SLOT, null)
            } else {
                item.amount -= 1
            }
        }
    }

    @EventHandler
    fun onAltarInventoryMove(event: InventoryMoveItemEvent) {
        if (isAltarInventory(event.source.location) || isAltarInventory(event.destination.location)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onAltarInventoryPickup(event: InventoryPickupItemEvent) {
        if (isAltarInventory(event.inventory.location)) event.isCancelled = true
    }

    @EventHandler
    fun onDropInactiveCoral(event: PlayerDropItemEvent) {
        val player = event.player
        if (!isQuestAtProgress(player, 2)) return
        if (getResourceId(event.itemDrop.itemStack) != INACTIVE_CORAL_ID) return

        val center = altarSurface()
        if (player.world.name != WORLD_NAME || event.itemDrop.location.distanceSquared(center) > ALTAR_DROP_RADIUS_SQUARED) {
            player.sendMessage("§c[提示] 请靠近祭台中央的金色方块，再丢出失活的珊瑚。")
            return
        }
        if (activeRituals.containsKey(player.uniqueId)) {
            event.isCancelled = true
            player.sendMessage("§7唤生仪式已经在进行了。")
            return
        }

        beginCoralRitual(player, event.itemDrop)
    }

    private fun beginCoralRitual(player: Player, dropped: Item) {
        val center = altarSurface()
        dropped.teleport(center.clone().add(0.0, 0.15, 0.0))
        dropped.setGravity(false)
        dropped.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
        dropped.pickupDelay = Int.MAX_VALUE
        dropped.isInvulnerable = true

        val ritual = CoralRitual(dropped.uniqueId)
        activeRituals[player.uniqueId] = ritual
        player.sendMessage("§b[唤生阵法] §f珊瑚缓缓升起，五行之力开始向它汇聚……")
        center.world?.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.1f, 0.75f)

        ritual.task = object : BukkitRunnable() {
            private var ticks = 0

            override fun run() {
                val itemEntity = Bukkit.getEntity(ritual.itemEntityId) as? Item
                if (!player.isOnline || itemEntity == null || !itemEntity.isValid) {
                    abortRitual(player, refundCoral = player.isOnline)
                    return
                }

                ticks += RITUAL_PERIOD_TICKS.toInt()
                animateRisingCoral(itemEntity, ticks)
                animateElementStreams(itemEntity.location, ticks)

                if (ticks == CORAL_TRANSFORM_TICK) {
                    itemEntity.itemStack = ItemStack(Material.TUBE_CORAL)
                    itemEntity.world.spawnParticle(Particle.TOTEM_OF_UNDYING, itemEntity.location, 35, 0.3, 0.3, 0.3, 0.08)
                    itemEntity.world.playSound(itemEntity.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.25f)
                }

                if (ticks >= RITUAL_DURATION_TICKS) {
                    finishCoralRitual(player, itemEntity)
                }
            }
        }.runTaskTimer(plugin, RITUAL_PERIOD_TICKS, RITUAL_PERIOD_TICKS)
    }

    private fun animateRisingCoral(item: Item, ticks: Int) {
        val start = altarSurface().clone().add(0.0, 0.15, 0.0)
        val rise = (ticks.toDouble() / CORAL_RISE_TICKS).coerceIn(0.0, 1.0)
        val awakenedFloat = sin(ticks * 0.16) * 0.12 * rise
        item.teleport(start.clone().add(0.0, CORAL_FLOAT_HEIGHT * rise + awakenedFloat, 0.0))

        val angle = ticks * 0.18
        val orbit = item.location.clone().add(cos(angle) * 0.45, 0.05, sin(angle) * 0.45)
        item.world.spawnParticle(Particle.END_ROD, orbit, 2, 0.03, 0.03, 0.03, 0.01)
        item.world.spawnParticle(Particle.ENCHANT, item.location, 5, 0.3, 0.25, 0.3, 0.1)
    }

    private fun animateElementStreams(target: Location, ticks: Int) {
        val travel = (ticks % ELEMENT_STREAM_CYCLE_TICKS).toDouble() / ELEMENT_STREAM_CYCLE_TICKS
        altarContainers.forEach { container ->
            val source = Location(target.world, container.x + 0.5, container.y + 1.05, container.z + 0.5)
            val particlePoint = source.clone().add(target.clone().subtract(source).toVector().multiply(travel))
            target.world?.spawnParticle(Particle.DUST, particlePoint, 3, 0.04, 0.04, 0.04, 0.0, container.dust)
        }
    }

    private fun finishCoralRitual(player: Player, itemEntity: Item) {
        activeRituals.remove(player.uniqueId)?.task?.cancel()
        itemEntity.world.spawnParticle(Particle.TOTEM_OF_UNDYING, itemEntity.location, 65, 0.45, 0.5, 0.45, 0.12)
        itemEntity.world.spawnParticle(Particle.END_ROD, itemEntity.location, 40, 0.4, 0.45, 0.4, 0.08)
        itemEntity.world.playSound(itemEntity.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.15f)
        itemEntity.remove()

        giveQuestItem(player, AWAKENED_CORAL_ID, 1, Material.TUBE_CORAL, "§b焕发生机的珊瑚")
        plugin.questManager.updateProgress(player, id, 3)
        player.sendMessage("§a[唤生阵法] §f珊瑚已重新焕发生机！带回去给祭司洛禾看看吧。")
    }

    private fun abortRitual(player: Player, refundCoral: Boolean) {
        val ritual = activeRituals.remove(player.uniqueId) ?: return
        ritual.task?.cancel()
        (Bukkit.getEntity(ritual.itemEntityId) as? Item)?.remove()
        if (refundCoral) {
            giveQuestItem(player, INACTIVE_CORAL_ID, 1, Material.DEAD_TUBE_CORAL, "§7失活的珊瑚")
            player.sendMessage("§e[提示] 唤生仪式中断，失活的珊瑚已退还。")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onLockFrameInteract(event: PlayerInteractEntityEvent) {
        val frame = event.rightClicked as? ItemFrame ?: return
        if (frame.type != EntityType.GLOW_ITEM_FRAME || !isXuanwuLock(frame.location)) return

        secureLockFrame(frame)
        // 门锁展示框不接受原版物品放置或旋转；副手事件也必须拦截，否则物品会卡在框中。
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val data = plugin.playerManager.getPlayerData(player) ?: return

        // 清理旧版本可能留在门锁中的任务珊瑚，门锁只记录玩家资格，不展示任务物品。
        if (isAwakenedCoral(frame.item)) {
            frame.setItem(ItemStack(Material.AIR), false)
        }

        // questStatuses 是传送系统使用的权威状态，不能让管理指令重置前残留的完成缓存影响这里。
        val completed = data.questStatuses[id] == QuestStatus.COMPLETED
        if (completed) {
            plugin.teleportManager.tryTeleport(player, XUANWU_TELEPORT_POINT)
            return
        }

        val progress = data.questProgress[id] ?: 0
        if (data.questStatuses[id] == QuestStatus.IN_PROGRESS && progress == 4 &&
            isAwakenedCoral(player.inventory.itemInMainHand)
        ) {
            consumeMainHand(player)
            playLockAwakening(frame.location)
            player.sendMessage("§b玄武洞的门锁认可了你，日后抚摸此锁便可进入。")
            plugin.questManager.completeQuest(player, data, this)
            return
        }

        val now = System.currentTimeMillis()
        val lastMessage = lastLockFailMessage[player.uniqueId] ?: 0L
        if (now - lastMessage >= LOCK_FAIL_MESSAGE_COOLDOWN_MS) {
            lastLockFailMessage[player.uniqueId] = now
            player.sendMessage("§7这个门锁似乎没有对你的请求回应（请先完成前置任务再来吧）")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onLockFrameDamage(event: EntityDamageEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (frame.type == EntityType.GLOW_ITEM_FRAME && isXuanwuLock(frame.location)) {
            event.isCancelled = true
            secureLockFrame(frame)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onLockFrameBreak(event: HangingBreakEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (frame.type != EntityType.GLOW_ITEM_FRAME || !isXuanwuLock(frame.location)) return

        event.isCancelled = true
        secureLockFrame(frame)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onLockFrameProjectileHit(event: ProjectileHitEvent) {
        val frame = event.hitEntity as? ItemFrame ?: return
        if (frame.type != EntityType.GLOW_ITEM_FRAME || !isXuanwuLock(frame.location)) return

        event.isCancelled = true
        event.entity.remove()
        secureLockFrame(frame)
    }

    @EventHandler
    fun onLockChunkLoad(event: ChunkLoadEvent) {
        event.chunk.entities
            .filterIsInstance<ItemFrame>()
            .filter { it.type == EntityType.GLOW_ITEM_FRAME && isXuanwuLock(it.location) }
            .forEach(::secureLockFrame)
    }

    private fun secureLoadedXuanwuLock() {
        val world = Bukkit.getWorld(WORLD_NAME) ?: return
        val target = Location(world, -83.50, 37.50, -519.03)
        world.getNearbyEntities(target, 1.0, 1.0, 1.0)
            .filterIsInstance<ItemFrame>()
            .filter { it.type == EntityType.GLOW_ITEM_FRAME && isXuanwuLock(it.location) }
            .forEach(::secureLockFrame)
    }

    private fun secureLockFrame(frame: ItemFrame) {
        frame.isFixed = true
        frame.isInvulnerable = true
        frame.isPersistent = true
    }

    private fun playLockAwakening(location: Location) {
        val world = location.world ?: return
        repeat(4) { ring ->
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                val radius = 0.45 + ring * 0.22
                repeat(24) { index ->
                    val angle = Math.PI * 2.0 * index / 24.0
                    val point = location.clone().add(cos(angle) * radius, sin(angle * 2.0) * 0.25, sin(angle) * radius)
                    world.spawnParticle(
                        Particle.DUST,
                        point,
                        2,
                        0.02,
                        0.02,
                        0.02,
                        0.0,
                        Particle.DustOptions(Color.fromRGB(55, 205, 255), 1.25f)
                    )
                }
                world.spawnParticle(Particle.END_ROD, location, 15, 0.35, 0.45, 0.35, 0.05)
                world.playSound(location, Sound.BLOCK_CONDUIT_ACTIVATE, 1.1f, 0.8f + ring * 0.1f)
            }, ring * 8L)
        }
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, location, 55, 0.5, 0.6, 0.5, 0.1)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        talkProgress.remove(event.player.uniqueId)
        lastLockFailMessage.remove(event.player.uniqueId)
        abortRitual(event.player, refundCoral = true)
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin !== plugin) return
        activeRituals.keys.toList().forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let { abortRitual(it, refundCoral = true) }
                ?: activeRituals.remove(playerId)?.let { ritual ->
                    ritual.task?.cancel()
                    (Bukkit.getEntity(ritual.itemEntityId) as? Item)?.remove()
                }
        }
    }

    private fun giveQuestItem(
        player: Player,
        resourceId: String,
        amount: Int,
        fallbackMaterial: Material,
        fallbackName: String
    ) {
        val item = plugin.resourceManager.getItem(resourceId) ?: ItemStack(fallbackMaterial).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(fallbackName)
                lore = listOf("§e[任务物品]", "§c配置缺失，请联系管理员")
                persistentDataContainer.set(resourceKey, PersistentDataType.STRING, resourceId)
            }
        }
        item.amount = amount
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§a[获得物品] §f${fallbackName.replace(Regex("§."), "")} x$amount")
        if (leftovers.isNotEmpty()) player.sendMessage("§e[提示] 背包已满，物品已掉落在脚下。")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1.1f)
    }

    private fun consumeMainHand(player: Player) {
        val held = player.inventory.itemInMainHand
        if (held.amount <= 1) {
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        } else {
            held.amount -= 1
        }
    }

    private fun getResourceId(item: ItemStack?): String? =
        item?.itemMeta?.persistentDataContainer?.get(resourceKey, PersistentDataType.STRING)

    private fun isAwakenedCoral(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.TUBE_CORAL) return false
        if (getResourceId(item) == AWAKENED_CORAL_ID) return true

        // 兼容热重载前生成或创造模式复制后丢失 PDC、但名称仍完整的任务物品。
        val expected = plugin.resourceManager.getItem(AWAKENED_CORAL_ID) ?: return false
        return item.itemMeta?.displayName == expected.itemMeta?.displayName
    }

    private fun isQuestAtProgress(player: Player, progress: Int): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return false
        return data.questStatuses[id] == QuestStatus.IN_PROGRESS && data.questProgress[id] == progress
    }

    private fun altarSurface(): Location = Location(Bukkit.getWorld(WORLD_NAME), -339.5, 19.0, -695.5)

    private fun isAltarLever(location: Location): Boolean =
        location.world?.name == WORLD_NAME &&
            ((location.blockX == -337 && location.blockY == 19 && location.blockZ == -695) ||
                (location.blockX == -339 && location.blockY == 19 && location.blockZ == -693))

    private fun isAltarInventory(location: Location?): Boolean {
        if (location?.world?.name != WORLD_NAME) return false
        return altarContainers.any {
            location.blockX == it.x && location.blockY == it.y && location.blockZ == it.z
        }
    }

    private fun isXuanwuLock(location: Location): Boolean {
        if (location.world?.name != WORLD_NAME) return false
        return location.distanceSquared(Location(location.world, -83.50, 37.50, -519.03)) <= LOCK_RADIUS_SQUARED
    }

    private fun elementName(resourceId: String): String = when (resourceId) {
        "metal" -> "金元素"
        "wood" -> "木元素"
        "water" -> "水元素"
        "fire" -> "火元素"
        "earth" -> "土元素"
        else -> resourceId
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 200)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +200")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        talkProgress.remove(player.uniqueId)
        abortRitual(player, refundCoral = false)
    }

    private data class AltarContainer(
        val x: Int,
        val y: Int,
        val z: Int,
        val resourceId: String,
        val colorName: String,
        val dust: Particle.DustOptions
    )

    private data class CoralRitual(
        val itemEntityId: UUID,
        var task: BukkitTask? = null
    )

    private companion object {
        const val WORLD_NAME = "world"
        const val INACTIVE_CORAL_ID = "shihuodeshanhu"
        const val AWAKENED_CORAL_ID = "huanfashengjideshanhu"
        const val XUANWU_TELEPORT_POINT = "玄武洞-门锁"
        const val HOPPER_CENTER_SLOT = 2
        const val ALTAR_DROP_RADIUS_SQUARED = 9.0
        const val LOCK_RADIUS_SQUARED = 0.65
        const val LOCK_FAIL_MESSAGE_COOLDOWN_MS = 1_000L
        const val RITUAL_PERIOD_TICKS = 2L
        const val CORAL_RISE_TICKS = 40.0
        const val CORAL_FLOAT_HEIGHT = 2.4
        const val CORAL_TRANSFORM_TICK = 90
        const val RITUAL_DURATION_TICKS = 100
        const val ELEMENT_STREAM_CYCLE_TICKS = 20
    }
}
