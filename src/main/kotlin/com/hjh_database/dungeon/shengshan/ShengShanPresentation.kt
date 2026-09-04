package com.hjh_database.dungeon.shengshan

import com.hjh_database.client.ClientParticleLayer
import com.hjh_database.client.ClientParticleShape
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound

/**
 * 圣山只描述“要呈现什么”，粒子采样和持续刷新全部由 hjh_mod 完成。
 * 这些效果均由通用粒子图层组合，不要求客户端认识圣山或具体技能 ID。
 */
internal enum class ShengShanEffect {
    SPIRIT_GATHER,
    COCOON_BREATH,
    DIVINE_DESCENT,
    LIGHTNING_COLUMN,
    DARK_CONVERGE,
    ROAR_SHOCKWAVE,
    GROUND_RUPTURE,
    FALLING_DEBRIS,
    TRIGRAM_ARRAY,
    TRIGRAM_REVEAL,
    THUNDER_CHARGE,
    THUNDER_WARNING_RING,
    THUNDER_SLASH,
    THUNDER_STORM,
    THUNDER_TRAIL,
    THUNDER_BURST,
    SKY_CLOUD,
    SKY_FEATHER,
    SKY_LIGHT,
    SKY_WATERFALL,
    WATER_MIST,
    WATER_SHIELD,
    WATER_WAVE,
    WATER_CHAIN,
    WATER_FLOW,
    WATER_BURST,
    MOUNTAIN_DUST,
    MOUNTAIN_CRACK,
    MOUNTAIN_SHOCK,
    EARTH_PULSE,
    FIRE_CRACK,
    FIRE_WARNING_RING,
    FIRE_PATH_WARNING,
    FIRE_PATH_SPARSE,
    FIRE_SLASH,
    FIRE_LINE,
    FIRE_BURST,
    WIND_FIELD,
    WIND_TORNADO,
    WIND_TARGET_LOCK,
    WIND_PROJECTILE,
    WIND_BLADE,
    WIND_TRAIL,
    POISON_FOG,
    POISON_STREAM,
    POISON_TIDE,
    SWAMP_BUBBLE,
    SWAMP_SPLASH,
    SWAMP_OUTLINE,
    SWAMP_VENT_WARNING,
    SWAMP_VOLCANO,
    SOUL_FIELD,
    SOUL_SILHOUETTE,
    YIN_YANG_SLASH,
    GATE_PORTAL,
    SOUL_LINK,
    BUILD_GLOW,
    COLLAPSE,
    BREAK_SUCCESS
}

internal data class ShengShanEffectOptions(
    val radius: Double = 2.0,
    val height: Double = radius,
    val color: Int? = null,
    val durationTicks: Int = 1,
    val intervalTicks: Int = 2,
    val key: String = ""
)

internal fun ShengShanDungeonManager.playDungeonEffect(
    session: ShengShanSession,
    effect: ShengShanEffect,
    origin: Location,
    end: Location = origin,
    options: ShengShanEffectOptions = ShengShanEffectOptions()
) {
    val targets = activePlayers(session)
    if (targets.isEmpty()) return
    val layers = effectLayers(effect, options)
    if (options.durationTicks > 1) {
        if (options.key.isNotBlank()) session.clientEffectKeys += options.key
        plugin.clientBridge.emitTimedParticles(
            targets, origin, layers, end,
            options.durationTicks, options.intervalTicks, options.key, ignoreDistance = true
        )
    } else {
        plugin.clientBridge.emitParticles(targets, origin, layers, end, ignoreDistance = true)
    }
}

internal fun ShengShanDungeonManager.stopDungeonEffect(session: ShengShanSession, key: String) {
    val recipients = session.playerIds.mapNotNull(Bukkit::getPlayer)
    plugin.clientBridge.cancelTimedEffect(recipients, key)
    session.clientEffectKeys.remove(key)
}

internal fun ShengShanDungeonManager.clearDungeonEffectsFor(session: ShengShanSession, player: org.bukkit.entity.Player) {
    session.clientEffectKeys.forEach { key -> plugin.clientBridge.cancelTimedEffect(listOf(player), key) }
}

internal fun ShengShanDungeonManager.playDungeonSound(
    session: ShengShanSession,
    location: Location,
    sound: Sound,
    volume: Float = 1.0f,
    pitch: Float = 1.0f
) {
    // 在每位副本玩家本地播放，避免原版声源距离衰减导致远处玩家完全听不见。
    activePlayers(session).filter { it.world == location.world }
        .forEach { it.playSound(it.location, sound, volume, pitch) }
}

