package com.hjh_database.alchemy.data

enum class PillSicknessChannel(val cooldownGroup: String) {
    STANDARD("alchemy_pill_sickness"),
    JUEZHANG("alchemy_juezhang_sickness"),
    QUSHI("alchemy_qushi_sickness"),
    JIEDU("alchemy_jiedu_sickness");

    companion object {
        fun fromEffectId(effectId: String?): PillSicknessChannel {
            val normalizedId = effectId?.lowercase().orEmpty()
            return when {
                normalizedId == "juezhangdan" -> JUEZHANG
                normalizedId.startsWith("qushidan") -> QUSHI
                normalizedId.startsWith("jieduwan") -> JIEDU
                else -> STANDARD
            }
        }
    }
}
