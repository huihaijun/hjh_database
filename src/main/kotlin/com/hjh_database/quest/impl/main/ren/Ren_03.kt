package com.hjh_database.quest.impl.main.ren

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.HashMap

class Ren_03 : QuestBase("main_ren_3", "[人族主线]工欲善其事", QuestType.MAIN, 3) {

    // 只有人族可接
    override val raceLimit = 2

    // 任务追踪描述
    override fun getProgressText(progress: Int): List<String> {
        return when (progress) {
            0 -> listOf("§c寻找 §e村长 §c对话")
            1 -> listOf("§a已与村长对话", "§c前往 §e铁匠铺 §c寻找掌柜")
            2 -> listOf("§a已获得材料", "§c制作 §b[新手鱼竿] §c并交给村长")
            else -> listOf("§a任务已完成")
        }
    }

    override val description = listOf(
        "§7村长似乎对你有新的教导。",
        "§7去听听关于人族更多的故事吧。"
    )

    // 临时记录对话索引
    private val talkProgress = HashMap<UUID, Int>()

    // ==========================================
    // 剧本配置
    // ==========================================

    // 1. 村长第一阶段对话
    private val scriptChiefStart = listOf(
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f这么重的山泉水一口气扛来，倒真有几分力气。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f我们人族便是如此——再难的环境，靠一股韧劲总能活下去。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f但人族能走到今天，不光靠自己，更靠“团结”二字。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f万众一心，山海可平。五族之中，唯我们最懂齐心之力。所以寿命虽短，却能创造诸多奇迹。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f想学团结，先学助人。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f你去村里铁匠铺，请掌柜打一竿鱼竿。东头孩子前两日缠着我要去钓鱼。",
        "§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f铺子就在你来时的路边，招牌显眼。怎么打造，掌柜自会教你。"
    )

    // 2. 铁匠对话 (需要确认 QuestNpcRegistry 中是否有 REN_SMITH，如果没有请记得添加)
    // 假设 ID 为 ren_smith
    private val scriptSmith = listOf(
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f村长让你来打鱼竿？",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f孩子们前几日才闹过，你这就来了，真是热心肠啊！",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f正好我这儿还剩些木料和线，你拿去用吧。锻造台在那边。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f不会？锻造可是咱人族吃饭的手艺！我只教一次，以后可别说不会啊。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f首先，找到锻造台，伸手一触，界面自开。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f台上分四类：武器、防具、法宝、杂项。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f每件物品都有配方，点进去就能看到所需材料。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f你锻造手艺越高，能造的东西就越多。每次成功，都会涨经验、提等级。",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f完成特定任务或造出珍品，还能提升“锻造资质”——这可是将来打造神器的重要凭证！",
        "§e[${StoryNpcs.REN_SMITH.displayName}§e] §f材料给你，鱼竿就在“杂项”里。打好后，送去给村长吧。"
    )

    // ==========================================
    // 逻辑处理
    // ==========================================

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        val plugin = Hjh_database.instance

        // --- 场景 1: 找村长 (接任务 或 交任务) ---
        if (npcId == StoryNpcs.REN_CHIEF.id) {

            // 阶段 0: 听村长讲道理
            if (currentProgress == 0) {
                playDialogue(player, scriptChiefStart) {
                    // 对话结束回调：进入下一阶段
                    plugin.questManager.updateProgress(player, id, 1)
                    player.sendMessage("§a[任务] -> 请前往铁匠铺寻找掌柜。")
                }
                return true
            }

            // 阶段 1: 还没找铁匠
            if (currentProgress == 1) {
                player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f铁匠铺就在路边，快去吧。")
                return true
            }

            // 阶段 2: 交鱼竿
            if (currentProgress == 2) {
                // 检查背包里有没有 [新手鱼竿] (ID: hjh_xsyg)
                if (checkAndRemoveQuestItem(player, "hjh_xsyg", "新手鱼竿", Material.FISHING_ROD)) {

                    player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f做得好！这鱼竿轻便结实，孩子们一定喜欢。")

                    // 完成任务
                    plugin.questManager.completeQuest(player, plugin.playerManager.getPlayerData(player)!!, this)
                } else {
                    player.sendMessage("§e[${StoryNpcs.REN_CHIEF.displayName}§e] §f鱼竿做好了吗？就在铁匠铺的锻造台里制作。")
//                    player.sendMessage("§7(提示：如果你弄丢了材料，可以找铁匠铺掌柜再要一份)")
                }
                return true
            }
        }

