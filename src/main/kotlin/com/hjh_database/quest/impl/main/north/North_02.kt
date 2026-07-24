package com.hjh_database.quest.impl.main.north

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Salmon
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class North_02 : QuestBase("main_north_2", "[主线]洄游祭的筹备", QuestType.MAIN, 20), Listener {

    override val raceLimit: Int? = null
    override val requiredCompletedQuestIds = setOf("main_north_1")

    override val description = listOf(
        "§7水族祭司洛禾或许能让你进入玄武神庙。",
        "§7协助他筹备洄游祭，取得进入神庙的资格。"
    )

    private val plugin = Hjh_database.instance
    private val resourceKey = NamespacedKey(plugin, "resource_id")
    private val fishingOwnerKey = NamespacedKey(plugin, "north_fishing_owner")
    private val dialogueProgress = HashMap<UUID, Int>()
    private val fishingSessions = ConcurrentHashMap<UUID, FishingSession>()

    private val scriptLuohe = listOf(
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f去去去，没见我正忙——嗯？你说你是§e族长§f让你来找我的？",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f……啊？你要进§b玄武神庙§f？不行不行，这可不合规矩。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f你也知道，§e洄游祭§f三天后便要举行了，我这正焦头烂额地筹备着呢。祭典之前，除了筹备的干部，谁也不得踏入神庙半步。外族人即便祭典当天来了，也只能在族里参加庆祝晚会，§c祭神仪式§f向来是不允许外人参观的。这是祖上传下的规矩，你求我也没用。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f……非进不可？让我想想。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f这样，你来帮我筹备祭典如何？祭神时不允外人入内，但§e事前准备§f的时候，干部是可以进入神庙的。你若肯搭手帮我备齐祭神所需之物，那前一天你便能以筹备的名义先进去逛逛。如何？",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f好，那就这么说定了。先帮我准备§e三条鲑鱼§f来。",
        "§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f记住了——§c我要活的，活的§f！看你这副模样，怕是连怎么下海抓鱼都不清楚吧。去找族里的§e顾小汉§f，他虽然也是个半吊子，但好歹比你多些经验。让他告诉你哪儿抓、怎么抓。"
    )

    private val scriptGuxiaohan = listOf(
        "§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f谁、谁是门外汉啊！我只是……不太擅长游泳罢了……",
        "§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f别笑了！还想不想要§e鲑鱼§f了？听好了——",
        "§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f你拿着这个§b水桶§f，去§e洄游塘§f，对着水里的鲑鱼用§e右键§f轻轻一碰，就能捞上来了。听起来简单？等你上手就知道了。",
        "§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f其一，那鲑鱼可不是随时都在塘里的。虽说眼下正值洄游时节，但这些家伙跟咱们族里好像有种约定似的——非得听见§e钟声§f才肯游进塘里等喂食。你到了那儿要是瞧不见鱼，就得先敲钟，一来一回折腾得很。",
        "§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f其二，那鲑鱼滑溜得很，在塘里停不了几秒便溜了。捞的时候稳着点，别心急滑倒了磕着脑袋。",
        "§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f好了，快去吧。洄游塘怎么走？从村子后面那个出口出去，看见§b玄水湖泊§f的指路牌后往左走，一回头便到了。",
        "§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f诶，水桶可不能白拿——这可是我辛辛苦苦做出来的！§e十五枚铜钱§f，三个桶，童叟无欺。快掏钱，别磨蹭！"
    )

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun getProgressText(progress: Int): List<String> = when (progress) {
        0 -> listOf("§c与水族祭司 §e洛禾 §c对话")
        1 -> listOf("§c寻找 §e顾小汉 §c学习捕捞鲑鱼")
        2 -> listOf("§c主手持有 §e15枚铜钱 §c向顾小汉购买水桶")
        3 -> listOf("§c前往洄游塘敲钟，并捕捞 §e3条活鲑鱼")
        4 -> listOf("§c带着 §e3桶鲑鱼 §c回去找祭司洛禾")
        else -> listOf("§a任务已完成")
    }

    override fun checkComplete(progress: Int): Boolean = false

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        return when {
            npcId == StoryNpcs.SHUIZUJISI.id && currentProgress == 0 -> {
                playDialogue(player, scriptLuohe) {
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] 去找顾小汉，向他学习如何捕捞鲑鱼。")
                }
                true
            }

            npcId == StoryNpcs.GUXIAOHAN.id && currentProgress == 1 -> {
                playDialogue(player, scriptGuxiaohan) {
                    plugin.questManager.updateProgress(player, id, 2)
                    player.sendMessage("§a[任务] 主手持有15枚铜钱，再与顾小汉对话购买水桶。")
                }
                true
            }

            npcId == StoryNpcs.GUXIAOHAN.id && currentProgress == 2 -> {
                purchaseBuckets(player)
                true
            }

            npcId == StoryNpcs.GUXIAOHAN.id && currentProgress == 3 -> {
                player.sendMessage("§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f快去洄游塘敲钟吧！鱼群只会停留五秒，可别磨蹭！")
                true
            }

            npcId == StoryNpcs.SHUIZUJISI.id && currentProgress in 3..4 -> {
                deliverSalmon(player)
                true
            }

            else -> false
        }
    }

    private fun playDialogue(player: Player, script: List<String>, onFinish: () -> Unit) {
        val index = dialogueProgress.getOrDefault(player.uniqueId, 0)
        if (index >= script.size) return

        player.sendMessage(script[index])
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == script.lastIndex) {
            dialogueProgress.remove(player.uniqueId)
            onFinish()
        } else {
            dialogueProgress[player.uniqueId] = index + 1
        }
    }

    private fun purchaseBuckets(player: Player) {
        val held = player.inventory.itemInMainHand
        if (getResourceId(held) != COPPER_ID || held.amount < COPPER_PRICE) {
            player.sendMessage("§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f说好了§e十五枚铜钱§f。把铜钱拿在主手上再来找我！")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        held.amount -= COPPER_PRICE
        val buckets = createQuestItem(EMPTY_BUCKET_ID, Material.WATER_BUCKET, "§b捞鱼水桶")
        buckets.amount = REQUIRED_SALMON
        giveOrDrop(player, buckets, "捞鱼水桶 x$REQUIRED_SALMON")

        player.sendMessage("§e[${StoryNpcs.GUXIAOHAN.displayName}§e] §f钱货两清！拿好三个桶，去洄游塘敲钟吧！")
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1.15f)
        plugin.questManager.updateProgress(player, id, 3)
    }

    private fun deliverSalmon(player: Player) {
        if (countResource(player, FILLED_BUCKET_ID) < REQUIRED_SALMON) {
            player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f还不够，我需要§e三条活鲑鱼§f。装在水桶里带回来！")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        removeResource(player, FILLED_BUCKET_ID, REQUIRED_SALMON)
        cleanupFishingSession(player.uniqueId)
        player.sendMessage("§e[${StoryNpcs.SHUIZUJISI.displayName}§e] §f三条活鲑鱼，一条不少。辛苦你了，这枚§6金元宝§f便给你当辛苦费吧。")
        plugin.playerManager.getPlayerData(player)?.let { data ->
            plugin.questManager.completeQuest(player, data, this)
        }
    }

    @EventHandler
    fun onBellInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BELL || !isFishingBell(block.location)) return

        val player = event.player
        if (!isQuestAtProgress(player, 3)) return
        if (fishingSessions.containsKey(player.uniqueId)) {
            player.sendMessage("§7鱼群正在循着钟声游来，稍等片刻……")
            return
        }

        startFishingSession(player)
    }

    private fun startFishingSession(player: Player) {
        val session = FishingSession()
        fishingSessions[player.uniqueId] = session
        player.sendMessage("§b[洄游塘] §f钟声在水下回荡，水面渐渐冒起了气泡……")
        player.playSound(player.location, Sound.BLOCK_BELL_USE, 1.2f, 0.9f)

        session.task = object : BukkitRunnable() {
            private var elapsedTicks = 0
            private var spawned = false

            override fun run() {
                if (!player.isOnline || !isQuestAtProgress(player, 3)) {
                    cleanupFishingSession(player.uniqueId)
                    return
                }

                if (!spawned) {
                    showBubbleParticles(player)
                    if (elapsedTicks >= FISH_WARMUP_TICKS) {
                        spawnSalmonBatch(player, session)
                        spawned = true
                        player.sendMessage("§b[洄游塘] §f鲑鱼群出现了！快用§e捞鱼水桶§f右键捕捞！")
                        player.playSound(player.location, Sound.ENTITY_SALMON_FLOP, 1f, 1.25f)
                    }
                } else {
                    moveSalmon(session)
                    if (elapsedTicks >= FISH_WARMUP_TICKS + FISH_LIFETIME_TICKS) {
                        val caught = countResource(player, FILLED_BUCKET_ID)
                        cleanupFishingSession(player.uniqueId)
                        if (caught < REQUIRED_SALMON) {
                            player.sendMessage("§7鱼群已经游远了，还差§e${REQUIRED_SALMON - caught}条§7。再次敲钟可以继续捕捞。")
                        }
                        return
                    }
                }

                elapsedTicks += SESSION_PERIOD_TICKS.toInt()
            }
        }.runTaskTimer(plugin, 0L, SESSION_PERIOD_TICKS)
    }

    private fun showBubbleParticles(player: Player) {
        repeat(10) {
            player.spawnParticle(
                Particle.BUBBLE,
                randomFishingLocation(player.world.name),
                3,
                0.15,
                0.15,
                0.15,
                0.02
            )
        }
    }

    private fun spawnSalmonBatch(player: Player, session: FishingSession) {
        repeat(FISH_BATCH_SIZE) {
            val salmon = player.world.spawn(randomFishingLocation(player.world.name), Salmon::class.java)
            salmon.isInvulnerable = true
            salmon.isPersistent = false
            salmon.persistentDataContainer.set(fishingOwnerKey, PersistentDataType.STRING, player.uniqueId.toString())
            session.fishIds.add(salmon.uniqueId)

            Bukkit.getOnlinePlayers().forEach { online ->
                if (online.uniqueId == player.uniqueId) {
                    online.showEntity(plugin, salmon)
                } else {
                    online.hideEntity(plugin, salmon)
                }
            }
        }
    }

    private fun moveSalmon(session: FishingSession) {
        session.fishIds.mapNotNull(Bukkit::getEntity).forEach { fish ->
            if (!fish.isValid) return@forEach
            fish.velocity = Vector(
                Random.nextDouble(-0.38, 0.38),
                Random.nextDouble(-0.04, 0.08),
                Random.nextDouble(-0.38, 0.38)
            )
        }
    }

    @EventHandler
    fun onCatchSalmon(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val salmon = event.rightClicked as? Salmon ?: return
        val ownerId = getFishingOwner(salmon) ?: return

        event.isCancelled = true
        catchSalmon(event.player, salmon, ownerId)
    }

    @EventHandler
    fun onBucketSalmon(event: PlayerBucketEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val salmon = event.entity as? Salmon ?: return
        val ownerId = getFishingOwner(salmon) ?: return

        event.isCancelled = true
        catchSalmon(event.player, salmon, ownerId)
    }

    private fun catchSalmon(player: Player, salmon: Salmon, ownerId: UUID) {
        if (player.uniqueId != ownerId || !isQuestAtProgress(player, 3)) return

        val held = player.inventory.itemInMainHand
        if (getResourceId(held) != EMPTY_BUCKET_ID) {
            player.sendMessage("§c[提示] 请主手持有顾小汉给你的捞鱼水桶。")
            return
        }

        val session = fishingSessions[ownerId] ?: return
        if (!session.fishIds.remove(salmon.uniqueId)) return

        held.amount -= 1
        salmon.remove()

        val filledBucket = createQuestItem(FILLED_BUCKET_ID, Material.SALMON_BUCKET, "§b装有鲑鱼的水桶")
        giveOrDrop(player, filledBucket, "装有鲑鱼的水桶 x1")
        player.playSound(player.location, Sound.ITEM_BUCKET_FILL_FISH, 1f, 1.1f)

        val caught = countResource(player, FILLED_BUCKET_ID)
        player.sendMessage("§a[捕捞] 成功捞起一条鲑鱼！§e($caught/$REQUIRED_SALMON)")
        if (caught >= REQUIRED_SALMON) {
            plugin.questManager.updateProgress(player, id, 4)
            cleanupFishingSession(player.uniqueId)
            player.sendMessage("§a[任务] 鲑鱼已经足够了，回去找祭司洛禾吧！")
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f)
        }
    }

    @EventHandler
    fun onFishingSalmonDamage(event: EntityDamageEvent) {
        if (getFishingOwner(event.entity) != null) event.isCancelled = true
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val viewer = event.player
        fishingSessions.forEach { (ownerId, session) ->
            if (ownerId == viewer.uniqueId) return@forEach
            session.fishIds.mapNotNull(Bukkit::getEntity).forEach { viewer.hideEntity(plugin, it) }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        dialogueProgress.remove(event.player.uniqueId)
        cleanupFishingSession(event.player.uniqueId)
    }

    private fun cleanupFishingSession(playerId: UUID) {
        val session = fishingSessions.remove(playerId) ?: return
        session.task?.cancel()
        session.fishIds.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        session.fishIds.clear()
    }

    private fun isQuestAtProgress(player: Player, progress: Int): Boolean {
        val data = plugin.playerManager.getPlayerData(player) ?: return false
        return data.questStatuses[id] == QuestStatus.IN_PROGRESS && data.questProgress[id] == progress
    }

    private fun isFishingBell(location: Location): Boolean =
        location.world?.name == FISHING_WORLD &&
            location.blockX == FISHING_BELL_X &&
            location.blockY == FISHING_BELL_Y &&
            location.blockZ == FISHING_BELL_Z

    private fun randomFishingLocation(worldName: String): Location {
        val world = Bukkit.getWorld(worldName) ?: Bukkit.getWorld(FISHING_WORLD)
            ?: error("Fishing world '$FISHING_WORLD' is not loaded")
        return Location(
            world,
            Random.nextDouble(-270.0, -267.999),
            Random.nextDouble(30.2, 32.0),
            Random.nextDouble(-692.0, -684.999)
        )
    }

    private fun getFishingOwner(entity: Entity): UUID? {
        val raw = entity.persistentDataContainer.get(fishingOwnerKey, PersistentDataType.STRING) ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    private fun getResourceId(item: ItemStack?): String? =
        item?.itemMeta?.persistentDataContainer?.get(resourceKey, PersistentDataType.STRING)

    private fun countResource(player: Player, resourceId: String): Int =
        player.inventory.contents
            .filter { getResourceId(it) == resourceId }
            .sumOf { it?.amount ?: 0 }

    private fun removeResource(player: Player, resourceId: String, amount: Int) {
        var remaining = amount
        player.inventory.contents.forEach { item ->
            if (remaining <= 0 || getResourceId(item) != resourceId) return@forEach
            val removed = minOf(item!!.amount, remaining)
            item.amount -= removed
            remaining -= removed
        }
    }

    private fun createQuestItem(resourceId: String, fallbackMaterial: Material, fallbackName: String): ItemStack {
        return plugin.resourceManager.getItem(resourceId) ?: ItemStack(fallbackMaterial).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(fallbackName)
                lore = listOf("§e[任务物品]", "§c配置缺失，请联系管理员")
                persistentDataContainer.set(resourceKey, PersistentDataType.STRING, resourceId)
            }
        }
    }

    private fun giveOrDrop(player: Player, item: ItemStack, displayName: String) {
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage(
            if (leftovers.isEmpty()) "§a[获得物品] §f$displayName"
            else "§e[获得物品] §f$displayName §7（背包已满，部分物品掉落在脚下）"
        )
    }

    override fun giveReward(player: Player) {
        plugin.playerManager.giveExp(player, 200)
        plugin.playerManager.getPlayerData(player)?.let(plugin.databaseManager::savePlayerAsync)

        val yuanbao = createQuestItem("jinyuanbao", Material.SUNFLOWER, "§6金元宝")
        yuanbao.amount = 1
        giveOrDrop(player, yuanbao, "金元宝 x1")

        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("  §e[奖励] §f经验 +200")
        player.sendMessage("  §e[奖励] §f金元宝 x1")
        player.sendMessage("§8§m========================================")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        dialogueProgress.remove(player.uniqueId)
    }

    private data class FishingSession(
        val fishIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        var task: BukkitTask? = null
    )

    private companion object {
        const val COPPER_ID = "hjh_tongqian"
        const val EMPTY_BUCKET_ID = "laoyushuitong"
        const val FILLED_BUCKET_ID = "guiyushuitong"
        const val COPPER_PRICE = 15
        const val REQUIRED_SALMON = 3
        const val FISH_BATCH_SIZE = 5
        const val FISHING_WORLD = "world"
        const val FISHING_BELL_X = -265
        const val FISHING_BELL_Y = 34
        const val FISHING_BELL_Z = -684
        const val FISH_WARMUP_TICKS = 40
        const val FISH_LIFETIME_TICKS = 100
        const val SESSION_PERIOD_TICKS = 5L
    }
}
