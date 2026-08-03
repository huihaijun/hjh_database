package com.hjh_database.title

data class QuestTitleReward(
    val questId: String,
    val titleId: String
)

object QuestTitleRewards {
    val human = QuestTitleReward("main_ren_9", "ren_xierenjianyanhuofushanhai")
    val shen = QuestTitleReward("main_shen_9", "shen_ziyunquexiawenrenjian")
    val yao = QuestTitleReward("main_yao_9", "yao_yelinggujichudexiwang")

    val regional = listOf(
        QuestTitleReward("main_south_5", "nan_lianxinzuihoudemoshengren"),
        QuestTitleReward("main_west_4", "xi_tiweiguirenbaopingan"),
        QuestTitleReward("main_north_5", "bei_huanxingxuanshuishengjideren")
    )

    val byQuestId = (listOf(human, shen, yao) + regional).associateBy { it.questId }

    fun forRace(race: Int?): QuestTitleReward? = when (race) {
        0 -> shen
        2 -> human
        4 -> yao
        else -> null
    }
}
