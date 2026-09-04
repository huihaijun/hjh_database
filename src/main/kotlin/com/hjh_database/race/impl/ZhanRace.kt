package com.hjh_database.race.impl

import com.hjh_database.race.RaceBase
import com.hjh_database.race.RaceManager
import com.hjh_database.spawner.impl.DesertSouthSkill
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/** 战神族天赋：战意领略。 */
class ZhanRace(manager: RaceManager) : RaceBase(manager) {

    private data class IntelligenceNpc(
        val displayName: String,
        val dialogue: List<String>
    )

    private val dialogueProgress = HashMap<UUID, HashMap<String, Int>>()

    override val raceId: Int = RACE_ID
    override val requiredQuestId: String = PROOF_QUEST_ID

    fun applyMonsterExpBonus(player: Player, baseExp: Int): Int =
        applyTwentyPercentBonus(player, baseExp)

    fun applyJianghuXindeBonus(player: Player, baseAmount: Int): Int =
        applyTwentyPercentBonus(player, baseAmount)

    /**
     * 情报网络使用固定眼线表，不占用 NPC 模板中的普通对话。
     * 返回 true 表示该 NPC 已被天赋逻辑接管（包括条件不足时的提示）。
     */
    fun handleIntelligenceDialogue(player: Player, npcId: String): Boolean {
        val intelligenceNpc = INTELLIGENCE_NPCS[npcId] ?: return false
        if (!isRaceActive(player)) {
            player.sendMessage("§c对方警惕地看着你，没有透露任何情报。")
            return true
        }
        if (manager.getResourceId(player.inventory.itemInMainHand) != PROOF_ITEM_ID) {
            player.sendMessage("§c请主手持有战神族证明，再与战神族眼线对话。")
            return true
        }

        val playerProgress = dialogueProgress.computeIfAbsent(player.uniqueId) { HashMap() }
        val index = (playerProgress[npcId] ?: 0).coerceIn(0, intelligenceNpc.dialogue.lastIndex)
        player.sendMessage("§e[${intelligenceNpc.displayName}§e] ${intelligenceNpc.dialogue[index]}")
        player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == intelligenceNpc.dialogue.lastIndex) {
            player.sendMessage("§c[提示] -> 对话已结束！")
            playerProgress[npcId] = 0
        } else {
            playerProgress[npcId] = index + 1
        }
        return true
    }

    /** 只免疫普通着火与持续燃烧；焱砂之火、岩浆、岩浆块和营火不在此列。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onOrdinaryFireDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.cause != EntityDamageEvent.DamageCause.FIRE &&
            event.cause != EntityDamageEvent.DamageCause.FIRE_TICK
        ) return
        if (!isRaceActive(player) || DesertSouthSkill.isAffected(player)) return

        event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        dialogueProgress.remove(event.player.uniqueId)
    }

    private fun applyTwentyPercentBonus(player: Player, baseAmount: Int): Int {
        val safeAmount = baseAmount.coerceAtLeast(0)
        if (!isRaceActive(player)) return safeAmount
        return (safeAmount.toLong() * BONUS_PERCENT / 100L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    companion object {
        const val RACE_ID = 3
        const val PROOF_QUEST_ID = "main_zhan_5"
        const val PROOF_ITEM_ID = "zhan_zm_begin"
        private const val BONUS_PERCENT = 120L

        private val INTELLIGENCE_NPCS = mapOf(
            "zhan_yanxian_chifeng" to IntelligenceNpc(
                displayName = "§a§l战神族眼线-赤锋",
                dialogue = listOf(
                    "§f战友，我是族里安插在南方§c焱砂大漠§f的眼线。情况紧急，长话短说。",
                    "§f此地酷热，连魔物身上都裹着一层§c火毒§f，打到你身上便会§c燃起灼心之火§f。不过我族早已习惯高温，若被点燃，§e蹲起两次§f，火便能灭。",
                    "§f这沙漠里有三个狠角色，都给我听好。",
                    "§f其一，§c凶神太岁§f，就在客栈附近。体型巨大，护甲极高，会唤出§e三处流沙§f陷人，再遁入沙中，从你意想不到的地方冒出来。",
                    "§f其二，§c马贼团首领§f，你来时应该碰见过。横冲直撞，虎背熊腰，护甲硬得离谱。但这家伙是个§e一根筋§f——冲撞到墙上会把自己的甲§e撞碎§f，人也会撞晕。趁他病，要他命。",
                    "§f其三，在§e绿洲西方§f的废弃村庄。那儿有个神秘平台，会生出§c沙漠风暴§f——传说整个村庄就是被它毁的。这孽障所到之处狂风四起，还会卷起§e龙卷刃§f伤人。务必趁它§c蓄力§f时拉远距离，别贪刀。",
                    "§f此地虽荒，资源不少。§c凶神太岁§f体内的§e恶魂丹§f，是制作饰品的良材。§c马贼团首领§f和§c沙漠风暴§f分别藏着§e炎晶石§f和§e黄风眼§f，一个做武器，一个做防具。",
                    "§f路上见到冒§a绿光§f的石头和仙人掌，别急着走，用§e开物术§f采了。石头仙人掌，都是锻造和炼丹用得上的东西。",
                    "§f就说这么多。在这地方，把命看紧。"
                )
            ),
            "zhan_yanxian_zhenyue" to IntelligenceNpc(
                displayName = "§a§l战神族眼线-镇岳",
                dialogue = listOf(
                    "§f战友，我是族里安排在西边§e虎爪山脉§f的眼线。闲话少说，情况说给你听。",
                    "§f此地的§c虎瘴§f你应该见识了。别舍不得那点铜钱，多备些§e虎瘴丹§f傍身。虎瘴一旦叠满，神仙也救不了你。我族对恶劣环境虽有几分抵抗，能免去某个阶段的§c巨额伤害§f，可瘴气一旦侵入体内，任你本领再大也施展不开。",
                    "§f洞里有两个硬茬子。",
                    "§f其一，§c矿工亡魂§f。据传是当年被洞中怪物害死的矿工，怨念不散所化。速度极快，力道极强，正面硬来讨不到便宜。不过他的怨念说到底是§e思乡§f——你找到商人说的那种§e篝火§f，点燃后把它引过去。他看见火光就跟回了家一样，会安静下来，那时便好对付了。",
                    "§f其二，§c焦骨战士§f。擅长召唤§e骨剑§f伤人，你身上§c虎瘴§f越重，吃他的伤害就越高。好在那骨剑飞得不算快，看清轨迹能躲开，§c切记别硬抗§f。",
                    "§f这两个魔物掉落的材料都§e附着虎瘴§f，得用§e至纯之心§f洗练才能用。至纯之心洞内某些尚存善念的魔物会掉，但概率不大。更稳的法子是拿§c耐久报废的虎瘴装备§f，配上§e深井水§f，去§e白虎锻造台§f洗炼。",
                    "§f洞内蛛网上结的§e棉花§f能融入甲胄，墙上的§e白虎岩§f可以锻造装备。都别放过。",
                    "§f就说这些。此地凶险，把命看紧。"
                )
            ),
            "zhan_yanxian_hanjiang" to IntelligenceNpc(
                displayName = "§a§l战神族眼线-寒江",
                dialogue = listOf(
                    "§f战友，我是族里安排在北边§b玄水湖泊§f的眼线。情况说给你听。",
                    "§f此地临湖，§e湿气§f极重。不管是挨了魔物的打，还是在它们附近待久了，身上都会染上湿气。湿气本身不伤你，但会§c拖慢你的脚步§f。更要命的是，你想释放武器技能或阵法时，湿气会§c吞掉这次释放§f，让你有劲使不出。",
                    "§f好在湖边的§e水族村庄§f里有种§e祛湿丹§f，能大幅度压下湿气。别怕找不到，村里贴满了小广告。",
                    "§f从水族村庄出去，有块沙地。那上面有个魔物叫§c千里神射手§f，手里的弓能射百斤重矢，须臾间取人性命，盾牌都挡不实。那箭还会带着湿气一起§c侵染§f你——湿气越重，伤害越高。",
                    "§f想完全躲掉不现实。但你可以借§e障碍物§f缓冲——墙壁、树木，甚至潜入水下，都能卸掉几分箭矢的冲劲。击杀他之后，有机会从他手上夺下一种§e檀木§f，日后锻造武器用得上。",
                    "§f再往前，就是§c玄水湾§f了。里面的§c玄水魂§f你应该听过，会朝你§e连冲四次§f。好在速度不快，及时闪避就行。要是不慎被它抓上半空，§c赶紧跳跃挣脱§f——不然被摔到地上，后果极重。它身上那§e鳞片§f坚硬异常，拿来做护甲最合适不过。",
                    "§f这地方的材料也别忘了。锻造用的§e玄晶石§f、乱葬岗的§e白骨木§f，还有湖边和墙边的§e鱼骨§f、§e藤蔓§f，都是炼丹的好东西。",
                    "§f此地凶险，务必小心。"
                )
            )
        )
    }
}
