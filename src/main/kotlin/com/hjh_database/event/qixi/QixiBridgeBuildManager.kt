package com.hjh_database.event.qixi

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 七夕限时活动“共建鹊桥”。活动进度与领奖记录独立持久化，时间配置复用 dungeon/qixi.yml。 */
class QixiBridgeBuildManager(private val plugin: Hjh_database) : Listener {

    private data class Reward(val resourceId: String, val amount: Int, val displayName: String)
    private data class Milestone(val progress: Int, val rewards: List<Reward>)

    private val legacyDataFile = File(plugin.dataFolder, "dungeon/qixi_bridge_build_data.yml")
    private val repository = QixiBridgeBuildRepository(plugin)
    private val playerStates = HashMap<UUID, QixiBridgePlayerState>()
    private val loadingPlayers = HashMap<UUID, CompletableFuture<QixiBridgePlayerState>>()
    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val celebrationFireworkKey = NamespacedKey(plugin, "qixi_bridge_celebration")
    private val menuOpenTimes = HashMap<UUID, Long>()
    private val fireworkUseTimes = HashMap<UUID, Long>()
    private val resettingPlayers = ConcurrentHashMap.newKeySet<UUID>()

    private var globalState = QixiBridgeGlobalState()
    private var storageReady = false
    private var stopping = false

    private var lastEventDate = LocalDate.parse(DEFAULT_LAST_EVENT_DATE)
    private var eventZone = ZoneId.of(DEFAULT_TIME_ZONE)

    private val milestones = listOf(
        Milestone(30, listOf(Reward("jinyuanbao", 8, "金元宝"), Reward("tongxinsuo", 5, "同心锁"))),
        // 需求原文在“银票×3 和”处截断；未知奖励不擅自发放，待补充后加在这里。
        Milestone(60, listOf(Reward("yinpiao", 3, "银票"))),
        Milestone(90, listOf(Reward("xingsha", 12, "星砂"), Reward("zhixingguo", 32, "织星果"))),
        Milestone(100, listOf(Reward("mijingyaoshi", 5, "秘境钥匙")))
    )

    init {
        reload()
        if (isEventActive()) initializeStorage()
    }

    fun reload() {
        val configFile = File(plugin.dataFolder, "dungeon/qixi.yml")
        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            plugin.saveResource("dungeon/qixi.yml", false)
        }
        val config = YamlConfiguration.loadConfiguration(configFile)

        val rawDate = config.getString("event-quest.last-accept-date", DEFAULT_LAST_EVENT_DATE)
            ?: DEFAULT_LAST_EVENT_DATE
        lastEventDate = runCatching { LocalDate.parse(rawDate) }.getOrElse {
            plugin.logger.warning("共建鹊桥读取到无效截止日期 $rawDate，已使用 $DEFAULT_LAST_EVENT_DATE")
            LocalDate.parse(DEFAULT_LAST_EVENT_DATE)
        }