internal fun ShengShanDungeonManager.playNearbyDungeonSound(
    session: ShengShanSession,
    location: Location,
    sound: Sound,
    radius: Double,
    volume: Float = 1.0f,
    pitch: Float = 1.0f
) {
    val radiusSquared = radius.coerceAtLeast(1.0).let { it * it }
    activePlayers(session).filter { it.world == location.world && it.location.distanceSquared(location) <= radiusSquared }
        .forEach { it.playSound(location, sound, volume, pitch) }
}

internal fun ShengShanDungeonManager.sendElderHint(
    session: ShengShanSession,
    key: String,
    text: String,
    firstOnly: Boolean = true
) {
    if (firstOnly && !session.shownElderHints.add(key)) return
    sendElderLine(session, text)
}

internal fun ShengShanDungeonManager.sendElderLine(
    session: ShengShanSession,
    text: String,
    finalReveal: Boolean = false
) {
    activePlayers(session).forEach { player ->
        val speaker = if (finalReveal || plugin.playerManager.getData(player.uniqueId)?.race == 0) "神族长老" else "神秘神族"
        player.sendMessage("§b$speaker：§f$text")
    }
}

private fun effectLayers(effect: ShengShanEffect, options: ShengShanEffectOptions): List<ClientParticleLayer> {
    val r = options.radius
    val h = options.height
    fun dust(shape: ClientParticleShape, count: Int, color: Int, size: Float = 1.2f, radius: Double = r, height: Double = h) =
        ClientParticleLayer("minecraft:dust", shape, count, color, size, radius, height, 0.015)
    fun simple(id: String, shape: ClientParticleShape, count: Int, radius: Double = r, height: Double = h, speed: Double = 0.03) =
        ClientParticleLayer(id, shape, count, radius = radius, height = height, speed = speed)
    val override = options.color
    return when (effect) {
        ShengShanEffect.SPIRIT_GATHER -> listOf(dust(ClientParticleShape.LINE, 56, override ?: 0x77E6DD, 1.0f, .12, .12), simple("minecraft:end_rod", ClientParticleShape.LINE, 24, .08, .08))
        ShengShanEffect.COCOON_BREATH -> listOf(dust(ClientParticleShape.SPHERE, 52, override ?: 0xD9576A, 1.25f), simple("minecraft:reverse_portal", ClientParticleShape.CLOUD, 20))
        ShengShanEffect.DIVINE_DESCENT -> listOf(dust(ClientParticleShape.LINE, 72, override ?: 0x87CFFF, 1.3f, .18, .18), simple("minecraft:end_rod", ClientParticleShape.BURST, 42))
        ShengShanEffect.LIGHTNING_COLUMN -> listOf(simple("minecraft:electric_spark", ClientParticleShape.LINE, 96, .18, .18, .08), dust(ClientParticleShape.LINE, 54, override ?: 0xFFF18A, 1.4f, .1, .1))
        ShengShanEffect.DARK_CONVERGE -> listOf(dust(ClientParticleShape.LINE, 72, override ?: 0x262033, 1.45f, .25, .25), simple("minecraft:reverse_portal", ClientParticleShape.LINE, 46, .2, .2))
        ShengShanEffect.ROAR_SHOCKWAVE -> listOf(dust(ClientParticleShape.RING, 120, override ?: 0xE9E1D2, 1.5f), simple("minecraft:gust", ClientParticleShape.BURST, 48, r, h, .18))
        ShengShanEffect.GROUND_RUPTURE -> listOf(dust(ClientParticleShape.RING, 100, override ?: 0x76543A, 1.5f), simple("minecraft:ash", ClientParticleShape.BURST, 60, r, h, .12))
        ShengShanEffect.FALLING_DEBRIS -> listOf(dust(ClientParticleShape.CLOUD, 60, override ?: 0x776B61, 1.4f), simple("minecraft:ash", ClientParticleShape.CLOUD, 70, r, h, .08))
        ShengShanEffect.TRIGRAM_ARRAY -> listOf(dust(ClientParticleShape.RING, 160, override ?: 0xF4C95D, 1.45f), simple("minecraft:enchant", ClientParticleShape.SPHERE, 90, r, h))
        ShengShanEffect.TRIGRAM_REVEAL -> listOf(dust(ClientParticleShape.RING, 128, override ?: 0xFFFFFF, 1.65f), simple("minecraft:flash", ClientParticleShape.BURST, 12, r, h))
        ShengShanEffect.THUNDER_CHARGE -> listOf(simple("minecraft:electric_spark", ClientParticleShape.CLOUD, 34, r, h, .13), dust(ClientParticleShape.RING, 20, override ?: 0xFFE45A, 1.3f))
        ShengShanEffect.THUNDER_WARNING_RING -> listOf(dust(ClientParticleShape.RING, 40, override ?: 0xFFD84A, 1.35f), simple("minecraft:electric_spark", ClientParticleShape.CLOUD, 12, r * .55, h, .055))
        ShengShanEffect.THUNDER_SLASH -> listOf(dust(ClientParticleShape.LINE, 36, override ?: 0xFFF0A0, 1.25f, .12, .12), simple("minecraft:electric_spark", ClientParticleShape.LINE, 14, .18, .18, .08))
        ShengShanEffect.THUNDER_STORM -> listOf(simple("minecraft:large_smoke", ClientParticleShape.RING, 32, r, h, .025), simple("minecraft:electric_spark", ClientParticleShape.RING, 14, r, h, .07))
        ShengShanEffect.THUNDER_TRAIL -> listOf(simple("minecraft:electric_spark", ClientParticleShape.LINE, 30, .18, .18, .08), dust(ClientParticleShape.LINE, 20, override ?: 0xFFE45A, 1.15f, .1, .1))
        ShengShanEffect.THUNDER_BURST -> listOf(simple("minecraft:electric_spark", ClientParticleShape.BURST, 96, r, h, .22), simple("minecraft:flash", ClientParticleShape.BURST, 8))
        ShengShanEffect.SKY_CLOUD -> listOf(simple("minecraft:cloud", ClientParticleShape.CLOUD, 44, r, h, .04), dust(ClientParticleShape.SPHERE, 20, override ?: 0xF7F1D0, 1.1f))
        ShengShanEffect.SKY_FEATHER -> listOf(dust(ClientParticleShape.LINE, 20, override ?: 0xFFD86A, 1.15f, .10, .10), simple("minecraft:small_flame", ClientParticleShape.LINE, 8, .12, .12, .035))
        ShengShanEffect.SKY_LIGHT -> listOf(dust(ClientParticleShape.LINE, 88, override ?: 0xFFD35B, 1.45f, .16, .16), simple("minecraft:end_rod", ClientParticleShape.LINE, 42, .12, .12))
        ShengShanEffect.SKY_WATERFALL -> listOf(dust(ClientParticleShape.LINE, 20, override ?: 0xFFD35B, 1.35f, .1, .1), simple("minecraft:end_rod", ClientParticleShape.LINE, 7, .08, .08))
        ShengShanEffect.WATER_MIST -> listOf(simple("minecraft:splash", ClientParticleShape.CLOUD, 48, r, h, .08), simple("minecraft:bubble", ClientParticleShape.CLOUD, 32, r, h, .05))
        ShengShanEffect.WATER_SHIELD -> listOf(dust(ClientParticleShape.RING, 36, override ?: 0x54BFFF, 1.15f), simple("minecraft:splash", ClientParticleShape.RING, 14, r, h, .05))
        ShengShanEffect.WATER_WAVE -> listOf(dust(ClientParticleShape.LINE, 44, override ?: 0x3F9FE8, 1.3f, .38, .38), simple("minecraft:splash", ClientParticleShape.LINE, 24, .48, .48, .07))
        ShengShanEffect.WATER_CHAIN -> listOf(dust(ClientParticleShape.LINE, 26, override ?: 0x5CB9F2, 1.1f, .16, .16), simple("minecraft:bubble", ClientParticleShape.LINE, 10, .18, .18, .035))
        ShengShanEffect.WATER_FLOW -> listOf(simple("minecraft:splash", ClientParticleShape.LINE, 38, .42, .42, .09), dust(ClientParticleShape.LINE, 20, override ?: 0x4BA9FF, 1.15f, .22, .22))
        ShengShanEffect.WATER_BURST -> listOf(simple("minecraft:splash", ClientParticleShape.BURST, 100, r, h, .2), simple("minecraft:bubble", ClientParticleShape.SPHERE, 62, r, h, .1))
        ShengShanEffect.MOUNTAIN_DUST -> listOf(simple("minecraft:ash", ClientParticleShape.CLOUD, 36, r, h, .08), dust(ClientParticleShape.RING, 24, override ?: 0x8A6848, 1.35f))
        ShengShanEffect.MOUNTAIN_CRACK -> listOf(dust(ClientParticleShape.FAN, 36, override ?: 0x8A6848, 1.35f, r, .08), simple("minecraft:ash", ClientParticleShape.CLOUD, 12, r, h, .04))
        ShengShanEffect.MOUNTAIN_SHOCK -> listOf(dust(ClientParticleShape.LINE, 36, override ?: 0xB28B5D, 1.5f, .30, .22), simple("minecraft:ash", ClientParticleShape.LINE, 18, .36, .28, .08))
        ShengShanEffect.EARTH_PULSE -> listOf(dust(ClientParticleShape.RING, 40, override ?: 0x9B7652, 1.4f), simple("minecraft:ash", ClientParticleShape.BURST, 20, r, h, .1))
        ShengShanEffect.FIRE_CRACK -> listOf(dust(ClientParticleShape.RING, 96, override ?: 0x8F241A, 1.35f), simple("minecraft:small_flame", ClientParticleShape.CLOUD, 34, r, h, .04))
        ShengShanEffect.FIRE_WARNING_RING -> listOf(dust(ClientParticleShape.RING, 40, override ?: 0xD84424, 1.45f), simple("minecraft:small_flame", ClientParticleShape.RING, 10, r, h, .03))
        ShengShanEffect.FIRE_PATH_WARNING -> listOf(dust(ClientParticleShape.LINE, 14, override ?: 0xFF7A2E, 1.4f, .16, .16), simple("minecraft:small_flame", ClientParticleShape.LINE, 5, .18, .18, .025))
        ShengShanEffect.FIRE_PATH_SPARSE -> listOf(dust(ClientParticleShape.LINE, 11, override ?: 0xD13B24, 1.25f, .16, .10), simple("minecraft:small_flame", ClientParticleShape.LINE, 4, .17, .12, .025))
        ShengShanEffect.FIRE_SLASH -> listOf(dust(ClientParticleShape.LINE, 36, override ?: 0xFF6A2A, 1.5f, .20, .14), simple("minecraft:flame", ClientParticleShape.LINE, 16, .22, .16, .05))
        ShengShanEffect.FIRE_LINE -> listOf(dust(ClientParticleShape.LINE, 30, override ?: 0xD13B24, 1.35f, .18, .12), simple("minecraft:small_flame", ClientParticleShape.LINE, 15, .2, .15, .035))
        ShengShanEffect.FIRE_BURST -> listOf(simple("minecraft:flame", ClientParticleShape.BURST, 110, r, h, .2), simple("minecraft:flash", ClientParticleShape.BURST, 8))
        ShengShanEffect.WIND_FIELD -> listOf(simple("minecraft:cloud", ClientParticleShape.RING, 40, r, h, .07), simple("minecraft:gust", ClientParticleShape.BURST, 15, r, h, .12))
        ShengShanEffect.WIND_TORNADO -> listOf(simple("minecraft:cloud", ClientParticleShape.CLOUD, 30, r, h, .075), dust(ClientParticleShape.CLOUD, 16, override ?: 0xE9FAF6, 1.0f, r, h), simple("minecraft:gust", ClientParticleShape.RING, 8, r * .72, h, .1))
        ShengShanEffect.WIND_TARGET_LOCK -> listOf(dust(ClientParticleShape.RING, 12, override ?: 0xFF3A3A, 1.1f), simple("minecraft:crit", ClientParticleShape.RING, 4, r, h, .015))
        ShengShanEffect.WIND_PROJECTILE -> listOf(simple("minecraft:cloud", ClientParticleShape.RING, 24, r, h, .07), dust(ClientParticleShape.RING, 10, override ?: 0xE8F5F2, 1.0f))
        ShengShanEffect.WIND_BLADE -> listOf(dust(ClientParticleShape.RING, 48, override ?: 0xDDF4E7, 1.25f), simple("minecraft:gust", ClientParticleShape.RING, 12, r, h, .09))
        ShengShanEffect.WIND_TRAIL -> listOf(simple("minecraft:cloud", ClientParticleShape.LINE, 30, .35, .35, .06), dust(ClientParticleShape.LINE, 15, override ?: 0xDDEBFF, 1.0f, .2, .2))
        ShengShanEffect.POISON_FOG -> listOf(dust(ClientParticleShape.CLOUD, 40, override ?: 0x548D46, 1.2f), simple("minecraft:witch", ClientParticleShape.CLOUD, 20, r, h, .035))
        ShengShanEffect.POISON_STREAM -> listOf(dust(ClientParticleShape.LINE, 30, override ?: 0x6C9B45, 1.25f, .30, .30), simple("minecraft:witch", ClientParticleShape.LINE, 10, .24, .24, .035))
        ShengShanEffect.POISON_TIDE -> listOf(dust(ClientParticleShape.RING, 40, override ?: 0x6DAA48, 1.45f), simple("minecraft:witch", ClientParticleShape.CLOUD, 20, r, h, .04))
        ShengShanEffect.SWAMP_BUBBLE -> listOf(simple("minecraft:item_slime", ClientParticleShape.CLOUD, 64, r, h, .07), simple("minecraft:witch", ClientParticleShape.CLOUD, 36, r, h, .04))
        ShengShanEffect.SWAMP_SPLASH -> listOf(simple("minecraft:item_slime", ClientParticleShape.BURST, 42, r, h, .13), dust(ClientParticleShape.RING, 16, override ?: 0x7AAE43, 1.25f))
        ShengShanEffect.SWAMP_OUTLINE -> listOf(dust(ClientParticleShape.RING, 36, override ?: 0x779B3F, 1.35f), simple("minecraft:witch", ClientParticleShape.CLOUD, 8, r * .75, h, .025))
        ShengShanEffect.SWAMP_VENT_WARNING -> listOf(dust(ClientParticleShape.RING, 18, override ?: 0xF03A45, 1.3f), dust(ClientParticleShape.RING, 8, 0x83A84B, 1.05f), simple("minecraft:witch", ClientParticleShape.RING, 4, r, h, .015))
        ShengShanEffect.SWAMP_VOLCANO -> listOf(dust(ClientParticleShape.LINE, 26, override ?: 0x6D9D42, 1.35f, .24, .24), simple("minecraft:item_slime", ClientParticleShape.LINE, 12, .30, .30, .09), simple("minecraft:witch", ClientParticleShape.BURST, 14, r, h, .12))
        ShengShanEffect.SOUL_FIELD -> listOf(simple("minecraft:soul", ClientParticleShape.CLOUD, 74, r, h, .05), dust(ClientParticleShape.RING, 66, override ?: 0x51465F, 1.25f))
        ShengShanEffect.SOUL_SILHOUETTE -> listOf(dust(ClientParticleShape.SPHERE, 28, override ?: 0x4B4255, 1.15f), simple("minecraft:soul", ClientParticleShape.CLOUD, 12, r, h, .035))
        ShengShanEffect.YIN_YANG_SLASH -> listOf(dust(ClientParticleShape.LINE, 32, override ?: 0xEEE9DF, 1.35f, .20, .20), simple("minecraft:soul", ClientParticleShape.LINE, 8, .16, .16, .035))
        ShengShanEffect.GATE_PORTAL -> listOf(dust(ClientParticleShape.SPHERE, 32, override ?: 0x49305F, 1.35f), simple("minecraft:reverse_portal", ClientParticleShape.CLOUD, 14, r, h, .025))
        ShengShanEffect.SOUL_LINK -> listOf(simple("minecraft:soul", ClientParticleShape.LINE, 30, .18, .18, .03), dust(ClientParticleShape.LINE, 15, override ?: 0x725A88, 1.0f, .1, .1))
        ShengShanEffect.BUILD_GLOW -> listOf(dust(ClientParticleShape.RING, 74, override ?: 0xE7E7E7, 1.25f), simple("minecraft:end_rod", ClientParticleShape.CLOUD, 34, r, h, .03))
        ShengShanEffect.COLLAPSE -> listOf(simple("minecraft:ash", ClientParticleShape.BURST, 82, r, h, .16), dust(ClientParticleShape.BURST, 52, override ?: 0xB7B7B7, 1.3f))
        ShengShanEffect.BREAK_SUCCESS -> listOf(simple("minecraft:flash", ClientParticleShape.BURST, 10), simple("minecraft:end_rod", ClientParticleShape.BURST, 112, r, h, .22), dust(ClientParticleShape.RING, 120, override ?: 0xFFE16A, 1.6f))
    }
}
