package com.hjh_database.dungeon.chest

import com.hjh_database.Hjh_database
import com.hjh_database.dungeon.DungeonRecord
import org.bukkit.entity.Player
import java.util.concurrent.ThreadLocalRandom

data class ChestLootItem(
    val resourceId: String,       // ResourceManager 中的物品ID
    val minAmount: Int,           // 最小掉落数量
    val maxAmount: Int,           // 最大掉落数量
    val weight: Double,           // 掉落权重
    val requiredJob: Int? = null, // 特定职业(如 0战士 1弓箭)才能开出，null不限制
    val oneTimeOnly: Boolean = false, // 是否此生只能开出一次
    val pityOpens: Int? = null,    // 保底次数：如果连续X次未开出，则下次必出
    val announceGlobal: Boolean = false,  // 【新增】是否在抽到此物品时触发全服公告
    val minimumOpen: Int = 1       // 至少第几次开箱才允许进入奖池
)

data class GoldenChestConfig(
    val dungeonId: String,        // 副本ID (例如 "dragon_test")
    val displayName: String,      // 【新增】副本宝箱的中文显示名
    val keyResourceId: String,    // 需要消耗的钥匙ID
    val keyCost: Int,             // 每次开箱消耗几把
    val maxDrops: Int,            // 每次最多开出几个物品(项)
    val lootTable: List<ChestLootItem>,
    // 不同难度可以各自累计开箱与保底，但共享终身一次物品的获得记录。
    val oneTimeScopeId: String? = null
)

class GoldenChestManager(private val plugin: Hjh_database) {

    val chestRegistry = HashMap<String, GoldenChestConfig>()

    init {
        registerChests()
    }

    private fun registerChests() {
        // 静态注册 dragon_test 金宝箱
        chestRegistry["dragon_test"] = GoldenChestConfig(
            dungeonId = "dragon_test",
            displayName = "青龙试炼", // 【新增】中文名指代副本名
            keyResourceId = "mijingyaoshi", // 钥匙ID 当前为秘境钥匙
            keyCost = 1,
            maxDrops = 1, // 每次开箱弹出1个物品
            lootTable = listOf(
                // 权重 100.0，慧识 青龙饰品
                ChestLootItem("huishi", 1, 1, 100.0)
            )
        )
        // 静态注册 朱雀试炼
        chestRegistry["zhuque_test"] = GoldenChestConfig(
            dungeonId = "zhuque_test",
            displayName = "朱雀试炼", // 【新增】中文名指代副本名
            keyResourceId = "mijingyaoshi", // 钥匙ID 当前为秘境钥匙
            keyCost = 1,
            maxDrops = 1, // 每次开箱弹出1个物品
            lootTable = listOf(
                // 权重 100.0，朱雀饰品
                ChestLootItem("yanxin", 1, 1, 100.0)
            )
        )
        // 静态注册 白虎试炼
        chestRegistry["baihu_test"] = GoldenChestConfig(
            dungeonId = "baihu_test",
            displayName = "白虎试炼", // 【新增】中文名指代副本名
            keyResourceId = "mijingyaoshi", // 钥匙ID 当前为秘境钥匙
            keyCost = 1,
            maxDrops = 1, // 每次开箱弹出1个物品
            lootTable = listOf(
                // 权重 100.0，朱雀饰品
                ChestLootItem("xiaofeng", 1, 1, 100.0)
            )
        )
        // 静态注册 玄武试炼
        chestRegistry["xuanwu_test"] = GoldenChestConfig(
            dungeonId = "xuanwu_test",
            displayName = "玄武试炼",
            keyResourceId = "mijingyaoshi",
            keyCost = 1,
            maxDrops = 1,
            lootTable = listOf(
                ChestLootItem("yuyuan", 1, 1, 100.0)
            )
        )

        val qixiHardId = "qixi_hard"
        val qixiEasyId = "qixi_easy"
        val qixiOneTimeScope = "qixi_shared_onetime"
        chestRegistry[qixiHardId] = GoldenChestConfig(
            dungeonId = qixiHardId,
            displayName = "鹊桥星愿·困难",
            keyResourceId = "mijingyaoshi",
            keyCost = 1,
            maxDrops = 1,
            oneTimeScopeId = qixiOneTimeScope,
            lootTable = qixiArtifactCores(weight = 15.0, pityOpens = 20) + listOf(
                ChestLootItem("lingyujian", 1, 1, 10.0),
                ChestLootItem("tongxinsuo", 4, 4, 20.0),
                ChestLootItem("yy_tongyong2", 6, 6, 20.0),
                ChestLootItem("xingsha", 3, 3, 20.0),
                ChestLootItem("mijingyaoshi", 2, 2, 10.0),
                ChestLootItem(
                    "luoyuxinghe", 1, 1, 5.0,
                    oneTimeOnly = true,
                    pityOpens = 40,
                    announceGlobal = true
                )
            )
        )
        chestRegistry[qixiEasyId] = GoldenChestConfig(
            dungeonId = qixiEasyId,
            displayName = "鹊桥星愿·简单",
            keyResourceId = "mijingyaoshi",
            keyCost = 1,
            maxDrops = 1,
            oneTimeScopeId = qixiOneTimeScope,
            lootTable = qixiArtifactCores(weight = 8.0, pityOpens = 40, minimumOpen = 5) + listOf(
                ChestLootItem("lingyujian", 1, 1, 5.0),
                ChestLootItem("tongxinsuo", 2, 2, 25.0),
                ChestLootItem("yy_tongyong2", 3, 3, 22.0),
                ChestLootItem("xingsha", 2, 2, 20.0),
                ChestLootItem("mijingyaoshi", 1, 1, 15.0),
                ChestLootItem(
                    "luoyuxinghe", 1, 1, 5.0,
                    oneTimeOnly = true,
                    pityOpens = 60,
                    announceGlobal = true
                )
            )
        )

        val shengShanId = "shengshan"
        chestRegistry[shengShanId] = GoldenChestConfig(
            dungeonId = shengShanId,
            displayName = "圣山",
            keyResourceId = "mijingyaoshi",
            keyCost = 1,
            maxDrops = 1,
            lootTable = listOf(
                ChestLootItem("shengshouhuiji", 4, 4, 25.0),
                ChestLootItem("lingyujian", 1, 1, 25.0),
                ChestLootItem("yy_tongyong2", 4, 4, 25.0),
                ChestLootItem("mijingyaoshi", 2, 2, 25.0)
            )
        )
    }

