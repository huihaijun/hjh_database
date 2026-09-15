package com.hjh_database.dungeon.zhenyao

import com.hjh_database.client.ClientParticleShape
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.util.Transformation
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.Player
import org.bukkit.block.BlockFace
import org.bukkit.block.data.MultipleFacing
import org.bukkit.attribute.Attribute
import org.bukkit.Sound
import java.util.UUID
import org.joml.Quaternionf
import org.joml.Vector3f

/** 妖气按九层循环；复用55条幽匿脉络展示，减益只写入本副本的临时属性条目。 */
internal class TowerFlood(private val f: ZhenyaoFinale) {
    private val surface = mutableListOf<BlockDisplay>()
    private var previousLevel = -1
    private val cycle = TowerFloodCycle()
    private val affected = hashSetOf<UUID>()
    private val debuffKeys = listOf("attack", "archer_damage", "zf_str", "armor").map { "zhenyao_flood::${it}_multiplier" }
    private val vein = (Material.SCULK_VEIN.createBlockData() as MultipleFacing).apply {
        setFace(BlockFace.UP, true); setFace(BlockFace.DOWN, true)
    }
    private val coverageBar = Bukkit.createBossBar("妖气覆盖：0/9层", BarColor.PURPLE, BarStyle.SOLID)
    fun tick(ticks: Long) {
        if (cycle.absorb(ticks)) {
            f.boss?.let { it.health = it.getAttribute(Attribute.MAX_HEALTH)?.value ?: 10000.0 }
            f.animateAbsorption()
            f.broadcast("§4妖气充盈全塔！蚩尤将妖气尽数吸收，恢复了全部生命；新的妖气正在塔底凝聚！")
            f.perform(Sound.ENTITY_WARDEN_ROAR, 0x20B8AE, ClientParticleShape.SPHERE, 5.0)
            previousLevel = -1
            surface.forEach { it.block = Material.AIR.createBlockData(); it.teleportDuration = 0 }
        }
        val time = ticks % TowerFinalRules.FLOOD_CYCLE_TICKS
        if (time % TowerFinalRules.FLOOD_LAYER_TICKS == TowerFinalRules.FLOOD_LAYER_TICKS - 200)
            f.broadcast("§6妖气即将侵染更高的楼层，尽快脱离妖气笼罩的区域！")
        val height = TowerFinalRules.floodHeight(ticks)
        val level = (time / TowerFinalRules.FLOOD_LAYER_TICKS).toInt()
        if (ticks % 10 == 0L) {
            coverageBar.setTitle("妖气侵染：${level}/9层${if (height > 5 + level * 15) "（蔓延中）" else ""}")
            coverageBar.progress = ((height - 5) / 129.0).coerceIn(0.0, 1.0)
            f.players().forEach(coverageBar::addPlayer)
        }
        if (level != previousLevel) {
            if (level > 0) f.broadcast("§6妖气已侵染${level}层，身处其中会使进攻和护甲降低10%！")
            previousLevel = level
        }
        val inside = f.players().filter {
            it.location.y < height && it.location.y >= 4 &&
                TowerFinalRules.insideOctagon(it.location.x, it.location.z, f.radiusAt(it.location.y))
        }.map { it.uniqueId }.toSet()
        (affected - inside).forEach { setDebuff(it, false) }
        (inside - affected).forEach { setDebuff(it, true) }
        if (height <= 5) return
        if (surface.isEmpty()) repeat(55) {
            val entity = f.world.spawn(Location(f.world, -1001.5, height, 2979.0 + it), BlockDisplay::class.java)
            f.own(entity); entity.brightness = Display.Brightness(15, 15)
            entity.teleportDuration = 10; entity.displayWidth = 60f; entity.displayHeight = 2f
            surface += entity
        }
        if (ticks % 10 == 0L) {
            val radius = f.radiusAt(height.coerceAtMost(132.0))
            surface.forEachIndexed { index, entity ->
                val z = 2979.0 + index
                val xs = (-1029..-975).filter { f.inAirSurface(it + .5, z + .5, radius) }
                if (xs.isEmpty()) entity.block = Material.AIR.createBlockData()
                else {
                    entity.block = vein
                    entity.teleport(Location(f.world, xs.first().toDouble(), height.coerceAtMost(132.8), z))
                    entity.teleportDuration = 10
                    entity.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(xs.size.toFloat(), .08f, 1f), Quaternionf())
                }
            }
        }
        if (ticks % 20 == 0L) f.players().filter { it.uniqueId in affected }.forEach {
            f.effect(it.location.clone().add(0.0, 1.0, 0.0), ClientParticleShape.CLOUD, 1.8, 0x8036A1, 12)
        }
    }
    private fun setDebuff(id: UUID, enabled: Boolean) {
        val data = f.plugin.playerManager.getData(id)
        if (enabled) {
            if (data == null) return
            affected.add(id)
            debuffKeys.forEach { data.tempBonuses[it] = .9 }
        } else {
            affected.remove(id)
            debuffKeys.forEach { data?.tempBonuses?.remove(it) }
        }
        Bukkit.getPlayer(id)?.takeIf { it.isOnline }?.let { player ->
            f.plugin.playerManager.updateStats(player)
            if (enabled) player.sendMessage("§5妖气蚀体：你的进攻和护甲降低10%，脱离妖气后恢复。")
        }
    }
    private fun ZhenyaoFinale.inAirSurface(x: Double, z: Double, radius: Double) = TowerFinalRules.insideOctagon(x, z, radius)
    fun removePlayer(player: Player) { coverageBar.removePlayer(player); setDebuff(player.uniqueId, false) }
    fun close() {
        affected.toList().forEach { setDebuff(it, false) }
        coverageBar.removeAll(); surface.forEach { it.remove() }; surface.clear()
    }
}
