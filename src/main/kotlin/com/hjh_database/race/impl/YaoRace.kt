package com.hjh_database.race.impl

import com.hjh_database.data.PlayerData
import com.hjh_database.race.RaceBase
import com.hjh_database.race.RaceManager
import org.bukkit.entity.Player

class YaoRace(manager: RaceManager) : RaceBase(manager) {

    override val raceId: Int = 4
    override val requiredQuestId: String = PROOF_QUEST_ID

    companion object {
        const val PROOF_QUEST_ID = "main_yao_5"
        private const val RACE_ID = 4

        fun isNatureSpiritActive(data: PlayerData): Boolean {
            return data.race == RACE_ID && data.completedQuests.contains(PROOF_QUEST_ID)
        }
    }

    fun getKaiwuSenseRange(player: Player, baseRange: Double): Double {
        return if (isRaceActive(player)) maxOf(baseRange, 20.0) else baseRange
    }

    fun getKaiwuMiningTimeMultiplier(player: Player): Double {
        return if (isRaceActive(player)) 0.8 else 1.0
    }

    fun ignoresDepletedPenalty(player: Player): Boolean {
        return isRaceActive(player)
    }

    override fun castActiveSkill(player: Player) = Unit
}