    private fun qixiArtifactCores(
        weight: Double,
        pityOpens: Int,
        minimumOpen: Int = 1
    ): List<ChestLootItem> = listOf(
        ChestLootItem("xingpei_lingyunsuo", 1, 1, weight, 0, true, pityOpens, true, minimumOpen),
        ChestLootItem("xingpei_queshuangling", 1, 1, weight, 1, true, pityOpens, true, minimumOpen),
        ChestLootItem("xingpei_tianheyi", 1, 1, weight, 2, true, pityOpens, true, minimumOpen),
        ChestLootItem("xingpei_queqiaoyin", 1, 1, weight, 3, true, pityOpens, true, minimumOpen)
    )

    // 核心抽卡逻辑
    fun rollLoot(
        player: Player,
        dungeonId: String,
        record: DungeonRecord,
        oneTimeRecord: DungeonRecord = record
    ): List<ChestLootItem> {
        val config = chestRegistry[dungeonId] ?: return emptyList()
        val drops = mutableListOf<ChestLootItem>()
        val playerData = plugin.playerManager.getPlayerData(player)

        // 1. 过滤可掉落池 (剔除不符合职业要求和已经拿过 OneTime 的物品)
        val possibleItems = config.lootTable.filter {
            if (it.requiredJob != null && playerData?.job != it.requiredJob) return@filter false
            if (record.opens < it.minimumOpen) return@filter false
            if (it.oneTimeOnly && oneTimeRecord.dropCounts.getOrDefault(it.resourceId, 0) > 0) return@filter false
            true
        }.toMutableList() // 【修改】：转为可变列表，方便后续动态剔除

        // 2. 优先检查保底 (Pity)
        // 注意：如果你 maxDrops 是 1，刚好触发保底，那这一次机会就直接给保底物品了
        val guaranteedItems = possibleItems.filter { item ->
            val pity = item.pityOpens ?: return@filter false
            record.opensSinceLastDrop.getOrDefault(item.resourceId, 0) + 1 >= pity
        }.sortedByDescending { it.pityOpens ?: 0 }
            .take(config.maxDrops)
        drops += guaranteedItems
        possibleItems.removeAll(guaranteedItems.toSet())

        // 3. 按权重随机抽取剩余次数
        var remainingRolls = config.maxDrops - drops.size
        while (remainingRolls > 0 && possibleItems.isNotEmpty()) {
            val totalWeight = possibleItems.sumOf { it.weight }
            var randomVal = ThreadLocalRandom.current().nextDouble() * totalWeight

            for (item in possibleItems.toList()) {
                randomVal -= item.weight
                if (randomVal <= 0) {
                    // 【修正】：去除了防重复判定，现在可以正常开出重复的物品了！
                    drops.add(item)

                    // 如果抽到的是“仅限一次”的孤品，立刻把它从本次奖池剔除
                    if (item.oneTimeOnly) {
                        possibleItems.remove(item)
                    }
                    break
                }
            }
            remainingRolls--
        }

        return drops
    }

    fun oneTimeScopeId(config: GoldenChestConfig): String = config.oneTimeScopeId ?: config.dungeonId

    /** 同时具有保底次数和全服公告的稀有奖励，统一称为“终极战利品”。 */
    fun isUltimateLoot(item: ChestLootItem): Boolean = item.pityOpens != null && item.announceGlobal

    fun oneTimeLootIds(dungeonId: String): List<String> = chestRegistry[dungeonId]?.lootTable
        ?.asSequence()
        ?.filter(ChestLootItem::oneTimeOnly)
        ?.map(ChestLootItem::resourceId)
        ?.distinct()
        ?.sorted()
        ?.toList()
        ?: emptyList()
}