        // --- 场景 2: 找铁匠 (学锻造) ---
        if (npcId == StoryNpcs.REN_SMITH.id) {

            // 阶段 1: 听铁匠教学
            if (currentProgress == 1) {
                playDialogue(player, scriptSmith) {
                    // 对话结束回调：给材料，进下一阶段
                    giveMaterials(player)
                    plugin.questManager.updateProgress(player, id, 2)
                    player.sendMessage("§a[任务] -> 已获得材料，请寻找附近的锻造台制作鱼竿。")
                }
                return true
            }

            // 阶段 2: 补领材料 (人性化设计)
            if (currentProgress == 2) {
                // 如果玩家身上没有鱼竿，也没有材料，可以补发 (此处简化逻辑，只做提示)
                player.sendMessage("§e[${StoryNpcs.REN_SMITH.displayName}§e] §f快去试试锻造台吧，就在旁边。")
                return true
            }
        }

        return false
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    /**
     * 发放任务材料 (使用 ResourceManager)
     */
    private fun giveMaterials(player: Player) {
        val rm = Hjh_database.instance.resourceManager

        // 从资源管理器获取物品
        val stick = rm.getItem("hjh_ygmg") // 鱼竿木棍
        val line = rm.getItem("hjh_ygx")   // 鱼竿线

        if (stick == null || line == null) {
            player.sendMessage("§c[错误] 无法获取任务物品配置，请联系管理员！")
            return
        }

        // === 修改点1：设置线的数量为 3 ===
        line.amount = 3

        // 发送给玩家
        val inv = player.inventory

        // 尝试添加物品，addItem 会返回装不下的物品 Map (自动处理堆叠)
        val leftovers = inv.addItem(stick, line)

        // 如果有装不下的，丢在脚下
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包空间不足，部分材料已掉落在脚下！")
            for (item in leftovers.values) {
                player.world.dropItem(player.location, item)
            }
        }

        player.sendMessage("§e[系统] 获得 ${stick.itemMeta?.displayName ?: "木棍"} x1")
        player.sendMessage("§e[系统] 获得 ${line.itemMeta?.displayName ?: "线"} x3")

        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
    }

    /**
     * 检查并扣除任务物品
     * 优先匹配 ResourceID (PDC)，其次匹配 DisplayName (防止锻造后NBT丢失)
     */
    private fun checkAndRemoveQuestItem(player: Player, resourceId: String, namePart: String, type: Material): Boolean {
        val plugin = Hjh_database.instance
        // 注意：Resource ID 的 Key，需要和 ResourceManager 里的 keyId 保持一致
        // 在 ResourceManager 中是: NamespacedKey(plugin, "resource_id")
        val resourceKey = NamespacedKey(plugin, "resource_id")

        // 1. 先检查是否存在
        var foundSlot = -1

        for ((slot, item) in player.inventory.withIndex()) {
            if (item == null || item.type != type) continue
            val meta = item.itemMeta ?: continue

            var isMatch = false

            // 判定 A: 检查 PDC ID (最准确)
            if (meta.persistentDataContainer.has(resourceKey, PersistentDataType.STRING)) {
                val id = meta.persistentDataContainer.get(resourceKey, PersistentDataType.STRING)
                if (id == resourceId) {
                    isMatch = true
                }
            }

            // 判定 B: 如果没有PDC (可能是锻造结果丢失了NBT)，检查名字 (作为保底)
            if (!isMatch && meta.hasDisplayName()) {
                if (meta.displayName.contains(namePart)) {
                    isMatch = true
                }
            }

            if (isMatch) {
                foundSlot = slot
                break
            }
        }

        // 2. 如果找到了，扣除一个
        if (foundSlot != -1) {
            val item = player.inventory.getItem(foundSlot)
            if (item != null) {
                item.amount = item.amount - 1
                player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 1f)
                return true
            }
        }

        return false
    }

    /**
     * 简化的对话播放逻辑
     */
    private fun playDialogue(player: Player, scripts: List<String>, onFinish: () -> Unit) {
        val index = talkProgress.getOrDefault(player.uniqueId, 0)

        if (index < scripts.size) {
            player.sendMessage(scripts[index].replace("&", "§"))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

            talkProgress[player.uniqueId] = index + 1

            // 提示下一句
            if (index < scripts.size - 1) {
//                player.sendMessage("§a[任务] -> 点击左键继续...")
            } else {
                // 播放完毕
                talkProgress.remove(player.uniqueId)
                onFinish()
            }
        }
    }

    override fun giveReward(player: Player) {
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("§8§m========================================")

        // 发放经验
        val data = Hjh_database.instance.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 10
            Hjh_database.instance.databaseManager.savePlayer(data)
        }

        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    }

    // 任务完成后不再触发对话逻辑
    override fun checkComplete(progress: Int): Boolean {
        return progress >= 3
    }
}