        val rawZone = config.getString("event-quest.time-zone", DEFAULT_TIME_ZONE) ?: DEFAULT_TIME_ZONE
        eventZone = runCatching { ZoneId.of(rawZone) }.getOrElse {
            plugin.logger.warning("共建鹊桥读取到无效时区 $rawZone，已使用 $DEFAULT_TIME_ZONE")
            ZoneId.of(DEFAULT_TIME_ZONE)
        }
    }

    fun shutdown() {
        stopping = true
        storageReady = false
        playerStates.clear()
        loadingPlayers.clear()
        menuOpenTimes.clear()
        fireworkUseTimes.clear()
    }

    private fun initializeStorage() {
        plugin.databaseManager.submitDatabaseOperation {
            repository.migrateLegacyYaml(legacyDataFile)
            repository.loadGlobal()
        }.whenComplete { loaded, error ->
            runOnMain {
                if (error != null || loaded == null) {
                    plugin.logger.severe("共建鹊桥数据库初始化失败：${rootMessage(error)}")
                    if (!stopping) plugin.server.scheduler.runTaskLater(plugin, Runnable { initializeStorage() }, 100L)
                    return@runOnMain
                }
                globalState = loaded
                storageReady = true
                Bukkit.getOnlinePlayers().forEach { loadPlayerState(it) }
                plugin.logger.info("共建鹊桥数据库已就绪，全服进度 ${formatProgress(loaded.progress)}%。")
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onNpcInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val villager = event.rightClicked as? Villager ?: return
        val player = event.player
        val held = player.inventory.itemInMainHand.type
        if (player.isOp && (held == Material.WOODEN_HOE || held == Material.STONE_HOE)) return

        val npcId = villager.persistentDataContainer.get(
            plugin.npcModule.manager.npcKey,
            PersistentDataType.STRING
        ) ?: return
        if (npcId != StoryNpcs.QUJING.id) return

        event.isCancelled = true
        val now = System.currentTimeMillis()
        if (now - (menuOpenTimes[player.uniqueId] ?: 0L) < 300L) return
        menuOpenTimes[player.uniqueId] = now

        if (!isEventActive()) {
            player.sendMessage("§c七夕限时活动【共建鹊桥】已经结束。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f)
            return
        }
        withPlayerState(player) { openMenu(player) }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (storageReady) loadPlayerState(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        playerStates.remove(event.player.uniqueId)
        loadingPlayers.remove(event.player.uniqueId)
        menuOpenTimes.remove(event.player.uniqueId)
        fireworkUseTimes.remove(event.player.uniqueId)
    }

    fun preparePlayerReset(playerId: UUID) {
        resettingPlayers.add(playerId)
        loadingPlayers.remove(playerId)?.cancel(false)
    }

    fun cancelPlayerReset(playerId: UUID) {
        resettingPlayers.remove(playerId)
    }

    fun resetPlayerData(player: Player) {
        loadingPlayers.remove(player.uniqueId)?.cancel(false)
        menuOpenTimes.remove(player.uniqueId)
        playerStates[player.uniqueId] = QixiBridgePlayerState(
            uuid = player.uniqueId,
            playerName = player.name,
            revision = System.currentTimeMillis()
        )
        resettingPlayers.remove(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onFireworkUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val hand = event.hand ?: return
        val item = event.item ?: return
        if (getResourceId(item) != FIREWORK_RESOURCE_ID) return

        event.isCancelled = true
        val player = event.player
        if (!isOnMagpieBridge(player.location)) {
            player.sendMessage("§c星河共筑只能在鹊影桥上释放。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.1f)
            return
        }

        // 不限每日释放次数；每次消耗一支，不累计贡献、不访问数据库。
        // 仅合并双手事件/极短时间连点，避免同次右键重复创建烟花。
        val now = System.currentTimeMillis()
        if (now - (fireworkUseTimes[player.uniqueId] ?: 0L) < 300L) return
        fireworkUseTimes[player.uniqueId] = now
        consumeOne(player, hand)
        launchCelebration(player)
    }

    /** 活动烟花只承担展示作用，不让原版烟花爆炸误伤桥上的玩家或怪物。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCelebrationFireworkDamage(event: EntityDamageByEntityEvent) {
        val firework = event.damager as? Firework ?: return
        if (firework.persistentDataContainer.has(celebrationFireworkKey, PersistentDataType.BYTE)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? QixiBridgeMenuHolder ?: return
        event.isCancelled = true
        if (event.rawSlot !in 0 until event.view.topInventory.size) return
        val player = event.whoClicked as? Player ?: return
        if (holder.owner != player.uniqueId) return
        if (playerStates[player.uniqueId] == null) {
            player.closeInventory()
            player.sendMessage("§e活动数据正在重新加载，请稍后再试。")
            loadPlayerState(player)
            return
        }

        if (!isEventActive()) {
            player.closeInventory()
            player.sendMessage("§c七夕限时活动【共建鹊桥】已经结束。")
            return
        }

        when (event.rawSlot) {
            CLAIM_SLOT -> claimDailyFirework(player)
            GLOBAL_SLOT -> claimGlobalMilestones(player)
            PERSONAL_SLOT -> claimPersonalRewards(player)
            else -> return
        }
        refreshMenu(event.view.topInventory, player)
    }

    @EventHandler
    fun onMenuDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is QixiBridgeMenuHolder) event.isCancelled = true
    }

    private fun openMenu(player: Player) {
        val holder = QixiBridgeMenuHolder(player.uniqueId)
        val inventory = Bukkit.createInventory(holder, 27, MENU_TITLE)
        holder.menu = inventory
        refreshMenu(inventory, player)
        player.openInventory(inventory)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.7f, 1.25f)
    }

    private fun refreshMenu(inventory: Inventory, player: Player) {
        val filler = createButton(Material.WHITE_STAINED_GLASS_PANE, " ", emptyList())
        for (slot in 0 until inventory.size) inventory.setItem(slot, filler)
        inventory.setItem(RULES_SLOT, createRulesButton())
        inventory.setItem(CLAIM_SLOT, createDailyClaimButton(player))
        inventory.setItem(GLOBAL_SLOT, createGlobalProgressButton(player))
        inventory.setItem(PERSONAL_SLOT, createPersonalButton(player))
    }

    private fun createRulesButton(): ItemStack = createButton(
        Material.WRITABLE_BOOK,
        "&5&l共建鹊桥",
        listOf(
            "&7&o天河有桥，名为鹊影。千百年来，凡人与天上之间，",
            "&7&o隔着的从不是星汉，而是一座由愿力架起的桥。",
            "&7&o今夕七夕将至，鹊桥却因愿力稀薄而迟迟未能成形。",
            "&7&o若你想让更多人踏上这座桥，共赏天河星辉，便执一筒烟火，",
            "&7&o于鹊影桥上亲手点燃吧。",
            "",
            "&6活动介绍:",
            "&f每日可在&a曲靖&f处签到，获取道具&5星河共筑",
            "&f在鹊影桥上点燃释放，个人贡献增加&e1%&f，全服进度增加&d0.5%",
            "&f全服进度达到&b30%、60%、90%、100%&f时，可来此领取奖励",
            "&f每日签到后通关鹊桥星愿，还可按难度获得额外的&5星河共筑",
            "&f个人贡献度每达到&e10%&f也会获得奖励",
            "&7进度已满时，个人仍能释放道具并累计贡献"
        )
    )

    private fun createDailyClaimButton(player: Player): ItemStack {
        val state = playerStates[player.uniqueId] ?: return createLoadingButton()
        val today = currentDate().toString()
        val claimed = state.dailyClaimDate == today
        val used = dailyUses(state, today)
        val dungeonRewarded = dailyDungeonRewards(state, today)
        return createButton(
            Material.FIREWORK_ROCKET,
            "&d&l领取「星河共筑」",
            listOf(
                "&f每日可领取一次，每次获得&d1支",
                "&f每人每天最多释放&c${DAILY_USE_LIMIT}次",
                "&f仅可在&b鹊影桥&f范围内点燃",
                "",
                if (claimed) "&a今日已领取" else "&e点击领取今日的星河共筑",
                "&7今日已释放: &f$used&7/$DAILY_USE_LIMIT",
                "&7今日副本额外获得: &f$dungeonRewarded&7/$DAILY_DUNGEON_REWARD_LIMIT"
            )
        )
    }

    private fun createGlobalProgressButton(player: Player): ItemStack {
        val progress = globalProgress()
        val state = playerStates[player.uniqueId] ?: return createLoadingButton()
        val claimed = claimedMilestones(state)
        val filled = (progress / 5.0).toInt().coerceIn(0, 20)
        val bar = "&d" + "■".repeat(filled) + "&7" + "■".repeat(20 - filled)
        val lore = mutableListOf(
            "&f当前全服进度: &d&l${formatProgress(progress)}%",
            bar,
            "",
            milestoneLine(30, claimed, progress, "&a金元宝×8 &f+ &e同心锁×5"),
            milestoneLine(60, claimed, progress, "&9银票×3"),
            milestoneLine(90, claimed, progress, "&b星砂×12 &f+ &d织星果×32"),
            milestoneLine(100, claimed, progress, "&e秘境钥匙×5"),
            "",
            "&e点击领取所有已解锁且尚未领取的奖励"
        )
        return createButton(Material.NETHER_STAR, "&b&l全服：鹊桥共建进度", lore)
    }

    private fun createPersonalButton(player: Player): ItemStack {
        val state = playerStates[player.uniqueId] ?: return createLoadingButton()
        val contribution = state.contribution.coerceAtLeast(0)
        val claimed = state.personalClaimedTiers.coerceIn(0, PERSONAL_TIER_LIMIT)
        val unlocked = (contribution / 10).coerceAtMost(PERSONAL_TIER_LIMIT)
        val available = (unlocked - claimed).coerceAtLeast(0)
        val button = createButton(
            Material.PLAYER_HEAD,
            "&e&l查看个人贡献",
            listOf(
                "&f当前个人贡献: &e&l$contribution%",
                "&f已领取档数: &a$claimed&7/$PERSONAL_TIER_LIMIT",
                "&f当前可领取: &d$available&f档",
                "",
                "&6每贡献10%可获得:",
                "&9银票×1 &f+ &d织星果种子×7 &f+ &b星砂×1",
                "&7个人奖励最多可领取10次",
                "",
                if (available > 0) "&e点击领取当前所有可领取奖励" else "&7继续点燃星河共筑来提高贡献"
            )
        )
        (button.itemMeta as? SkullMeta)?.let { meta ->
            meta.owningPlayer = player
            button.itemMeta = meta
        }
        return button
    }

    private fun claimDailyFirework(player: Player) {
        val today = currentDate().toString()
        val state = playerStates[player.uniqueId] ?: return
        if (state.dailyClaimDate == today) {
            player.sendMessage("§c你今天已经领取过星河共筑了。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f)
            return
        }

        val item = plugin.resourceManager.getItem(FIREWORK_RESOURCE_ID)
        if (item == null) {
            player.sendMessage("§c星河共筑物品配置缺失，请联系管理员。")
            plugin.logger.warning("共建鹊桥无法发放资源：$FIREWORK_RESOURCE_ID")
            return
        }

        val updated = state.copy(
            playerName = player.name,
            dailyClaimDate = today,
            revision = nextRevision(state.revision)
        )
        playerStates[player.uniqueId] = updated
        persistPlayer(updated)
        item.amount = 1
        giveItems(player, listOf(item))
        player.sendMessage("§a签到成功，获得 §5星河共筑 §ax1！")
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.35f)
    }

    private fun claimGlobalMilestones(player: Player) {
        val progress = globalProgress()
        val state = playerStates[player.uniqueId] ?: return
        val claimed = claimedMilestones(state)
        val available = milestones.filter { it.progress.toDouble() <= progress && it.progress !in claimed }
        if (available.isEmpty()) {
            player.sendMessage("§c当前没有可领取的全服共建奖励。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f)
            return
        }

        val rewards = mergeRewards(available.flatMap { it.rewards })
        val items = prepareRewardItems(rewards) ?: run {
            player.sendMessage("§c奖励物品配置缺失，本次没有写入领取记录，请联系管理员。")
            return
        }

        val updatedMask = available.fold(state.claimedMilestones) { mask, milestone ->
            mask or milestoneBit(milestone.progress)
        }
        val updated = state.copy(
            playerName = player.name,
            claimedMilestones = updatedMask,
            revision = nextRevision(state.revision)
        )
        playerStates[player.uniqueId] = updated
        persistPlayer(updated)
        giveItems(player, items)
        player.sendMessage("§a已领取全服共建进度 ${available.joinToString("、") { "${it.progress}%" }} 的奖励！")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.15f)
    }

    private fun claimPersonalRewards(player: Player) {
        val state = playerStates[player.uniqueId] ?: return
        val contribution = state.contribution.coerceAtLeast(0)
        val claimed = state.personalClaimedTiers.coerceIn(0, PERSONAL_TIER_LIMIT)
        val unlocked = (contribution / 10).coerceAtMost(PERSONAL_TIER_LIMIT)
        val available = (unlocked - claimed).coerceAtLeast(0)
        if (available == 0) {
            player.sendMessage("§c当前没有可领取的个人贡献奖励。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f)
            return
        }

        val rewards = listOf(
            Reward("yinpiao", available, "银票"),
            Reward("zhixingguozhongzi", 7 * available, "织星果种子"),
            Reward("xingsha", available, "星砂")
        )
        val items = prepareRewardItems(rewards) ?: run {
            player.sendMessage("§c奖励物品配置缺失，本次没有写入领取记录，请联系管理员。")
            return
        }

        val updated = state.copy(
            playerName = player.name,
            personalClaimedTiers = claimed + available,
            revision = nextRevision(state.revision)
        )
        playerStates[player.uniqueId] = updated
        persistPlayer(updated)
        giveItems(player, items)
        player.sendMessage("§a已领取 $available 档个人贡献奖励！")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f)
    }

    private fun prepareRewardItems(rewards: List<Reward>): List<ItemStack>? {
        val items = ArrayList<ItemStack>()
        for (reward in rewards) {
            val item = plugin.resourceManager.getItem(reward.resourceId)
            if (item == null) {
                plugin.logger.warning("共建鹊桥奖励配置缺失：${reward.resourceId} (${reward.displayName})")
                return null
            }
            item.amount = reward.amount
            items += item
        }
        return items
    }

    private fun giveItems(player: Player, items: List<ItemStack>) {
        for (item in items) {
            player.inventory.addItem(item).values.forEach { overflow ->
                player.world.dropItemNaturally(player.location, overflow)
                player.sendMessage("§e[提示] 背包已满，部分奖励已掉落在脚下。")
            }
        }
    }

    /** 由鹊桥星愿的唯一胜利结算入口调用；每天需先签到，副本奖励累计最多6支。 */
    fun grantDungeonCompletionReward(player: Player, hardDifficulty: Boolean) {
        if (!isEventActive()) return
        withPlayerState(player) {
            val state = playerStates[player.uniqueId] ?: return@withPlayerState
            val today = currentDate().toString()
            if (state.dailyClaimDate != today) {
                player.sendMessage("§7[共建鹊桥] 今日尚未在曲靖处签到，本次通关不发放额外的星河共筑。")
                return@withPlayerState
            }

            val alreadyRewarded = dailyDungeonRewards(state, today)
            val remaining = DAILY_DUNGEON_REWARD_LIMIT - alreadyRewarded
            if (remaining <= 0) {
                player.sendMessage("§7[共建鹊桥] 今日通过副本获得的星河共筑已达到上限。")
                return@withPlayerState
            }

            val configuredAmount = if (hardDifficulty) HARD_DUNGEON_REWARD else EASY_DUNGEON_REWARD
            val grantedAmount = minOf(configuredAmount, remaining)
            val item = plugin.resourceManager.getItem(FIREWORK_RESOURCE_ID)
            if (item == null) {
                plugin.logger.warning("鹊桥星愿通关奖励缺少资源：$FIREWORK_RESOURCE_ID")
                player.sendMessage("§c通关额外奖励配置缺失，请联系管理员。")
                return@withPlayerState
            }

            val updated = state.copy(
                playerName = player.name,
                dungeonRewardDate = today,
                dungeonRewardCount = alreadyRewarded + grantedAmount,
                revision = nextRevision(state.revision)
            )
            playerStates[player.uniqueId] = updated
            persistPlayer(updated)
            item.amount = grantedAmount
            giveItems(player, listOf(item))
            val difficultyName = if (hardDifficulty) "困难" else "简单"
            player.sendMessage(
                "§5[共建鹊桥] §f通关${difficultyName}难度，获得 §d星河共筑×$grantedAmount§f！" +
                    " §7(今日副本奖励 ${updated.dungeonRewardCount}/$DAILY_DUNGEON_REWARD_LIMIT)"
            )
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f)
        }
    }

    private fun mergeRewards(rewards: List<Reward>): List<Reward> = rewards
        .groupBy { it.resourceId }
        .map { (id, entries) -> Reward(id, entries.sumOf { it.amount }, entries.first().displayName) }

    private fun milestoneLine(threshold: Int, claimed: Set<Int>, progress: Double, rewardText: String): String {
        val status = when {
            threshold in claimed -> "&a[已领取]"
            progress >= threshold.toDouble() -> "&e[可领取]"
            else -> "&7[未解锁]"
        }
        return "&f$threshold% $status &7- $rewardText"
    }

    private fun createButton(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(color(name))
        meta.lore = lore.map(::color)
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
        item.itemMeta = meta
        return item
    }

    private fun createLoadingButton(): ItemStack = createButton(
        Material.CLOCK,
        "&e正在加载活动数据",
        listOf("&7请稍候片刻后重新打开界面")
    )

    private fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    private fun getResourceId(item: ItemStack): String? = item.itemMeta?.persistentDataContainer?.get(
        resourceIdKey,
        PersistentDataType.STRING
    )

    private fun consumeOne(player: Player, hand: EquipmentSlot) {
        val current = if (hand == EquipmentSlot.HAND) {
            player.inventory.itemInMainHand
        } else {
            player.inventory.itemInOffHand
        }
        if (current.amount <= 1) {
            if (hand == EquipmentSlot.HAND) player.inventory.setItemInMainHand(null)
            else player.inventory.setItemInOffHand(null)
        } else {
            current.amount -= 1
        }
    }

    private fun launchCelebration(player: Player) {
        val launch = player.location.clone().add(0.0, 1.0, 0.0)
        val firework = player.world.spawn(launch, Firework::class.java)
        val meta = firework.fireworkMeta
        meta.power = 1
        meta.addEffects(
            FireworkEffect.builder()
                .with(FireworkEffect.Type.STAR)
                .withColor(Color.fromRGB(185, 92, 255), Color.fromRGB(77, 225, 255))
                .withFade(Color.WHITE)
                .trail(true)
                .flicker(true)
                .build(),
            FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.fromRGB(255, 202, 76), Color.fromRGB(241, 130, 255))
                .withFade(Color.fromRGB(125, 220, 255))
                .trail(true)
                .build()
        )
        firework.fireworkMeta = meta
        firework.persistentDataContainer.set(celebrationFireworkKey, PersistentDataType.BYTE, 1.toByte())
        firework.velocity = Vector(0.0, 0.82, 0.0)
        player.world.playSound(launch, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.1f, 1.12f)

        object : BukkitRunnable() {
            private var tick = 0
            private var last = launch.clone()

            override fun run() {
                if (!firework.isValid || tick >= 34) {
                    if (firework.isValid) firework.detonate()
                    showStarRiverBurst(last)
                    cancel()
                    return
                }

                last = firework.location.clone()
                val angle = tick * 0.62
                for (phase in listOf(0.0, PI)) {
                    val orbit = last.clone().add(cos(angle + phase) * 0.38, 0.0, sin(angle + phase) * 0.38)
                    last.world.spawnParticle(Particle.DUST, orbit, 1, 0.01, 0.01, 0.01, 0.0, if (phase == 0.0) PURPLE_DUST else CYAN_DUST)
                }
                if (tick % 3 == 0) {
                    last.world.spawnParticle(Particle.END_ROD, last, 2, 0.08, 0.08, 0.08, 0.01)
                    last.world.spawnParticle(Particle.ENCHANT, last, 4, 0.25, 0.12, 0.25, 0.02)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun showStarRiverBurst(center: Location) {
        val world = center.world ?: return
        world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.8f, 1.0f)
        world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.3f, 1.45f)
        world.spawnParticle(Particle.FIREWORK, center, 80, 1.8, 1.25, 1.8, 0.13)
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 54, 1.5, 1.0, 1.5, 0.12)
        world.spawnParticle(Particle.END_ROD, center, 36, 1.35, 0.9, 1.35, 0.08)

        for (ray in 0 until 12) {
            val angle = ray * (PI * 2.0 / 12.0)
            for (step in 1..7) {
                val radius = step * 0.38
                val point = center.clone().add(
                    cos(angle) * radius,
                    sin(step * 0.75 + ray * 0.35) * 0.72,
                    sin(angle) * radius
                )
                world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0.0, if (ray % 2 == 0) GOLD_DUST else PURPLE_DUST)
                if (step == 7) world.spawnParticle(Particle.END_ROD, point, 2, 0.08, 0.08, 0.08, 0.01)
            }
        }
    }

    private fun dailyUses(state: QixiBridgePlayerState, today: String): Int {
        return if (state.dailyUseDate == today) {
            state.dailyUses.coerceIn(0, DAILY_USE_LIMIT)
        } else {
            0
        }
    }

    private fun dailyDungeonRewards(state: QixiBridgePlayerState, today: String): Int {
        return if (state.dungeonRewardDate == today) {
            state.dungeonRewardCount.coerceIn(0, DAILY_DUNGEON_REWARD_LIMIT)
        } else {
            0
        }
    }

    private fun claimedMilestones(state: QixiBridgePlayerState): Set<Int> = milestones
        .map { it.progress }
        .filter { state.claimedMilestones and milestoneBit(it) != 0 }
        .toSet()

    private fun milestoneBit(threshold: Int): Int = when (threshold) {
        30 -> 1
        60 -> 2
        90 -> 4
        100 -> 8
        else -> 0
    }

    private fun globalProgress(): Double = globalState.progress.coerceIn(0.0, 100.0)

    private fun formatProgress(progress: Double): String {
        val bounded = progress.coerceIn(0.0, 100.0)
        return if (bounded % 1.0 == 0.0) bounded.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", bounded)
    }

    private fun currentDate(): LocalDate = LocalDate.now(eventZone)

    // 共建活动永久结算，不因修改旧截止日期再次发放烟花或贡献奖励。
    private fun isEventActive(): Boolean = false

    private fun isOnMagpieBridge(location: Location): Boolean {
        return location.world?.name == BRIDGE_WORLD &&
            location.x in BRIDGE_MIN_X..BRIDGE_MAX_X &&
            location.z in BRIDGE_MIN_Z..BRIDGE_MAX_Z
    }

    private fun withPlayerState(player: Player, action: () -> Unit) {
        if (!storageReady) {
            player.sendMessage("§e共建鹊桥活动数据正在初始化，请稍后再试。")
            return
        }
        if (playerStates.containsKey(player.uniqueId)) {
            action()
            return
        }
        player.sendMessage("§e正在读取你的共建鹊桥活动数据……")
        loadPlayerState(player) { action() }
    }

    private fun loadPlayerState(player: Player, onLoaded: (() -> Unit)? = null) {
        if (!storageReady || stopping || resettingPlayers.contains(player.uniqueId)) return
        playerStates[player.uniqueId]?.let {
            onLoaded?.invoke()
            return
        }

        val uuid = player.uniqueId
        val future = loadingPlayers[uuid] ?: plugin.databaseManager.submitDatabaseOperation {
            repository.loadPlayer(uuid, player.name)
        }.also { loadingPlayers[uuid] = it }

        future.whenComplete { loaded, error ->
            runOnMain {
                if (loadingPlayers[uuid] !== future || resettingPlayers.contains(uuid)) return@runOnMain
                loadingPlayers.remove(uuid)
                if (error != null || loaded == null) {
                    if (player.isOnline) player.sendMessage("§c共建鹊桥活动数据读取失败，请稍后重试。")
                    plugin.logger.severe("读取 ${player.name} 的共建鹊桥数据失败：${rootMessage(error)}")
                    return@runOnMain
                }
                if (!player.isOnline || stopping) return@runOnMain
                playerStates[uuid] = loaded.copy(playerName = player.name)
                onLoaded?.invoke()
            }
        }
    }

    private fun persistPlayer(state: QixiBridgePlayerState, attemptsLeft: Int = 3) {
        if (resettingPlayers.contains(state.uuid)) return
        plugin.databaseManager.submitDatabaseOperation {
            if (!resettingPlayers.contains(state.uuid)) repository.savePlayer(state)
        }
            .whenComplete { _, error ->
                if (error == null) return@whenComplete
                plugin.logger.warning("保存 ${state.playerName} 的共建鹊桥数据失败：${rootMessage(error)}")
                if (attemptsLeft > 1 && !stopping) {
                    runOnMain {
                        plugin.server.scheduler.runTaskLater(
                            plugin,
                            Runnable { persistPlayer(state, attemptsLeft - 1) },
                            20L
                        )
                    }
                }
            }
    }

    private fun persistUse(
        global: QixiBridgeGlobalState,
        state: QixiBridgePlayerState,
        attemptsLeft: Int = 3
    ) {
        if (resettingPlayers.contains(state.uuid)) return
        plugin.databaseManager.submitDatabaseOperation {
            if (!resettingPlayers.contains(state.uuid)) repository.saveUse(global, state)
        }
            .whenComplete { _, error ->
                if (error == null) return@whenComplete
                plugin.logger.warning("保存 ${state.playerName} 的共建进度失败：${rootMessage(error)}")
                if (attemptsLeft > 1 && !stopping) {
                    runOnMain {
                        plugin.server.scheduler.runTaskLater(
                            plugin,
                            Runnable { persistUse(global, state, attemptsLeft - 1) },
                            20L
                        )
                    }
                }
            }
    }

    private fun nextRevision(previous: Long): Long = maxOf(System.currentTimeMillis(), previous + 1L)

    private fun runOnMain(action: () -> Unit) {
        if (stopping) return
        if (Bukkit.isPrimaryThread()) action()
        else plugin.server.scheduler.runTask(plugin, Runnable { if (!stopping) action() })
    }

    private fun rootMessage(error: Throwable?): String {
        var current = error ?: return "未知错误"
        while (current is CompletionException && current.cause != null) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    companion object {
        private const val MENU_TITLE = "§5§l七夕限时活动 · 共建鹊桥"
        private const val RULES_SLOT = 10
        private const val CLAIM_SLOT = 12
        private const val GLOBAL_SLOT = 14
        private const val PERSONAL_SLOT = 16
        private const val FIREWORK_RESOURCE_ID = "xinghegongzhu"
        private const val DAILY_USE_LIMIT = 7
        private const val GLOBAL_PROGRESS_PER_USE = 0.5
        private const val DAILY_DUNGEON_REWARD_LIMIT = 6
        private const val EASY_DUNGEON_REWARD = 2
        private const val HARD_DUNGEON_REWARD = 3
        private const val PERSONAL_TIER_LIMIT = 10
        private const val DEFAULT_LAST_EVENT_DATE = "2026-08-31"
        private const val DEFAULT_TIME_ZONE = "Asia/Shanghai"
        private const val BRIDGE_WORLD = "world"
        private const val BRIDGE_MIN_X = 1295.0
        private const val BRIDGE_MAX_X = 1462.0
        private const val BRIDGE_MIN_Z = 2988.0
        private const val BRIDGE_MAX_Z = 3048.0

        private val PURPLE_DUST = Particle.DustOptions(Color.fromRGB(194, 87, 255), 1.15f)
        private val CYAN_DUST = Particle.DustOptions(Color.fromRGB(78, 229, 255), 1.05f)
        private val GOLD_DUST = Particle.DustOptions(Color.fromRGB(255, 211, 83), 1.2f)
    }
}

private class QixiBridgeMenuHolder(val owner: UUID) : InventoryHolder {
    lateinit var menu: Inventory
    override fun getInventory(): Inventory = menu
}
