package com.hjh_database.medical.impl

import com.hjh_database.Hjh_database
import com.hjh_database.medical.MedicalTrial
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.ChiseledBookshelf
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.max
import kotlin.math.min

class LuoHeTrial(
    private val plugin: Hjh_database,
    override val player: Player
) : BukkitRunnable(), MedicalTrial, Listener {

    override val trialId = "luohe"

    private enum class Phase {
        STORY,
        PLAYING,
        TRANSITION,
        ENDED
    }

    private enum class Ailment(
        val displayName: String,
        val description: String
    ) {
        COLD(
            "§9寒邪",
            "§f若未处理：患者元气§c-2§f。"
        ),
        HEAT(
            "§c热邪",
            "§f若未处理：患者元气§c-2§f，药毒§d+1§f。"
        ),
        STASIS(
            "§4瘀毒",
            "§f若未处理：患者元气§c-1§f，下回合再损失§c1§f点元气。"
        ),
        DEFICIENCY(
            "§e气虚",
            "§f若未处理：患者元气§c-3§f，病势§c+1§f。"
        )
    }

    private enum class ThermalResidue(val displayName: String) {
        WARM("§6温性"),
        COOL("§b寒性")
    }

    private enum class TreatmentCard(
        val id: String,
        val displayName: String,
        val cost: Int,
        val maxUses: Int,
        val description: List<String>
    ) {
        WARM(
            "warm",
            "§6温阳针",
            1,
            3,
            listOf(
                "§f克制§9寒邪§f，令病势§a-2§f。",
                "§7施术后留下一轮§6温性余势§7。",
                "§7与寒性余势相逆时，药毒§d+2§7。"
            )
        ),
        COOL(
            "cool",
            "§b清心散",
            1,
            3,
            listOf(
                "§f克制§c热邪§f，令病势§a-2§f。",
                "§7施术后留下一轮§b寒性余势§7。",
                "§7与温性余势相逆时，药毒§d+2§7。"
            )
        ),
        BLOOD_DETOX(
            "blood_detox",
            "§c活血解毒",
            1,
            3,
            listOf(
                "§f克制§4瘀毒§f，令病势§a-2§f。",
                "§7刺络行血会使患者元气§c-1§7。"
            )
        ),
        TONIFY(
            "tonify",
            "§e扶正固本",
            1,
            3,
            listOf(
                "§f克制§e气虚§f，令病势§a-2§f。",
                "§7同时为患者恢复§a2§7点元气。"
            )
        ),
        HARMONIZE(
            "harmonize",
            "§a调和诸药",
            1,
            2,
            listOf(
                "§f药毒§a-2§f，并清除现有药性余势。",
                "§7下一张医术不会触发寒热药性相冲。"
            )
        ),
        DRASTIC(
            "drastic",
            "§d峻剂攻邪",
            2,
            1,
            listOf(
                "§f强行处理本轮首个病邪，包括整张重症。",
                "§f令病势§a-4§f。",
                "§7药毒§d+2§7；整场只能使用一次。"
            )
        );

        companion object {
            fun fromId(id: String): TreatmentCard? = entries.firstOrNull { it.id == id }
        }
    }

    private data class AilmentInstance(
        val type: Ailment,
        val severity: Int = 1
    ) {
        val displayName: String
            get() = if (severity > 1) "${type.displayName}§4【重症】" else type.displayName
    }

    private data class RoundSpec(val ailments: List<AilmentInstance>)

    private data class MedicalCase(
        val name: String,
        val rounds: List<RoundSpec>,
        val witness: List<List<TreatmentCard>>
    )

    private data class BlockKey(val x: Int, val y: Int, val z: Int) {
        companion object {
            fun from(location: Location): BlockKey =
                BlockKey(location.blockX, location.blockY, location.blockZ)
        }
    }

    private data class SourceShelf(
        val card: TreatmentCard,
        val slot: Int
    )

    private data class ShelfSnapshot(
        val location: Location,
        val contents: Array<ItemStack?>
    )

    private data class IntentInfo(
        val heading: String,
        val ailments: List<AilmentInstance>
    )

    private data class SimState(
        val vitality: Int,
        val disease: Int,
        val toxicity: Int,
        val lingeringStasis: Int,
        val thermalResidue: ThermalResidue?,
        val remainingUses: List<Int>
    )

    private data class TreatmentOutcome(
        val target: AilmentInstance?,
        val removedLayers: Int = 0
    )

    private var phase = Phase.STORY
    private var tick = 0
    private var trialTicksLeft = TOTAL_TRIAL_TICKS
    private var roundIndex = 0
    private var vitality = MAX_VITALITY
    private var disease = INITIAL_DISEASE
    private var toxicity = 0
    private var lingeringStasis = 0
    private var thermalResidue: ThermalResidue? = null
    private var thermalResidueSetRound = -1
    private var pendingSignature: List<TreatmentCard>? = null
    private var confirmationDeadlineTick = -1
    private var lastButtonInteractMillis = 0L
    private var lastIntentInteractMillis = 0L

    private val selectedCards = mutableListOf<TreatmentCard>()
    private val availableSourceCards = mutableSetOf<TreatmentCard>()
    private val remainingUses = TreatmentCard.entries.associateWith { it.maxUses }.toMutableMap()
    private val shelfSnapshots = mutableListOf<ShelfSnapshot>()
    private val shelfDisplays = mutableListOf<Entity>()
    private val intentDisplays = mutableListOf<Entity>()
    private val intentInteractions = mutableMapOf<java.util.UUID, IntentInfo>()

    private val cardKey = NamespacedKey(plugin, "luohe_trial_card")
    private val cardOwnerKey = NamespacedKey(plugin, "luohe_trial_card_owner")

    private val startLocation =
        Location(player.world, -320.89, 18.50, -697.18, 3511.98f, 3.00f)
    private val exitLocation =
        Location(player.world, -335.50, 18.00, -686.50)
    private val treatmentShelfLocation =
        Location(player.world, -318.0, 19.0, -697.0)
    private val confirmButtonLocation =
        Location(player.world, -319.0, 18.0, -697.0)

    private val sourceShelves = linkedMapOf(
        BlockKey(-319, 20, -695) to SourceShelf(TreatmentCard.WARM, slot = 1),
        BlockKey(-321, 20, -695) to SourceShelf(TreatmentCard.COOL, slot = 1),
        BlockKey(-320, 19, -695) to SourceShelf(TreatmentCard.BLOOD_DETOX, slot = 0),
        BlockKey(-322, 19, -695) to SourceShelf(TreatmentCard.TONIFY, slot = 4),
        BlockKey(-319, 18, -695) to SourceShelf(TreatmentCard.HARMONIZE, slot = 1),
        BlockKey(-321, 18, -695) to SourceShelf(TreatmentCard.DRASTIC, slot = 1)
    )
    private val sourceShelfKeys = sourceShelves.keys

    private var rounds: List<RoundSpec> = emptyList()

    private val vitalityBar: BossBar = Bukkit.createBossBar(
        "§a患者元气：$MAX_VITALITY/$MAX_VITALITY",
        BarColor.GREEN,
        BarStyle.SEGMENTED_10
    )
    private val diseaseBar: BossBar = Bukkit.createBossBar(
        "§c病势：$INITIAL_DISEASE",
        BarColor.RED,
        BarStyle.SOLID
    )
    private val toxicityBar: BossBar = Bukkit.createBossBar(
        "§d药毒：0/$MAX_TOXICITY",
        BarColor.PURPLE,
        BarStyle.SEGMENTED_6
    )
    private val roundBar: BossBar = Bukkit.createBossBar(
        "§b辨证弈局：第1/${TOTAL_ROUNDS}回合",
        BarColor.BLUE,
        BarStyle.SOLID
    )

    private val storyMessages = listOf(
        "§f医师，你来得正好。水族久居深渊，虽通水脉灵息，却也有些病症并非寻常汤药能够看清。",
        "§f我会借祭坛阵法，将病人体内的§c病邪§f牵引出来，凝成肉眼可见的牌面。红毯是本回合，橙、黄两毯则是之后两回合的预兆；右键便能细看症候。",
        "§f你身后的六面§e雕纹书架§f中，各藏着一卷医术。右键抽出医书，再把选中的医书依次放进旁边那面§6施术书架§f。",
        "§f医书插入的先后，便是施术的先后。每回合只有§e两点行医之力§f，寻常医术耗费一点，§d峻剂攻邪§f则会独占整回合。",
        "§f医术并非取之不尽。四门对症医术各可动用§e三次§f，§a调和诸药§f可用两次，§d峻剂攻邪§f仅有一次。",
        "§f温阳与清心之法都会留下§e一轮药性余势§f。温寒骤然相逆，纵然对症也会§d药性相冲§f；§a调和诸药§f可散去余势，并压下药毒。",
        "§f病邪亦有轻重。标有§4重症§f者共有两层，寻常对症医术一次只能削去一层；§d峻剂攻邪§f则可将整张重症一并压下。",
        "§f若想改方，空手右键施术书架，便可从最后一手开始抽回。切莫只顾攻邪：患者§a元气§f耗尽，或§d药毒§f积满，都会断送性命。",
        "§f方子定下后按一次石钮，我会替你复核本轮医案；确认无误，须在§c十秒之内§f再按一次，方才正式施治。期间只要换过一卷医书，便要重新确认。",
        "§f祭坛每次牵引出的七轮病邪皆不相同，但必然留有可解之法。守过§e七轮§f并削尽§c病势§f，便算你辨证有方。静下心来，我们开始吧。"
    )

    override fun start() {
        player.teleport(startLocation)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        runTaskTimer(plugin, 0L, 1L)
    }

    override fun run() {
        if (!player.isOnline || player.isDead) {
            fail()
            return
        }

        when (phase) {
            Phase.STORY -> runStory()
            Phase.PLAYING -> {
                if (confirmationDeadlineTick >= 0 && tick > confirmationDeadlineTick) {
                    pendingSignature = null
                    confirmationDeadlineTick = -1
                    player.sendMessage("§7医案确认已经过时，请重新按下石钮复核。")
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.7f)
                }
            }
            else -> Unit
        }

        if (phase == Phase.PLAYING || phase == Phase.TRANSITION) {
            trialTicksLeft--
            if (trialTicksLeft <= 0) {
                player.sendMessage("§c五分钟已过，病邪仍未尽除！")
                fail()
                return
            }
            if (tick % 5 == 0) updateActionBar()
        }
        tick++
    }

    @EventHandler
    fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId || event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val blockKey = BlockKey.from(block.location)

        when {
            blockKey in sourceShelfKeys -> {
                event.isCancelled = true
                if (phase == Phase.PLAYING) {
                    if (blockKey in sourceShelves) {
                        handleSourceShelf(blockKey)
                    } else {
                        player.sendMessage("§7这格书架未存放医术。")
                    }
                }
            }
            sameBlock(block.location, treatmentShelfLocation) -> {
                event.isCancelled = true
                if (phase == Phase.PLAYING) handleTreatmentShelf()
            }
            sameBlock(block.location, confirmButtonLocation) -> {
                event.isCancelled = true
                if (phase == Phase.PLAYING) handleConfirmButton()
            }
        }
    }

    @EventHandler
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        if (event.rightClicked.uniqueId in intentInteractions) event.isCancelled = true
        handleIntentInteraction(event.player, event.rightClicked, event.hand)
    }

    @EventHandler
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) {
        if (event.rightClicked.uniqueId in intentInteractions) event.isCancelled = true
        handleIntentInteraction(event.player, event.rightClicked, event.hand)
    }

    @EventHandler
    fun onDropCard(event: PlayerDropItemEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (!isTrialCard(event.itemDrop.itemStack)) return
        event.isCancelled = true
        player.sendMessage("§c试炼医书不可丢弃；可将它放回原书架，或插入施术书架。")
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.whoClicked.uniqueId != player.uniqueId) return
        if (!isTrialCard(event.currentItem) && !isTrialCard(event.cursor)) return
        if (event.view.topInventory.holder == player) return
        event.isCancelled = true
        player.sendMessage("§c试炼医书不能放入其他容器。")
    }

    private fun runStory() {
        if (tick % STORY_MESSAGE_TICKS != 0) return
        val messageIndex = tick / STORY_MESSAGE_TICKS
        if (messageIndex < storyMessages.size) {
            player.sendMessage("§b§l水族祭司-洛禾 §f: ${storyMessages[messageIndex]}")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.05f)
            return
        }

        beginTrial()
    }

    private fun configuredCases(): List<MedicalCase> {
        return listOf(
            MedicalCase(
                name = "寒潮伏脉",
                rounds = listOf(
                    RoundSpec(listOf(AilmentInstance(Ailment.COLD))),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.STASIS),
                            AilmentInstance(Ailment.DEFICIENCY)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.HEAT),
                            AilmentInstance(Ailment.STASIS)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.HEAT),
                            AilmentInstance(Ailment.DEFICIENCY)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.COLD, severity = 2),
                            AilmentInstance(Ailment.STASIS)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.COLD),
                            AilmentInstance(Ailment.HEAT),
                            AilmentInstance(Ailment.DEFICIENCY)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.HEAT, severity = 2),
                            AilmentInstance(Ailment.STASIS),
                            AilmentInstance(Ailment.COLD)
                        )
                    )
                ),
                witness = listOf(
                    listOf(TreatmentCard.WARM),
                    listOf(TreatmentCard.BLOOD_DETOX, TreatmentCard.TONIFY),
                    listOf(TreatmentCard.HARMONIZE, TreatmentCard.COOL),
                    listOf(TreatmentCard.COOL, TreatmentCard.TONIFY),
                    listOf(TreatmentCard.WARM, TreatmentCard.BLOOD_DETOX),
                    listOf(TreatmentCard.WARM, TreatmentCard.TONIFY),
                    listOf(TreatmentCard.DRASTIC)
                )
            ),
            MedicalCase(
                name = "阴阳逆流",
                rounds = listOf(
                    RoundSpec(listOf(AilmentInstance(Ailment.HEAT))),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.STASIS),
                            AilmentInstance(Ailment.DEFICIENCY)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.COLD),
                            AilmentInstance(Ailment.STASIS)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.COLD),
                            AilmentInstance(Ailment.DEFICIENCY)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.HEAT, severity = 2),
                            AilmentInstance(Ailment.STASIS)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.HEAT),
                            AilmentInstance(Ailment.COLD),
                            AilmentInstance(Ailment.DEFICIENCY)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.HEAT, severity = 2),
                            AilmentInstance(Ailment.STASIS),
                            AilmentInstance(Ailment.COLD)
                        )
                    )
                ),
                witness = listOf(
                    listOf(TreatmentCard.COOL),
                    listOf(TreatmentCard.BLOOD_DETOX, TreatmentCard.TONIFY),
                    listOf(TreatmentCard.HARMONIZE, TreatmentCard.WARM),
                    listOf(TreatmentCard.WARM, TreatmentCard.TONIFY),
                    listOf(TreatmentCard.COOL, TreatmentCard.BLOOD_DETOX),
                    listOf(TreatmentCard.COOL, TreatmentCard.TONIFY),
                    listOf(TreatmentCard.DRASTIC)
                )
            ),
            MedicalCase(
                name = "瘀火交侵",
                rounds = listOf(
                    RoundSpec(listOf(AilmentInstance(Ailment.STASIS))),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.COLD),
                            AilmentInstance(Ailment.DEFICIENCY)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.HEAT),
                            AilmentInstance(Ailment.STASIS)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.STASIS),
                            AilmentInstance(Ailment.DEFICIENCY)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.COLD, severity = 2),
                            AilmentInstance(Ailment.HEAT)
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.HEAT),
                            AilmentInstance(Ailment.COLD),
                            AilmentInstance(Ailment.DEFICIENCY),
                        )
                    ),
                    RoundSpec(
                        listOf(
                            AilmentInstance(Ailment.STASIS, severity = 2),
                            AilmentInstance(Ailment.COLD),
                            AilmentInstance(Ailment.HEAT)
                        )
                    )
                ),
                witness = listOf(
                    listOf(TreatmentCard.BLOOD_DETOX),
                    listOf(TreatmentCard.WARM, TreatmentCard.TONIFY),
                    listOf(TreatmentCard.HARMONIZE, TreatmentCard.COOL),
                    listOf(TreatmentCard.BLOOD_DETOX, TreatmentCard.WARM),
                    listOf(TreatmentCard.WARM, TreatmentCard.COOL),
                    listOf(TreatmentCard.TONIFY, TreatmentCard.COOL),
                    listOf(TreatmentCard.DRASTIC)
                )
            )
        )
    }

    private fun generateSolvableRounds(): List<RoundSpec> {
        val validCases = configuredCases().filter { medicalCase ->
            isWitnessValid(medicalCase)
        }
        if (validCases.isEmpty()) {
            throw IllegalStateException("洛禾试炼的三套预设病案均未通过见证解校验")
        }
        val selected = validCases.random()
        plugin.logger.info("洛禾试炼本次采用病案：${selected.name}")
        return selected.rounds
    }

    private fun isWitnessValid(medicalCase: MedicalCase): Boolean {
        if (medicalCase.rounds.size != TOTAL_ROUNDS ||
            medicalCase.witness.size != TOTAL_ROUNDS
        ) {
            plugin.logger.severe("洛禾试炼病案 ${medicalCase.name} 的轮数配置不正确。")
            return false
        }

        var state = SimState(
            vitality = MAX_VITALITY,
            disease = INITIAL_DISEASE,
            toxicity = 0,
            lingeringStasis = 0,
            thermalResidue = null,
            remainingUses = TreatmentCard.entries.map { it.maxUses }
        )

        medicalCase.rounds.forEachIndexed { index, round ->
            val plan = medicalCase.witness[index]
            val startingVitality = state.vitality - state.lingeringStasis
            if (startingVitality <= 0 ||
                plan.isEmpty() ||
                plan.sumOf { it.cost } > MEDICAL_POINTS_PER_ROUND ||
                plan.distinct().size != plan.size ||
                plan.any { card -> state.remainingUses[card.ordinal] <= 0 }
            ) {
                plugin.logger.severe("洛禾试炼病案 ${medicalCase.name} 的第${index + 1}轮见证解无效。")
                return false
            }
            state = simulateRound(
                state.copy(vitality = startingVitality, lingeringStasis = 0),
                round,
                plan
            )
            if (state.vitality <= 0 || state.toxicity >= MAX_TOXICITY) {
                plugin.logger.severe("洛禾试炼病案 ${medicalCase.name} 的见证解会导致患者死亡。")
                return false
            }
        }

        val solved = state.disease <= 0
        if (!solved) {
            plugin.logger.severe(
                "洛禾试炼病案 ${medicalCase.name} 的见证解仍余 ${state.disease} 点病势。"
            )
        }
        return solved
    }

    private fun simulateRound(
        state: SimState,
        round: RoundSpec,
        plan: List<TreatmentCard>
    ): SimState {
        var vitality = state.vitality
        var disease = state.disease
        var toxicity = state.toxicity
        var residue = state.thermalResidue
        var residueSetThisRound = false
        var lingering = 0
        var preventConflict = false
        val remaining = round.ailments.toMutableList()
        val uses = state.remainingUses.toMutableList()

        fun damageVitality(amount: Int) {
            vitality -= amount
        }

        fun treat(type: Ailment): TreatmentOutcome {
            val index = remaining.indexOfFirst { ailment -> ailment.type == type }
            if (index < 0) return TreatmentOutcome(null)
            val target = remaining[index]
            if (target.severity <= 1) {
                remaining.removeAt(index)
            } else {
                remaining[index] = target.copy(severity = target.severity - 1)
            }
            disease -= 2
            return TreatmentOutcome(target, removedLayers = 1)
        }

        for (card in plan) {
            uses[card.ordinal]--
            when (card) {
                TreatmentCard.HARMONIZE -> {
                    toxicity = max(0, toxicity - 2)
                    residue = null
                    residueSetThisRound = false
                    preventConflict = true
                }
                TreatmentCard.DRASTIC -> {
                    val target = remaining.firstOrNull()
                    if (target != null) {
                        remaining.remove(target)
                        disease -= 4
                    }
                    toxicity += 2
                }
                TreatmentCard.WARM -> {
                    val residueConflict = residue == ThermalResidue.COOL
                    val outcome = treat(Ailment.COLD)
                    val wrongTreatment =
                        outcome.target == null &&
                            remaining.any { ailment -> ailment.type == Ailment.HEAT }
                    if ((residueConflict || wrongTreatment) && !preventConflict) toxicity += 2
                    residue = ThermalResidue.WARM
                    residueSetThisRound = true
                    preventConflict = false
                }
                TreatmentCard.COOL -> {
                    val residueConflict = residue == ThermalResidue.WARM
                    val outcome = treat(Ailment.HEAT)
                    val wrongTreatment =
                        outcome.target == null &&
                            remaining.any { ailment -> ailment.type == Ailment.COLD }
                    if ((residueConflict || wrongTreatment) && !preventConflict) toxicity += 2
                    residue = ThermalResidue.COOL
                    residueSetThisRound = true
                    preventConflict = false
                }
                TreatmentCard.BLOOD_DETOX -> {
                    damageVitality(1)
                    treat(Ailment.STASIS)
                    preventConflict = false
                }
                TreatmentCard.TONIFY -> {
                    vitality = min(MAX_VITALITY, vitality + 2)
                    treat(Ailment.DEFICIENCY)
                    preventConflict = false
                }
            }
        }

        for (ailment in remaining) {
            when (ailment.type) {
                Ailment.COLD -> damageVitality(2 * ailment.severity)
                Ailment.HEAT -> {
                    damageVitality(2 * ailment.severity)
                    toxicity += ailment.severity
                }
                Ailment.STASIS -> {
                    damageVitality(ailment.severity)
                    lingering += ailment.severity
                }
                Ailment.DEFICIENCY -> {
                    damageVitality(3 * ailment.severity)
                    disease += ailment.severity
                }
            }
        }

        if (!residueSetThisRound) residue = null
        return SimState(
            vitality = min(MAX_VITALITY, vitality),
            disease = max(0, disease),
            toxicity = max(0, toxicity),
            lingeringStasis = lingering,
            thermalResidue = residue,
            remainingUses = uses
        )
    }

    private fun beginTrial() {
        if (!validateTrialBlocks()) {
            player.sendMessage("§c试炼场中的书架、病邪标记或确认石钮缺失，试炼无法开始。")
            fail()
            return
        }

        try {
            rounds = generateSolvableRounds()
        } catch (exception: IllegalStateException) {
            plugin.logger.severe(exception.message ?: "洛禾试炼病案校验失败")
            player.sendMessage("§c祭坛病案未能正常凝成，请联系管理员检查配置。")
            fail()
            return
        }
        remainingUses.clear()
        TreatmentCard.entries.forEach { card -> remainingUses[card] = card.maxUses }
        captureShelfSnapshots()
        addBossBars()
        trialTicksLeft = TOTAL_TRIAL_TICKS
        updateBossBars()
        beginRound()
    }

    private fun validateTrialBlocks(): Boolean {
        val shelvesValid = sourceShelfKeys.all { key ->
            player.world.getBlockAt(key.x, key.y, key.z).type == Material.CHISELED_BOOKSHELF
        }
        val treatmentShelfValid =
            treatmentShelfLocation.block.type == Material.CHISELED_BOOKSHELF
        val buttonValid = confirmButtonLocation.block.type == Material.STONE_BUTTON
        val intentBlocksValid =
            player.world.getBlockAt(-321, 17, -698).type == Material.RED_WOOL &&
                player.world.getBlockAt(-321, 17, -700).type == Material.ORANGE_WOOL &&
                player.world.getBlockAt(-319, 17, -700).type == Material.YELLOW_WOOL
        return shelvesValid && treatmentShelfValid && buttonValid && intentBlocksValid
    }

    private fun captureShelfSnapshots() {
        shelfSnapshots.clear()
        val locations = sourceShelfKeys.map { key ->
            Location(player.world, key.x.toDouble(), key.y.toDouble(), key.z.toDouble())
        } + treatmentShelfLocation

        for (location in locations) {
            val shelf = location.block.state as? ChiseledBookshelf ?: continue
            val contents = shelf.inventory.contents.map { item -> item?.clone() }.toTypedArray()
            shelfSnapshots += ShelfSnapshot(location.clone(), contents)
        }
    }

    private fun beginRound() {
        if (roundIndex !in rounds.indices) {
            failByRemainingDisease()
            return
        }

        if (lingeringStasis > 0) {
            vitality -= lingeringStasis
            player.sendMessage("§4瘀毒未清，残留的血瘀令患者元气§c-$lingeringStasis§4！")
            lingeringStasis = 0
            if (checkImmediateFailure()) return
        }

        phase = Phase.PLAYING
        tick = 0
        resetRoundCards()
        refreshIntentDisplays()
        updateBossBars()
        updateActionBar()

        player.sendMessage("§b[辨证弈局] §f第§e${roundIndex + 1}§f/${rounds.size}回合开始。")
        player.sendMessage(
            "§f本回合病邪：${rounds[roundIndex].ailments.joinToString("§7、") { it.displayName }}"
        )
        player.sendMessage("§7右键红、橙、黄毯上方的病邪牌，可查看本轮与之后两轮的具体效果。")
        player.playSound(player.location, Sound.BLOCK_CHISELED_BOOKSHELF_PICKUP, 0.9f, 1.1f)
    }

    private fun resetRoundCards() {
        pendingSignature = null
        confirmationDeadlineTick = -1
        selectedCards.clear()
        removeTrialCards()
        clearShelf(treatmentShelfLocation)

        availableSourceCards.clear()
        clearShelfDisplays()
        spawnShelfLabels()
        for ((key, source) in sourceShelves) {
            val card = source.card
            val location = Location(player.world, key.x.toDouble(), key.y.toDouble(), key.z.toDouble())
            clearShelf(location)
            if ((remainingUses[card] ?: 0) <= 0) continue
            availableSourceCards += card
            setShelfItem(location, source.slot, createCardItem(card))
        }
    }

    private fun handleSourceShelf(key: BlockKey) {
        val source = sourceShelves[key] ?: return
        val card = source.card
        val heldCard = getTrialCard(player.inventory.itemInMainHand)

        if (heldCard != null) {
            if (heldCard != card) {
                player.sendMessage("§c这卷医书并不属于此处，请放回它原来的书架。")
                return
            }
            if (card in availableSourceCards) {
                player.sendMessage("§7这面书架中已经放着同一卷医书。")
                return
            }
            consumeOneFromMainHand()
            availableSourceCards += card
            setShelfItem(locationOf(key), source.slot, createCardItem(card))
            invalidateConfirmation()
            player.sendMessage("§a已将${card.displayName}§a放回原书架。")
            player.playSound(player.location, Sound.BLOCK_CHISELED_BOOKSHELF_INSERT, 0.9f, 1.1f)
            return
        }

        if ((remainingUses[card] ?: 0) <= 0) {
            player.sendMessage("§7${card.displayName}§7的可用次数已经耗尽。")
            return
        }
        if (card !in availableSourceCards) {
            player.sendMessage("§7这卷医书已经被取下。")
            return
        }

        val item = createCardItem(card)
        val leftovers = player.inventory.addItem(item)
        if (leftovers.isNotEmpty()) {
            player.sendMessage("§c背包已满，无法取下医书。")
            return
        }

        availableSourceCards -= card
        clearShelf(locationOf(key))
        invalidateConfirmation()
        player.sendMessage("§e已取下${card.displayName}§e：${card.description.first()}")
        player.playSound(player.location, Sound.BLOCK_CHISELED_BOOKSHELF_PICKUP, 0.9f, 1.15f)
    }

    private fun handleTreatmentShelf() {
        val heldCard = getTrialCard(player.inventory.itemInMainHand)
        if (heldCard != null) {
            if (heldCard in selectedCards) {
                player.sendMessage("§c同一卷医书不能重复插入。")
                return
            }
            if (selectedCards.size >= 2) {
                player.sendMessage("§c施术书架只能容纳第一手和第二手医术。")
                return
            }
            val usedPoints = selectedCards.sumOf { it.cost }
            if (usedPoints + heldCard.cost > MEDICAL_POINTS_PER_ROUND) {
                player.sendMessage("§c本回合只有两点行医之力，无法再加入这卷医书。")
                return
            }

            consumeOneFromMainHand()
            selectedCards += heldCard
            setShelfItem(treatmentShelfLocation, selectedCards.lastIndex, createCardItem(heldCard))
            invalidateConfirmation()
            updateActionBar()
            val handName = if (selectedCards.size == 1) "第一手" else "第二手"
            player.sendMessage("§a$handName 已定：${heldCard.displayName}§a。")
            player.playSound(player.location, Sound.BLOCK_CHISELED_BOOKSHELF_INSERT, 0.9f, 1.2f)
            return
        }

        if (selectedCards.isEmpty()) {
            player.sendMessage("§7施术书架中尚未放入医书。")
            return
        }

        val card = selectedCards.removeLast()
        clearShelfSlot(treatmentShelfLocation, selectedCards.size)
        val leftovers = player.inventory.addItem(createCardItem(card))
        leftovers.values.forEach { item ->
            player.world.dropItemNaturally(player.location, item)
        }
        invalidateConfirmation()
        updateActionBar()
        player.sendMessage("§e已从施术书架抽回${card.displayName}§e。")
        player.playSound(player.location, Sound.BLOCK_CHISELED_BOOKSHELF_PICKUP, 0.9f, 1.0f)
    }

    private fun handleConfirmButton() {
        val now = System.currentTimeMillis()
        if (now - lastButtonInteractMillis < 500L) return
        lastButtonInteractMillis = now

        if (selectedCards.isEmpty()) {
            player.sendMessage("§c施术书架中没有医书，无法拟定医案。")
            return
        }

        val signature = selectedCards.toList()
        if (signature.any { card -> (remainingUses[card] ?: 0) <= 0 }) {
            player.sendMessage("§c所选医术中已有医书耗尽，请重新拟定医案。")
            resetRoundCards()
            return
        }
        if (pendingSignature == signature && tick <= confirmationDeadlineTick) {
            resolveRound(signature)
            return
        }

        pendingSignature = signature
        confirmationDeadlineTick = tick + CONFIRMATION_TICKS
        val usedPoints = signature.sumOf { it.cost }
        val vitalityCost = signature.count { it == TreatmentCard.BLOOD_DETOX }
        val fixedToxicity = if (TreatmentCard.DRASTIC in signature) 2 else 0

        player.sendMessage("§d§l[医案复核]")
        signature.forEachIndexed { index, card ->
            val handName = if (index == 0) "第一手" else "第二手"
            player.sendMessage("§f$handName：${card.displayName} §7（行医之力 ${card.cost}）")
        }
        player.sendMessage("§f合计消耗行医之力：§e$usedPoints/$MEDICAL_POINTS_PER_ROUND")
        player.sendMessage("§f固定元气代价：§c$vitalityCost §7| §f固定药毒：§d+$fixedToxicity")
        player.sendMessage("§7寒热药性相冲造成的额外药毒，将依照本轮病邪结算。")
        player.sendMessage("§e确认无误，请在§c10秒§e内再次按下石钮正式医治。")
        player.playSound(confirmButtonLocation, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.1f)
    }

    private fun resolveRound(cards: List<TreatmentCard>) {
        phase = Phase.TRANSITION
        pendingSignature = null
        confirmationDeadlineTick = -1
        val remaining = rounds[roundIndex].ailments.toMutableList()
        var preventConflict = false

        cards.forEach { card ->
            remainingUses[card] = ((remainingUses[card] ?: 0) - 1).coerceAtLeast(0)
        }

        fun damageVitality(amount: Int) {
            vitality -= amount
        }

        fun treat(type: Ailment): TreatmentOutcome {
            val index = remaining.indexOfFirst { ailment -> ailment.type == type }
            if (index < 0) return TreatmentOutcome(null)
            val target = remaining[index]
            if (target.severity <= 1) {
                remaining.removeAt(index)
            } else {
                remaining[index] = target.copy(severity = target.severity - 1)
            }
            disease -= 2
            return TreatmentOutcome(target, removedLayers = 1)
        }

        fun announceTreatment(card: TreatmentCard, outcome: TreatmentOutcome, successText: String) {
            if (outcome.target == null) {
                player.sendMessage("${card.displayName}§7：本轮没有对应病邪，未能削减病势。")
                return
            }
            val layerText =
                if (outcome.target.severity > 1) "，削去一层重症" else ""
            player.sendMessage("${card.displayName}§f：$successText$layerText，病势§a-2§f。")
        }

        player.sendMessage("§b§l[本轮施治]")
        for (card in cards) {
            when (card) {
                TreatmentCard.HARMONIZE -> {
                    val reduced = min(2, toxicity)
                    toxicity -= reduced
                    thermalResidue = null
                    thermalResidueSetRound = -1
                    preventConflict = true
                    player.sendMessage("${card.displayName}§f：药毒§a-$reduced§f，药性余势已散，下一手免受药性相冲。")
                }
                TreatmentCard.DRASTIC -> {
                    val target = remaining.firstOrNull()
                    if (target == null) {
                        player.sendMessage("${card.displayName}§f：本轮已无病邪可攻，峻剂徒增药毒。")
                    } else {
                        remaining.remove(target)
                        disease -= 4
                        player.sendMessage("${card.displayName}§f：强行压下${target.displayName}§f，病势§a-4§f。")
                    }
                    toxicity += 2
                    preventConflict = false
                }
                TreatmentCard.WARM -> {
                    val residueConflict = thermalResidue == ThermalResidue.COOL
                    val outcome = treat(Ailment.COLD)
                    val wrongTreatment =
                        outcome.target == null && remaining.any { it.type == Ailment.HEAT }
                    if ((residueConflict || wrongTreatment) && !preventConflict) {
                        toxicity += 2
                        player.sendMessage("${card.displayName}§c：温寒药性相冲，药毒§d+2§c！")
                    }
                    announceTreatment(card, outcome, "温散寒邪")
                    thermalResidue = ThermalResidue.WARM
                    thermalResidueSetRound = roundIndex
                    preventConflict = false
                }
                TreatmentCard.COOL -> {
                    val residueConflict = thermalResidue == ThermalResidue.WARM
                    val outcome = treat(Ailment.HEAT)
                    val wrongTreatment =
                        outcome.target == null && remaining.any { it.type == Ailment.COLD }
                    if ((residueConflict || wrongTreatment) && !preventConflict) {
                        toxicity += 2
                        player.sendMessage("${card.displayName}§c：温寒药性相冲，药毒§d+2§c！")
                    }
                    announceTreatment(card, outcome, "清解热邪")
                    thermalResidue = ThermalResidue.COOL
                    thermalResidueSetRound = roundIndex
                    preventConflict = false
                }
                TreatmentCard.BLOOD_DETOX -> {
                    damageVitality(1)
                    val outcome = treat(Ailment.STASIS)
                    announceTreatment(card, outcome, "瘀毒渐散")
                    player.sendMessage("§7刺络代价：元气§c-1§7。")
                    preventConflict = false
                }
                TreatmentCard.TONIFY -> {
                    val healed = min(2, MAX_VITALITY - vitality)
                    vitality += healed
                    val outcome = treat(Ailment.DEFICIENCY)
                    announceTreatment(card, outcome, "正气渐复")
                    player.sendMessage("§7扶正之效：患者元气§a+$healed§7。")
                    preventConflict = false
                }
            }
        }

        resolveRemainingAilments(remaining)
        if (thermalResidue != null && thermalResidueSetRound < roundIndex) {
            thermalResidue = null
            thermalResidueSetRound = -1
        }
        disease = max(0, disease)
        vitality = min(MAX_VITALITY, vitality)
        toxicity = max(0, toxicity)
        updateBossBars()
        clearRoundCardsAfterResolution()

        if (checkImmediateFailure()) return

        roundIndex++
        if (roundIndex >= rounds.size) {
            if (disease <= 0) {
                win()
            } else {
                failByRemainingDisease()
            }
            return
        }

        player.sendMessage(
            "§7本轮结束：元气§a$vitality§7，病势§c$disease§7，药毒§d$toxicity§7。"
        )
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (phase == Phase.TRANSITION && player.isOnline) beginRound()
        }, 40L)
    }

    private fun resolveRemainingAilments(remaining: List<AilmentInstance>) {
        for (ailment in remaining) {
            when (ailment.type) {
                Ailment.COLD -> {
                    val damage = 2 * ailment.severity
                    vitality -= damage
                    player.sendMessage("§9寒邪侵脉§c：患者元气-$damage！")
                }
                Ailment.HEAT -> {
                    val damage = 2 * ailment.severity
                    vitality -= damage
                    toxicity += ailment.severity
                    player.sendMessage("§c热邪攻心：患者元气-$damage，药毒§d+${ailment.severity}§c！")
                }
                Ailment.STASIS -> {
                    vitality -= ailment.severity
                    lingeringStasis += ailment.severity
                    player.sendMessage("§4瘀毒阻络§c：患者元气-${ailment.severity}，并留下持续血瘀！")
                }
                Ailment.DEFICIENCY -> {
                    val damage = 3 * ailment.severity
                    vitality -= damage
                    disease += ailment.severity
                    player.sendMessage("§e正气亏虚§c：患者元气-$damage，病势+${ailment.severity}！")
                }
            }
        }
    }

    private fun clearRoundCardsAfterResolution() {
        selectedCards.clear()
        removeTrialCards()
        sourceShelfKeys.forEach { key -> clearShelf(locationOf(key)) }
        clearShelf(treatmentShelfLocation)
        availableSourceCards.clear()
        updateActionBar()
    }

    private fun checkImmediateFailure(): Boolean {
        if (vitality <= 0) {
            player.sendMessage("§c患者元气耗尽，祭坛上的生机彻底熄灭……")
            fail()
            return true
        }
        if (toxicity >= MAX_TOXICITY) {
            player.sendMessage("§c药毒已经积满，患者承受不住药毒侵袭！")
            fail()
            return true
        }
        return false
    }

    private fun failByRemainingDisease() {
        player.sendMessage("§c七轮已尽，患者体内仍余下§e$disease§c点病势。")
        fail()
    }

    private fun handleIntentInteraction(
        interactingPlayer: Player,
        entity: Entity,
        hand: EquipmentSlot
    ) {
        if (interactingPlayer.uniqueId != player.uniqueId || hand != EquipmentSlot.HAND) return
        val info = intentInteractions[entity.uniqueId] ?: return
        val now = System.currentTimeMillis()
        if (now - lastIntentInteractMillis < 150L) return
        lastIntentInteractMillis = now

        player.sendMessage("§d§l[${info.heading}]")
        if (info.ailments.isEmpty()) {
            player.sendMessage("§7此后已无新的病邪预兆。")
        } else {
            info.ailments.forEach { ailment ->
                player.sendMessage("${ailment.displayName} §7- ${ailment.type.description}")
                if (ailment.severity > 1) {
                    player.sendMessage("§4重症共有${ailment.severity}层；未处理效果按剩余层数叠加。")
                }
            }
        }
        player.playSound(entity.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.25f)
    }

    private fun refreshIntentDisplays() {
        clearIntentDisplays()
        val current = rounds[roundIndex].ailments
        val next = rounds.getOrNull(roundIndex + 1)?.ailments.orEmpty()
        val later = rounds.getOrNull(roundIndex + 2)?.ailments.orEmpty()
        spawnIntentDisplay(
            Location(player.world, -320.5, 18.15, -697.5),
            "§c本回合病邪",
            current
        )
        spawnIntentDisplay(
            Location(player.world, -320.5, 18.15, -699.5),
            "§6下一回合",
            next
        )
        spawnIntentDisplay(
            Location(player.world, -318.5, 18.15, -699.5),
            "§e之后第二回合",
            later
        )
    }

    private fun spawnIntentDisplay(
        location: Location,
        heading: String,
        ailments: List<AilmentInstance>
    ) {
        val itemDisplay = location.world.spawn(location, ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.PINK_DYE))
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.setRotation(0f, 0f)
            entity.addScoreboardTag("hjh_luohe_medical_trial")
        }
        intentDisplays += itemDisplay

        val names = if (ailments.isEmpty()) "§7无" else ailments.joinToString("§7、") { it.displayName }
        val serializer = LegacyComponentSerializer.legacySection()
        val textDisplay = location.world.spawn(
            location.clone().add(0.0, 0.72, 0.0),
            TextDisplay::class.java
        ) { entity ->
            entity.text(serializer.deserialize("$heading\n$names\n§7右键查看"))
            entity.billboard = Display.Billboard.FIXED
            entity.setRotation(0f, 0f)
            entity.isSeeThrough = false
            entity.isShadowed = true
            entity.addScoreboardTag("hjh_luohe_medical_trial")
        }
        intentDisplays += textDisplay

        val interaction = location.world.spawn(
            location.clone().add(0.0, 0.35, 0.0),
            Interaction::class.java
        ) { entity ->
            entity.interactionWidth = 1.15f
            entity.interactionHeight = 1.6f
            entity.isResponsive = true
            entity.addScoreboardTag("hjh_luohe_medical_trial")
        }
        intentDisplays += interaction
        intentInteractions[interaction.uniqueId] =
            IntentInfo(heading.replace(Regex("§."), ""), ailments)
    }

    private fun spawnShelfLabels() {
        clearShelfDisplays()
        val serializer = LegacyComponentSerializer.legacySection()
        for ((key, source) in sourceShelves) {
            val card = source.card
            val location = Location(
                player.world,
                key.x + 0.5,
                key.y + 0.16,
                key.z - 0.03,
                180f,
                0f
            )
            val display = player.world.spawn(location, TextDisplay::class.java) { entity ->
                val uses = remainingUses[card] ?: 0
                entity.text(
                    serializer.deserialize(
                        "${card.displayName}\n§7耗费：§e${card.cost}\n§7剩余：§f${uses}次"
                    )
                )
                entity.billboard = Display.Billboard.FIXED
                entity.setRotation(180f, 0f)
                entity.lineWidth = 400
                entity.isSeeThrough = false
                entity.isShadowed = true
                entity.addScoreboardTag("hjh_luohe_medical_trial")
            }
            shelfDisplays += display
        }
    }

    private fun createCardItem(card: TreatmentCard): ItemStack {
        return ItemStack(Material.BOOK).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(card.displayName)
                lore = buildList {
                    add("§7行医之力：§e${card.cost}")
                    add("§7本场剩余：§f${remainingUses[card] ?: 0}次")
                    addAll(card.description)
                    add("")
                    add("§8洛禾医术试炼专用医书")
                }
                persistentDataContainer.set(cardKey, PersistentDataType.STRING, card.id)
                persistentDataContainer.set(
                    cardOwnerKey,
                    PersistentDataType.STRING,
                    player.uniqueId.toString()
                )
            }
        }
    }

    private fun getTrialCard(item: ItemStack?): TreatmentCard? {
        if (item == null || item.type != Material.BOOK || !item.hasItemMeta()) return null
        val meta = item.itemMeta
        val owner = meta.persistentDataContainer.get(cardOwnerKey, PersistentDataType.STRING)
        if (owner != player.uniqueId.toString()) return null
        val id = meta.persistentDataContainer.get(cardKey, PersistentDataType.STRING) ?: return null
        return TreatmentCard.fromId(id)
    }

    private fun isTrialCard(item: ItemStack?): Boolean = getTrialCard(item) != null

    private fun consumeOneFromMainHand() {
        val item = player.inventory.itemInMainHand
        if (item.amount <= 1) {
            player.inventory.setItemInMainHand(null)
        } else {
            item.amount -= 1
            player.inventory.setItemInMainHand(item)
        }
    }

    private fun removeTrialCards() {
        val inventory = player.inventory
        for (slot in inventory.contents.indices) {
            val item = inventory.getItem(slot) ?: continue
            if (isTrialCard(item)) inventory.setItem(slot, null)
        }
        if (isTrialCard(player.itemOnCursor)) player.setItemOnCursor(null)

        player.world.getEntitiesByClass(Item::class.java).forEach { dropped ->
            if (isTrialCard(dropped.itemStack)) dropped.remove()
        }
    }

    private fun setShelfItem(location: Location, slot: Int, item: ItemStack) {
        val shelf = location.block.state as? ChiseledBookshelf ?: return
        shelf.inventory.setItem(slot, item)
    }

    private fun clearShelfSlot(location: Location, slot: Int) {
        val shelf = location.block.state as? ChiseledBookshelf ?: return
        shelf.inventory.setItem(slot, null)
    }

    private fun clearShelf(location: Location) {
        val shelf = location.block.state as? ChiseledBookshelf ?: return
        shelf.inventory.clear()
    }

    private fun invalidateConfirmation() {
        if (pendingSignature != null) {
            player.sendMessage("§7医案已经改动，先前的确认作废。")
        }
        pendingSignature = null
        confirmationDeadlineTick = -1
    }

    private fun addBossBars() {
        vitalityBar.addPlayer(player)
        diseaseBar.addPlayer(player)
        toxicityBar.addPlayer(player)
        roundBar.addPlayer(player)
    }

    private fun updateBossBars() {
        vitalityBar.progress = (vitality.toDouble() / MAX_VITALITY).coerceIn(0.0, 1.0)
        vitalityBar.setTitle("§a患者元气：§f${max(0, vitality)}/$MAX_VITALITY")

        diseaseBar.progress = (disease.toDouble() / INITIAL_DISEASE).coerceIn(0.0, 1.0)
        diseaseBar.setTitle("§c病势：§f${max(0, disease)}")

        toxicityBar.progress = (toxicity.toDouble() / MAX_TOXICITY).coerceIn(0.0, 1.0)
        toxicityBar.setTitle("§d药毒：§f${max(0, toxicity)}/$MAX_TOXICITY")

        roundBar.progress = ((roundIndex + 1).toDouble() / rounds.size).coerceIn(0.0, 1.0)
        roundBar.setTitle("§b辨证弈局：§f第${min(roundIndex + 1, rounds.size)}/${rounds.size}回合")
    }

    private fun updateActionBar() {
        if (!player.isOnline || phase == Phase.STORY || phase == Phase.ENDED) return
        val availablePoints =
            (MEDICAL_POINTS_PER_ROUND - selectedCards.sumOf { card -> card.cost }).coerceAtLeast(0)
        val secondsLeft = ((trialTicksLeft + 19) / 20).coerceAtLeast(0)
        val minutes = secondsLeft / 60
        val seconds = secondsLeft % 60
        val timeText = "%02d:%02d".format(minutes, seconds)
        val residueText = thermalResidue?.displayName ?: "§7无"
        player.sendActionBar(
            LegacyComponentSerializer.legacySection().deserialize(
                "§e行医之力：§f$availablePoints/$MEDICAL_POINTS_PER_ROUND §7| §f药性余势：$residueText §7| §b试炼剩余：§f$timeText"
            )
        )
    }

    private fun locationOf(key: BlockKey): Location =
        Location(player.world, key.x.toDouble(), key.y.toDouble(), key.z.toDouble())

    private fun sameBlock(first: Location, second: Location): Boolean {
        return first.world == second.world &&
            first.blockX == second.blockX &&
            first.blockY == second.blockY &&
            first.blockZ == second.blockZ
    }

    private fun win() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        cleanUp()
        player.teleport(exitLocation)
        player.sendMessage("§b§l水族祭司-洛禾 §f: §a病邪尽散，灵息复归清明。你能在寒热攻补之间守住分寸，§d天佑§a的法门，便交给你了。")
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        player.world.spawnParticle(Particle.HAPPY_VILLAGER, player.location.clone().add(0.0, 1.0, 0.0), 30, 0.5, 0.7, 0.5, 0.05)

        val data = plugin.playerManager.getPlayerData(player)
        if (data != null) {
            plugin.playerManager.giveExp(player, 300)
            data.learnMedicalSkill("tianyou")
            data.completedMedicalTrials.add(trialId)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    plugin.databaseManager.dataSource?.connection?.use { connection ->
                        plugin.databaseManager.saveMedicalData(connection, data)
                        plugin.databaseManager.saveCompletedMedicalTrials(connection, data)
                    }
                } catch (exception: Exception) {
                    plugin.logger.severe("保存洛禾医术试炼完成记录失败: ${exception.message}")
                }
            })
        }
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun fail() {
        if (phase == Phase.ENDED) return
        val shouldMessage = player.isOnline && !player.isDead
        phase = Phase.ENDED
        cleanUp()
        if (player.isOnline && !player.isDead) player.teleport(exitLocation)
        if (shouldMessage) player.sendMessage("§c水族祭司-洛禾的医术试炼失败！")
        plugin.medicalTrialManager.activeTrials.remove(player.uniqueId)
    }

    override fun cleanUp() {
        try {
            cancel()
        } catch (_: IllegalStateException) {
        }
        HandlerList.unregisterAll(this)
        vitalityBar.removeAll()
        diseaseBar.removeAll()
        toxicityBar.removeAll()
        roundBar.removeAll()
        clearIntentDisplays()
        clearShelfDisplays()
        removeTrialCards()
        restoreShelfSnapshots()
        if (player.isOnline) {
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(""))
        }
    }

    private fun restoreShelfSnapshots() {
        for (snapshot in shelfSnapshots) {
            val shelf = snapshot.location.block.state as? ChiseledBookshelf ?: continue
            shelf.inventory.contents = snapshot.contents.map { item -> item?.clone() }.toTypedArray()
        }
        shelfSnapshots.clear()
    }

    private fun clearIntentDisplays() {
        intentDisplays.forEach { entity ->
            if (entity.isValid) entity.remove()
        }
        intentDisplays.clear()
        intentInteractions.clear()
    }

    private fun clearShelfDisplays() {
        shelfDisplays.forEach { entity ->
            if (entity.isValid) entity.remove()
        }
        shelfDisplays.clear()
    }

    companion object {
        private const val MAX_VITALITY = 10
        private const val INITIAL_DISEASE = 20
        private const val MAX_TOXICITY = 6
        private const val MEDICAL_POINTS_PER_ROUND = 2
        private const val TOTAL_ROUNDS = 7
        private const val CONFIRMATION_TICKS = 10 * 20
        private const val STORY_MESSAGE_TICKS = 5 * 20
        private const val TOTAL_TRIAL_TICKS = 5 * 60 * 20
    }
}
