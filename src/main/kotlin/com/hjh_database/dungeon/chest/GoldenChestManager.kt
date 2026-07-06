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
    val announceGlobal: Boolean = false  // 【新增】是否在抽到此物品时触发全服公告
)

data class GoldenChestConfig(
    val dungeonId: String,        // 副本ID (例如 "dragon_test")
    val displayName: String,      // 【新增】副本宝箱的中文显示名
    val keyResourceId: String,    // 需要消耗的钥匙ID
    val keyCost: Int,             // 每次开箱消耗几把
    val maxDrops: Int,            // 每次最多开出几个物品(项)
    val lootTable: List<ChestLootItem>
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
    }

    // 核心抽卡逻辑
    fun rollLoot(player: Player, dungeonId: String, record: DungeonRecord): List<ChestLootItem> {
        val config = chestRegistry[dungeonId] ?: return emptyList()
        val drops = mutableListOf<ChestLootItem>()
        val playerData = plugin.playerManager.getPlayerData(player)

        // 1. 过滤可掉落池 (剔除不符合职业要求和已经拿过 OneTime 的物品)
        val possibleItems = config.lootTable.filter {
            if (it.requiredJob != null && playerData?.job != it.requiredJob) return@filter false
            if (it.oneTimeOnly && record.dropCounts.getOrDefault(it.resourceId, 0) > 0) return@filter false
            true
        }.toMutableList() // 【修改】：转为可变列表，方便后续动态剔除

        // 2. 优先检查保底 (Pity)
        // 注意：如果你 maxDrops 是 1，刚好触发保底，那这一次机会就直接给保底物品了
        for (item in possibleItems.toList()) {
            if (item.pityOpens != null) {
                val sinceLast = record.opensSinceLastDrop.getOrDefault(item.resourceId, 0) + 1
                if (sinceLast >= item.pityOpens) {
                    drops.add(item)
                    // 如果保底出的是“仅限一次”的物品，从本次可抽取的池子里移出，防止后续普通随机再抽到
                    if (item.oneTimeOnly) possibleItems.remove(item)
                }
            }
        }

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
}