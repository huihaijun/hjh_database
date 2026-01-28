package com.hjh_database.quest.impl.main.ren

import com.hjh_database.quest.core.QuestBase
import com.hjh_database.quest.core.QuestType
import com.hjh_database.quest.core.StoryNpcs
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.HashMap

class Ren_01 : QuestBase("main_ren_1", "[人族主线]初出茅庐", QuestType.MAIN, 1) {

    override val raceLimit = 2
    override val description = listOf("§7请找到 §e新手引导员-小仁 §7并与他对话。")

    private val talkProgress = HashMap<UUID, Int>()

    // 修改为指定的对话内容
    private val script = listOf(
        "§e[${StoryNpcs.REN_xiaoren.displayName}§e] §f你好族人，很高兴遇到你！",
        "§e[${StoryNpcs.REN_xiaoren.displayName}§e] §f看你正当年，是不是要出去闯荡了啊？",
        "§e[${StoryNpcs.REN_xiaoren.displayName}§e] §f先去找村长吧，他会给你一些指引。",
        "§e[${StoryNpcs.REN_xiaoren.displayName}§e] §f拿着这张引荐信交给村长，他会告诉你接下来怎么做的！"
    )

    override fun getProgressText(progress: Int): List<String> {
        return if (progress >= 1) listOf("§a已完成对话") else listOf("§c与小仁对话 (0/1)")
    }

    override fun checkComplete(progress: Int): Boolean {
        return progress >= 1
    }

    override fun giveReward(player: Player) {
        val plugin = com.hjh_database.Hjh_database.instance

        // 1. 物品奖励：小仁的引荐信 (硬编码)
        val letter = ItemStack(Material.PAPER)
        val meta = letter.itemMeta
        if (meta != null) {
            meta.setDisplayName("§b小仁的引荐信")
            val lore = ArrayList<String>()
            lore.add("§7§o这是一张由小仁写给村长的信，")
            lore.add("§7§o请将它交给村长吧。")
            lore.add("§e[任务物品]")
            meta.lore = lore
            letter.itemMeta = meta
        }

        // 尝试添加到背包，如果满了则掉落在地上
        val left = player.inventory.addItem(letter)
        if (left.isNotEmpty()) {
            player.sendMessage("§c[提示] 背包已满，物品已掉落在脚下。")
            for (item in left.values) {
                player.world.dropItem(player.location, item)
            }
        }

        // 2. 经验奖励：+10 EXP
        // 根据你的架构，获取 PlayerData 并直接修改 exp 字段
        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            data.exp += 10
            // 保存数据，确保经验不丢失
            // 如果你的 PlayerManager 有 checkLevelUp(player) 方法，建议在这里调用一下
            plugin.databaseManager.savePlayer(data)
        }

        // 3. 明显的完成提示
        player.sendMessage("§8§m========================================")
        player.sendMessage("   §a§l[任务完成] §f$title")
        player.sendMessage("")
        player.sendMessage("  §e[奖励] §f小仁的引荐信 x1")
        player.sendMessage("  §e[奖励] §f经验 +10")
        player.sendMessage("§8§m========================================")

        // 播放升级或完成音效
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)

        // 清理对话缓存
        talkProgress.remove(player.uniqueId)
    }

    override fun onNpcDialogue(player: Player, npcId: String, currentProgress: Int): Boolean {
        // === 修改点：使用枚举判断 ID (这里使用的是 REN_xiaoren) ===
        if (npcId == StoryNpcs.REN_xiaoren.id) {

            if (checkComplete(currentProgress)) return false

            val index = talkProgress.getOrDefault(player.uniqueId, 0)

            if (index < script.size) {
                // 替换掉名字里的颜色代码 & -> §
                val line = script[index].replace("&", "§")
                player.sendMessage(line)
                player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

                talkProgress[player.uniqueId] = index + 1

                if (index >= script.size - 1) {
                    player.sendMessage("§a[任务] -> 对话结束。")
                    // 对话全部读完，更新任务进度
                    com.hjh_database.Hjh_database.instance.questManager.updateProgress(player, id, 1)
                }
            }
            return true
        }
        return false
    }
}