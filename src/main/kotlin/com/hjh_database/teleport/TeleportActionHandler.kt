package com.hjh_database.teleport

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.quest.core.QuestStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class TeleportActionHandler(private val plugin: Hjh_database) {
    fun checkConditions(player: Player, data: PlayerData, actionStr: String): Boolean {
        when (actionStr) {
            "JOB_TRIAL_WARRIOR" -> {
                // 1. 职业检查：如果是 0, 1, 2, 3 中的任意一个，则禁止
                if (data.job != null && data.job in 0..3) {
                    player.sendMessage("§c你已经选择了职业，无法再来体验了！")
                    return false
                }
                // 2. 背包检查：检查背包内容和装备栏是否为空
                val inv = player.inventory
                val hasItem = inv.contents.any { it != null && it.type != org.bukkit.Material.AIR } ||
                        inv.armorContents.any { it != null && it.type != org.bukkit.Material.AIR }

                if (hasItem) {
                    player.sendMessage("§c请先清空背包再来吧")
                    return false
                }
            }
            "JOB_TRIAL_ARCHER" -> {
                // 1. 职业检查：如果是 0, 1, 2, 3 中的任意一个，则禁止
                if (data.job != null && data.job in 0..3) {
                    player.sendMessage("§c你已经选择了职业，无法再来体验了！")
                    return false
                }
                // 2. 背包检查：检查背包内容和装备栏是否为空
                val inv = player.inventory
                val hasItem = inv.contents.any { it != null && it.type != org.bukkit.Material.AIR } ||
                        inv.armorContents.any { it != null && it.type != org.bukkit.Material.AIR }

                if (hasItem) {
                    player.sendMessage("§c请先清空背包再来吧")
                    return false
                }
            }
            "JOB_TRIAL_MAGIC" -> {
                // 1. 职业检查：如果是 0, 1, 2, 3 中的任意一个，则禁止
                if (data.job != null && data.job in 0..3) {
                    player.sendMessage("§c你已经选择了职业，无法再来体验了！")
                    return false
                }
                // 2. 背包检查：检查背包内容和装备栏是否为空
                val inv = player.inventory
                val hasItem = inv.contents.any { it != null && it.type != org.bukkit.Material.AIR } ||
                        inv.armorContents.any { it != null && it.type != org.bukkit.Material.AIR }

                if (hasItem) {
                    player.sendMessage("§c请先清空背包再来吧")
                    return false
                }
            }
            "JOB_TRIAL_ALCHEMY" -> {
                // 1. 职业检查：如果是 0, 1, 2, 3 中的任意一个，则禁止
                if (data.job != null && data.job in 0..3) {
                    player.sendMessage("§c你已经选择了职业，无法再来体验了！")
                    return false
                }
                // 2. 背包检查：检查背包内容和装备栏是否为空
                val inv = player.inventory
                val hasItem = inv.contents.any { it != null && it.type != org.bukkit.Material.AIR } ||
                        inv.armorContents.any { it != null && it.type != org.bukkit.Material.AIR }
                if (hasItem) {
                    player.sendMessage("§c请先清空背包再来吧")
                    return false
                }
            }
            // === 新增：重生石前置检查 ===
            "RELIVE_STONE_TO_CITY", "RELIVE_STONE_TO_RACE" -> {
                val itemInHand = player.inventory.itemInMainHand
                // 从 ResourceManager 获取正确的重生石模板
                val rm = Hjh_database.instance.resourceManager
                val reliveStone = rm.getItem("relive_stone")
                // 如果没拿东西或者配置获取失败
                if (itemInHand.type == org.bukkit.Material.AIR || !itemInHand.hasItemMeta()) {
                    player.sendMessage("§c请手持重生石重生！")
                    return false
                }
                // 比对材质和名字判定是否为重生石
                if (reliveStone != null) {
                    val handMeta = itemInHand.itemMeta
                    val stoneMeta = reliveStone.itemMeta
                    if (itemInHand.type != reliveStone.type || handMeta?.displayName != stoneMeta?.displayName) {
                        player.sendMessage("§c请手持重生石重生！")
                        return false
                    }
                } else {
                    player.sendMessage("§c[错误] 缺失重生石配置，请联系管理员！")
                    return false
                }
                return true // 检查通过，允许传送
            }
            "ENTER_QIXIA_TOWN" -> {
                if (data.questStatuses["side_strange_tree"] == null ||
                    data.questStatuses["side_strange_tree"] == QuestStatus.LOCKED
                ) {
                    player.sendMessage("§7这树洞到底有啥用，搞不懂，还是离开吧……")
                    player.sendMessage("§c请先完成前置任务")
                    return false
                }
            }
            "ENTER_QIXI_BRIDGE" -> {
                // 常态开放；传送后的旧任务推进逻辑仍保留。
                return true
            }
            "ENTER_PENGLAI" -> {
                val requiredQuestId = when (data.race) {
                    0 -> "main_shen_10"
                    1 -> "main_xian_10"
                    2 -> "main_ren_10"
                    3 -> "main_zhan_10"
                    4 -> "main_yao_10"
                    else -> null
                }
                if (requiredQuestId == null ||
                    data.questStatuses[requiredQuestId] != QuestStatus.COMPLETED
                ) {
                    player.sendMessage("§7通往蓬莱的航路似乎被一层结界遮蔽了。")
                    player.sendMessage("§c请先完成对应种族的前置主线任务。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 0.8f)
                    return false
                }

                val missingTrials = SACRED_BEAST_TRIALS.mapNotNull { (recordId, displayName) ->
                    displayName.takeIf { (data.dungeonRecords[recordId]?.clears ?: 0) <= 0 }
                }
                if (missingTrials.isNotEmpty()) {
                    player.sendMessage("§c蓬莱结界没有认可你，尚未亲手通过：§e${missingTrials.joinToString("、")}试炼§c。")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 0.8f)
                    return false
                }
            }
        }
        return true
    }

    /**
     * ★★★ 在这里写你的个性化硬编码逻辑 ★★★
     * @param actionStr 配置文件里 actions.custom_action 的值
     * @return 如果修改了玩家数据需要保存，返回 true；否则返回 false
     */
    fun handle(player: Player, data: PlayerData, actionStr: String): Boolean {
        when (actionStr) {
            // === 新增：选择种族-前往新手前置 ===
            "CHOOSE_HUMAN_START", "CHOOSE_YAO_START", "CHOOSE_GOD_START", "CHOOSE_IMMORTAL_START", "CHOOSE_WAR_GOD_START" -> {
                // 1. 处理队伍 (关闭友伤)
                val board = Bukkit.getScoreboardManager().mainScoreboard
                var team = board.getTeam("player")
                if (team == null) {
                    team = board.registerNewTeam("player")
                    team.setAllowFriendlyFire(false) // 关闭队友伤害
                    team.setCanSeeFriendlyInvisibles(true)
                    team.color = org.bukkit.ChatColor.WHITE
                }
                if (!team.hasEntry(player.name)) {
                    team.addEntry(player.name)
                }
                // 2. 修改状态 -> 1
                data.updateStatus(1) // 这会自动更新 description
                // 3. 修改种族：0=神族，1=仙族，2=人族，3=战神族，4=妖族
                data.race = when (actionStr) {
                    "CHOOSE_GOD_START" -> 0
                    "CHOOSE_IMMORTAL_START" -> 1
                    "CHOOSE_WAR_GOD_START" -> 3
                    "CHOOSE_YAO_START" -> 4
                    else -> 2
                }
                // 4. 恢复满状态 (瞬间治疗 + 饱和)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 255, false, false))
                // 5. 传送逻辑已经由 tryTeleport 方法根据 yml 配置执行了，这里不需要重复写 tp 代码
                return true // 返回 true 表示修改了 data (race/status)，需要保存数据库
            }
            // === 新增：根据种族传送 ===
            "TELEPORT_BY_RACE" -> {
                // 获取当前世界（假设就在当前世界传送，如果跨世界请指定 world 名字）
                val world = player.world
                // 获取玩家当前种族，如果未设置(null)则默认为 0
                val raceId = data.race ?: 0
                // 根据种族决定 X 坐标
                val targetX = when (raceId) {
                    0 -> 1200.5 // 神
                    1 -> 1296.5 // 仙
                    2 -> 1392.5 // 人
                    3 -> 1344.5 // 战神
                    4 -> 1248.5 // 妖
                    else -> 1200.5 // 默认/未知种族去神族点
                }
                // 固定的 Y, Z 和 Yaw (-90)
                val targetY = 43.0
                val targetZ = 647.5 // 加 .5 居中
                val targetYaw = -90f
                // Pitch 使用 player.location.pitch (即 ~ )
                val targetPitch = player.location.pitch
                // 构建位置并传送
                val targetLoc = org.bukkit.Location(world, targetX, targetY, targetZ, targetYaw, targetPitch)
                player.teleport(targetLoc)
                // 播放个音效提示传送成功
                player.playSound(player.location, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
                // 这里只是传送，没有修改 data 数据，所以返回 false (不需要存库)
                return false
            }
            // === 新增：反悔-重选种族 ===
            "RESET_RACE" -> {
                // 1. 将种族重置为空
                data.race = null
                // 2. 将状态重置为 0
                data.updateStatus(0)
                // 3. 给点提示
                player.sendMessage("§7你放弃了之前的选择...")
                player.playSound(player.location, org.bukkit.Sound.BLOCK_ANVIL_LAND, 1f, 0.5f)
                // 4. 返回 true，表示数据发生了变动，需要保存数据库
                return true
            }
            // === 新增：确认进入盘古大陆 ===
            "ENTER_PANGU" -> {
                // 获取世界，假设都在主世界 "world"，如果不是请修改 world 的名字
                // 如果你想让它就在玩家当前所在的世界传送，用 player.world 即可
                val world = Bukkit.getWorld("world") ?: player.world
                val raceId = data.race ?: 0
                // 1. 根据种族获取目标坐标
                // 注意：坐标加了 .5 以防卡墙，角度按你要求的填
                val targetLoc = when (raceId) {
                    0 -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f)
                    1 -> org.bukkit.Location(world, 3179.5, 127.0, 783.5, -90f, 0f)
                    2 -> org.bukkit.Location(world, 1689.5, 140.0, 138.5, 90f, 0f)
                    3 -> org.bukkit.Location(world, 3299.5, 22.0, -138.5, 90f, 0f)
                    4 -> org.bukkit.Location(world, 2845.5, 48.0, 899.5, 180f, -20f)
                    else -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f) // 默认去种族0
                }
                // 执行传送
                player.teleport(targetLoc)
                // 2. 修改 status -> 2
                data.updateStatus(2)
                // 3. 补满状态 (瞬间治疗 + 饱和)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 255, false, false))
                // ★★★ 新增：根据种族自动接取第一个主线任务 ★★★
                when (raceId) {
                    0 -> plugin.questManager.acceptQuest(player, "main_shen_1")
                    1 -> plugin.questManager.acceptQuest(player, "main_xian_1")
                    2 -> plugin.questManager.acceptQuest(player, "main_ren_1")
                    3 -> plugin.questManager.acceptQuest(player, "main_zhan_1")
                    4 -> plugin.questManager.acceptQuest(player, "main_yao_1")
                }
                // 4. 发送提示消息
                player.sendMessage("§6恭喜正式进入盘古大陆。请与新手引导员进行交流，接取任务吧！")
                return true // 需要保存数据
            }
            "ENTER_QIXIA_TOWN" -> {
                player.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, 20 * 7, 0, false, false, true))

                if (data.questStatuses["side_strange_tree"] == QuestStatus.IN_PROGRESS &&
                    data.questProgress["side_strange_tree"] == 1
                ) {
                    plugin.questManager.updateProgress(player, "side_strange_tree", 2)
                    player.sendMessage("§e你感到一阵头晕，前方好像有光亮和人声，去找人问问情况吧")
                }
                return false
            }
            "ENTER_QIXI_BRIDGE" -> {
                player.world.spawnParticle(
                    Particle.FIREWORK,
                    player.location.clone().add(0.0, 1.0, 0.0),
                    18,
                    0.4,
                    0.6,
                    0.4,
                    0.03
                )
                player.playSound(player.location, Sound.ENTITY_PARROT_FLY, 1.0f, 1.2f)
                if (data.questStatuses[QIXI_QUEST_ID] == QuestStatus.IN_PROGRESS &&
                    data.questProgress[QIXI_QUEST_ID] == 1
                ) {
                    plugin.questManager.updateProgress(player, QIXI_QUEST_ID, 2)
                    player.sendMessage("§a[任务] -> 寻找守桥人柳安，询问鹊影桥的异常。")
                }
                return false
            }
            // === 职业体验-战士 ===
            "JOB_TRIAL_WARRIOR" -> {
                // 此时能运行到这里，说明 checkCustomActionConditions 已经通过了
                // 且玩家已经被 tryTeleport 方法准确传送到了 yml 填写的坐标
                // 设置职业为 1，状态为 4
                data.job = 0
                data.updateStatus(4)
                player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
                return true // 返回 true 以保存数据库
            }

            "JOB_TRIAL_ARCHER" -> {
                // 3. 传送与数据更新
                // 设置职业为 1，状态为 4
                data.job = 1
                data.updateStatus(4)
                return true // 返回 true 以保存数据
            }
            "JOB_TRIAL_MAGIC" -> {
                // 3. 传送与数据更新
                data.job = 2
                data.updateStatus(4)
                // 初始化五种元素阵法的等级为 1 级
                data.elementLevels["METAL"] = 1
                data.elementLevels["WOOD"] = 1
                data.elementLevels["WATER"] = 1
                data.elementLevels["FIRE"] = 1
                data.elementLevels["EARTH"] = 1
                return true // 返回 true 以保存数据
            }
            "JOB_TRIAL_ALCHEMY" -> {
                // 3. 传送与数据更新
                // 设置职业为 1，状态为 4
                data.job = 3
                data.updateStatus(4)
                return true // 返回 true 以保存数据
            }
            // === 新增：全职业暂离-体验其他职业 ===
            "LEAVE_JOB_TRIAL_WARRIOR",
            "LEAVE_JOB_TRIAL_ARCHER",
            "LEAVE_JOB_TRIAL_MAGIC",
            "LEAVE_JOB_TRIAL_DOCTOR" -> {
                // 1. 清空玩家背包和装备栏
                player.inventory.clear()
                player.inventory.armorContents = arrayOfNulls(4)
                // 2. 将玩家 status 设置为 3
                data.updateStatus(3)
                // 3. 将玩家 job 设置为 null
                data.job = null
                // 清空该玩家所有的医术记忆
                data.clearMedicalSkills()
                // 异步保存医术数据（如果你的转职逻辑最后有统一的 savePlayer(data)，这行也可以省略，但加上最保险）
                java.util.concurrent.CompletableFuture.runAsync { plugin.databaseManager.saveMedicalData(data) }
                // 4. 将玩家体力和饱和恢复满 (瞬间治疗 + 饱和度，255级)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 255, false, false))
                // 5. 根据具体的 actionStr 动态获取职业名称并发送消息
                val jobName = when (actionStr) {
                    "LEAVE_JOB_TRIAL_WARRIOR" -> "战士"
                    "LEAVE_JOB_TRIAL_ARCHER" -> "弓箭手"
                    "LEAVE_JOB_TRIAL_MAGIC" -> "术士"
                    "LEAVE_JOB_TRIAL_DOCTOR" -> "医师"
                    else -> "未知"
                }
                player.sendMessage("§7你暂时离开了${jobName}职业体验……")
                // 返回 true 表示数据已发生变动，需要保存数据库
                return true
            }
            // === 新增：决定成为职业 (全职业合并处理) ===
            "CHOOSE_JOB_WARRIOR",
            "CHOOSE_JOB_ARCHER",
            "CHOOSE_JOB_MAGIC",
            "CHOOSE_JOB_DOCTOR" -> {
                // 1 & 2. 清空背包和装备栏
                player.inventory.clear()
                player.inventory.armorContents = arrayOfNulls(4)
                // 3. 将玩家状态设为 3
                data.updateStatus(3)
                // 5. 恢复满状态 (瞬间治疗 + 饱和度)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 255, false, false))
                // 6. 播放升级音效，降低音量 (0.6f) 避免太吵
                player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1f)
                // 获取你的资源管理器 (根据你的之前代码，通常是这样获取的，如果报错请改为 plugin.resourceManager)
                val rm = Hjh_database.instance.resourceManager
                // 用来收集溢出背包的物品
                val leftovers = mutableMapOf<Int, org.bukkit.inventory.ItemStack>()
                // ★★★ 新增：全职业通用防具 (初心套) ★★★
                val helmet = rm.getItem("chuxinzhepimao") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_HELMET).apply {
                    itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] chuxinzhepimao") }
                }
                val chestplate = rm.getItem("chuxinzhehujia") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_CHESTPLATE).apply {
                    itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] chuxinzhehujia") }
                }
                val leggings = rm.getItem("chuxinzhehutui") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_LEGGINGS).apply {
                    itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] chuxinzhehutui") }
                }
                val boots = rm.getItem("chuxinzhepixue") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_BOOTS).apply {
                    itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] chuxinzhepixue") }
                }

                // 将防具发放给玩家
                leftovers.putAll(player.inventory.addItem(helmet, chestplate, leggings, boots))
                // ★★★ ============================ ★★★
                // 4 & 7 & 8 & 9. 分支处理具体职业的 job、消息与物品
                when (actionStr) {
                    "CHOOSE_JOB_WARRIOR" -> {
                        data.job = 0
                        player.sendMessage("§6恭喜你成功加入职业——【战士】！")
                        player.sendMessage("§e[顾镇岳]: §f好小子，果然没看错你！这些就都给你了！")
                        val weapon = rm.getItem("taomujian") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.WOODEN_SWORD).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] taomujian") }
                        }
                        // 手搓包子
                        val food = org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 15).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§f包子") }
                        }
                        leftovers.putAll(player.inventory.addItem(weapon, food))
                    }
                    "CHOOSE_JOB_ARCHER" -> {
                        data.job = 1
                        player.sendMessage("§6恭喜你成功加入职业——【弓箭手】！")
                        player.sendMessage("§e[余步云]: §f不错，很有风度，拿好这把弓！")
                        val weapon = rm.getItem("tengmugong") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.BOW).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] tengmugong") }
                        }
                        // 手搓原版箭
                        val arrows = org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW, 64)
                        leftovers.putAll(player.inventory.addItem(weapon, arrows))
                    }
                    "CHOOSE_JOB_MAGIC" -> {
                        data.job = 2
                        player.sendMessage("§6恭喜你成功加入职业——【术士】！")
                        player.sendMessage("§e[秦观星]: §f果然符合我们术士的审美，这炉子和元素你且拿好~")
                        val weapon = rm.getItem("xuetulu") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.CARROT_ON_A_STICK).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] xuetulu") }
                        }
                        // 批量生成5种元素
                        val elements = listOf("metal", "wood", "fire", "water", "earth").map { id ->
                            val item = rm.getItem(id) ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_NUGGET).apply {
                                itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] $id") }
                            }
                            item.amount = 15
                            item
                        }
                        val conversionTickets = rm.getItem("yuansuzhuanhuaquan")
                            ?.apply { amount = 15 }
                            ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER, 15).apply {
                                itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] yuansuzhuanhuaquan") }
                            }
                        // 武器、元素和元素转化券一起塞入背包
                        leftovers.putAll(
                            player.inventory.addItem(
                                weapon,
                                *elements.toTypedArray(),
                                conversionTickets
                            )
                        )
                    }
                    "CHOOSE_JOB_DOCTOR" -> {
                        data.job = 3
                        // 清空该玩家所有的医术记忆
                        data.clearMedicalSkills()
                        // 异步保存医术数据（如果你的转职逻辑最后有统一的 savePlayer(data)，这行也可以省略，但加上最保险）
                        java.util.concurrent.CompletableFuture.runAsync { plugin.databaseManager.saveMedicalData(data) }
                        player.sendMessage("§6恭喜你成功加入职业——【医师】！")
                        player.sendMessage("§e[韩济世]: §f老夫后继有人了！这初阶的医术和医旗就交付给你了，拿去绘制台自己绘制吧！")
                        // 1. 给素布旗 (原有逻辑)
                        val weapon = rm.getItem("subuqi") ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.WHITE_BANNER).apply {
                            itemMeta = itemMeta?.apply { setDisplayName("§c[配置缺失] subuqi") }
                        }
                        weapon.amount = 5
                        leftovers.putAll(player.inventory.addItem(weapon))
                        // 2. 发放医术：愈合花
                        // 注意：这里的 plugin 是你的主类实例，请根据你当前类的实际情况替换 (比如 plugin.medicalManager)
                        val yuhehua = plugin.medicalManager.getSkillBook("yuhehua")
                        if (yuhehua != null) {
                            // 默认 getSkillBook 出来的 amount 就是 1，直接给玩家即可
                            leftovers.putAll(player.inventory.addItem(yuhehua))
                        } else {
                            player.sendMessage("§c[配置缺失] 找不到医术：yuhehua")
                        }
                        // 3. 发放医术：退敌
                        val tuidi = plugin.medicalManager.getSkillBook("tuidi")
                        if (tuidi != null) {
                            leftovers.putAll(player.inventory.addItem(tuidi))
                        } else {
                            player.sendMessage("§c[配置缺失] 找不到医术：tuidi")
                        }
                    }
                }

                // 处理背包满的情况，把塞不下的掉在玩家脚下
                if (leftovers.isNotEmpty()) {
                    player.sendMessage("§c[提示] 背包已满，部分物品掉落在脚下！")
                    for (item in leftovers.values) {
                        player.world.dropItem(player.location, item)
                    }
                }
                // 返回 true，保存写入的 job 和 status 数据
                return true
            }
            // === 新增：奈何桥——重生石回皇城 ===
            "RELIVE_STONE_TO_CITY" -> {
                // 1. 扣除主手1个重生石
                val itemInHand = player.inventory.itemInMainHand
                itemInHand.amount = itemInHand.amount - 1

                // 2. 设置状态为 3
                data.updateStatus(3)
                restoreFullStatus(player, data)

                // 第一种情况的传送已经由 tryTeleport 通过 yml 中的 179.58 坐标自动完成了，无需在代码写传送
                return true
            }

            // === 新增：奈何桥——重生石回种族 ===
            "RELIVE_STONE_TO_RACE" -> {
                // 1. 扣除主手1个重生石
                val itemInHand = player.inventory.itemInMainHand
                itemInHand.amount = itemInHand.amount - 1
                // 2. 设置状态为 3
                data.updateStatus(3)
                // 3. 执行种族动态传送 (覆盖 yml 的空坐标)
                val world = player.world
                val raceId = data.race ?: 0
                val targetLoc = when (raceId) {
                    0 -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f)
                    1 -> org.bukkit.Location(world, 3179.5, 127.0, 783.5, -90f, 0f)
                    2 -> org.bukkit.Location(world, 1689.5, 140.0, 138.5, 90f, 0f)
                    3 -> org.bukkit.Location(world, 3299.5, 22.0, -138.5, 90f, 0f)
                    4 -> org.bukkit.Location(world, 2845.5, 48.0, 899.5, 180f, -20f)
                    else -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f) // 默认去种族0
                }
                // 执行传送
                player.teleport(targetLoc)
                restoreFullStatus(player, data)
                return true
            }

            // === 新增：奈何桥——无重生石打回种族 ===
            "NO_STONE_TO_RACE" -> {
                // 1. 设置状态为 3
                data.updateStatus(3)
                // 2. 执行种族动态传送 (覆盖 yml 的空坐标)
                val world = player.world
                val raceId = data.race ?: 0
                val targetLoc = when (raceId) {
                    0 -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f)
                    1 -> org.bukkit.Location(world, 3179.5, 127.0, 783.5, -90f, 0f)
                    2 -> org.bukkit.Location(world, 1689.5, 140.0, 138.5, 90f, 0f)
                    3 -> org.bukkit.Location(world, 3299.5, 22.0, -138.5, 90f, 0f)
                    4 -> org.bukkit.Location(world, 2845.5, 48.0, 899.5, 180f, -20f)
                    else -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f) // 默认去种族0
                }
                // 执行传送
                player.teleport(targetLoc)
                // 3. 扣除 20% 的当前经验
                val currentExp = data.exp
                val deductExp = (currentExp * 0.2).toInt()
                data.exp = (currentExp - deductExp).coerceAtLeast(0) // 防止变负数
                // 为了让经验条立即在原版 UI 刷新，可以给个 0 经验触动一下 PlayerManager 的经验刷新逻辑
                plugin.playerManager.giveExp(player, 0)
                // 4. 给予持续 1 分钟的缓慢 2 (1分钟 = 1200 Tick，缓慢2 = amplifier 为 1)
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 1200, 1, false, false))
                return true
            }
        }

        return false
    }

    /**
     * 仅供消耗重生石成功重生的流程调用。
     * 除生命、饥饿等状态外，同时回满当前动态灵力上限并向全服广播。
     */
    private fun restoreFullStatus(player: Player, data: PlayerData) {
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = maxHealth.coerceAtLeast(1.0)
        player.foodLevel = 20
        player.saturation = 20.0f
        player.exhaustion = 0.0f
        player.fireTicks = 0
        player.remainingAir = player.maximumAir
        player.addPotionEffect(PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 255, false, false))
        player.addPotionEffect(PotionEffect(PotionEffectType.SATURATION, 200, 255, false, false))
        data.lingli = data.maxLingli
        Bukkit.broadcast(
            Component.text(player.name, NamedTextColor.YELLOW)
                .append(Component.text("重生了", NamedTextColor.GREEN))
        )
    }

    private companion object {
        const val QIXI_QUEST_ID = "side_qixi_starwish"
        val SACRED_BEAST_TRIALS = listOf(
            "dragon_test" to "青龙",
            "zhuque_test" to "朱雀",
            "baihu_test" to "白虎",
            "xuanwu_test" to "玄武"
        )
    }

}

