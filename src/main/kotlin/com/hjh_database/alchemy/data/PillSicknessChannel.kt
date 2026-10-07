package com.hjh_database.alchemy.data

enum class PillSicknessChannel(val cooldownGroup: String) {
    STANDARD("alchemy_pill_sickness"),
    JUEZHANG("alchemy_juezhang_sickness"),
    QUSHI("alchemy_qushi_sickness"),
    JIEDU("alchemy_jiedu_sickness"),
    CHUKUI("alchemy_chukui_sickness"),
    GUIYUAN("alchemy_guiyuan_sickness"),
    ZHUSHI("alchemy_zhushi_sickness"),
    FEIDAN("alchemy_feidan_sickness");

    companion object {
        fun fromEffectId(effectId: String?, category: String?): PillSicknessChannel {
            val normalizedId = effectId?.lowercase().orEmpty()
            return when {
                normalizedId.startsWith("qushidan") -> QUSHI
                normalizedId == "jieduwan2" -> JIEDU
                else -> when (category?.uppercase()) {
                    "CHUKUI" -> CHUKUI
                    "GUIYUAN" -> GUIYUAN
                    "ZHUSHI" -> ZHUSHI
                    "FEIDAN" -> FEIDAN
                    else -> STANDARD
                }
            }
        }
    }
}
