package com.hjh_database.title

import org.bukkit.Material
import java.util.UUID

enum class TitleCategory(
    val fileName: String,
    val displayName: String,
    val icon: Material
) {
    CUSTOM("custom.yml", "自定义称号", Material.WHITE_WOOL),
    WANDERING("wandering.yml", "云游称号", Material.LIGHT_BLUE_WOOL),
    ENCOUNTER("encounter.yml", "奇遇称号", Material.LIME_WOOL),
    BOUNTY("bounty.yml", "赏金称号", Material.ORANGE_WOOL),
    DUNGEON("dungeon.yml", "秘境称号", Material.PURPLE_WOOL)
}

data class TitleDefinition(
    val id: String,
    val category: TitleCategory,
    val name: String,
    val lore: List<String>,
    val material: Material,
    val order: Int
)

data class OwnedTitle(
    val titleId: String,
    val customText: String?,
    val obtainedAt: Long,
    val obtainedSource: String
)

data class PlayerTitleProfile(
    val uuid: UUID,
    val playerName: String,
    val equippedTitleId: String?,
    val showChat: Boolean,
    val showOverhead: Boolean,
    val showTab: Boolean,
    val ownedTitles: Map<String, OwnedTitle>
)

data class TitleSettings(
    val maxCustomTitles: Int,
    val maxCustomNameLength: Int,
    val customCosts: List<Int>,
    val defaultShowChat: Boolean,
    val defaultShowOverhead: Boolean,
    val defaultShowTab: Boolean
)

data class TitleConfigSnapshot(
    val settings: TitleSettings,
    val definitions: Map<String, TitleDefinition>,
    val byCategory: Map<TitleCategory, List<TitleDefinition>>
)

data class ResolvedTitleTarget(val uuid: UUID, val playerName: String)

enum class TitleDisplayChannel {
    CHAT,
    OVERHEAD,
    TAB;

    companion object {
        fun parse(input: String): TitleDisplayChannel? = when (input.lowercase()) {
            "chat", "聊天", "聊天框" -> CHAT
            "overhead", "head", "头顶" -> OVERHEAD
            "tab", "列表" -> TAB
            else -> null
        }
    }
}

sealed interface CustomPurchaseResult {
    data class Success(val profile: PlayerTitleProfile, val titleId: String) : CustomPurchaseResult
    data class Rejected(val message: String) : CustomPurchaseResult
}
