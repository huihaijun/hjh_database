package com.hjh_database.dungeon.huomo

import org.bukkit.Material
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.util.Vector

internal class HuomoEvents(private val m: HuomoDungeonManager) : Listener {
    private fun source(event: EntityDamageEvent): Entity? = when (val d = (event as? EntityDamageByEntityEvent)?.damager) {
        is Projectile -> d.shooter as? Entity
        null -> event.damageSource.causingEntity
        else -> d
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun earlyDamage(event: EntityDamageEvent) = guard(event)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun finalDamage(event: EntityDamageEvent) = guard(event)
    private fun guard(event: EntityDamageEvent) {
        val s = m.session ?: return
        val attacker = source(event)
        if (m.belongs(s, event.entity)) {
            // 无来源环境伤害、旁观者、其他副本的怪物都不能影响本局目标。
            if (s.phase != HuomoPhase.FIGHT || attacker !is Player || !m.participant(s, attacker) ||
                attacker.isDead || attacker.world != s.world) event.isCancelled = true
            if (event.entity.uniqueId == s.core.uniqueId && (s.core.isInvulnerable || s.pendingStages.isNotEmpty() ||
                    s.pendingShifts > 0 || s.shiftStart != null)) event.isCancelled = true
        }
        if (attacker != null && m.belongs(s, attacker)) {
            val target = event.entity as? Player
            if (target == null || !m.participant(s, target) || s.phase != HuomoPhase.FIGHT ||
                (attacker.uniqueId == s.core.uniqueId && !s.internalDamage)) event.isCancelled = true
        }
        if ((event as? EntityDamageByEntityEvent)?.damager?.uniqueId == s.cast?.projectile?.uniqueId &&
            s.cast?.projectile != null) event.isCancelled = true
        // 岩浆块仅表示血液流动，不额外叠加原版烫脚伤害。
        val p = event.entity as? Player
        if (p != null && m.participant(s, p) && event.cause == EntityDamageEvent.DamageCause.HOT_FLOOR &&
            m.arena.veins.flatten().any { it.x == p.location.blockX && it.z == p.location.blockZ && p.location.y < 16.1 }) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun coreHit(event: EntityDamageEvent) {
        val s = m.session ?: return
        if (s.phase != HuomoPhase.FIGHT || event.entity.uniqueId != s.core.uniqueId || event.finalDamage <= 0) return
        s.pendingHeat += s.coreThermal.hit(s.tick, minOf(event.finalDamage, s.core.health))
        val crossings = s.milestones.observe(s.core.health - event.finalDamage)
        s.pendingStages.addAll(crossings.stages)
        s.pendingShifts += crossings.shifts
        if (s.pendingStages.isNotEmpty() || s.pendingShifts > 0) {
            s.core.isInvulnerable = true
            s.healthBar?.color = org.bukkit.boss.BarColor.BLUE
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun regen(event: EntityRegainHealthEvent) {
        val s = m.session ?: return
        if (m.belongs(s, event.entity)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun knockback(event: EntityKnockbackEvent) {
        val s = m.session ?: return
        if (m.belongs(s, event.entity) && event.entity.uniqueId !in s.mobs) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun target(event: EntityTargetLivingEntityEvent) {
        val s = m.session ?: return
        if (!m.belongs(s, event.entity)) return
        val p = event.target as? Player
        if (event.entity.uniqueId !in s.mobs || p == null || !m.participant(s, p) || p.isDead || p.world != s.world) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun projectile(event: ProjectileLaunchEvent) {
        val s = m.session ?: return
        val shooter = event.entity.shooter as? Entity ?: return
        if (m.belongs(s, shooter)) m.track(s, event.entity)
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun impact(event: ProjectileHitEvent) {
        val s = m.session ?: return
        if (!m.belongs(s, event.entity)) return
        if (event.entity.uniqueId == s.cast?.projectile?.uniqueId) { event.isCancelled = true; return }
        val p = event.hitEntity as? Player
        if (p != null && !m.participant(s, p)) { event.isCancelled = true; m.remove(s, event.entity) }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun split(event: SlimeSplitEvent) { if (m.owned(event.entity)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun combust(event: EntityCombustEvent) {
        val s = m.session ?: return
        if (m.belongs(s, event.entity)) { event.isCancelled = true; return }
        val by = (event as? EntityCombustByEntityEvent)?.combuster ?: return
        val shooter = (by as? Projectile)?.shooter as? Entity
        if (m.belongs(s, by) || shooter?.let { m.belongs(s, it) } == true) {
            val p = event.entity as? Player
            if (p == null || !m.participant(s, p) || by.uniqueId == s.cast?.projectile?.uniqueId) event.isCancelled = true
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun mobDeath(event: EntityDeathEvent) {
        if (!m.owned(event.entity)) return
        event.drops.clear(); event.droppedExp = 0
        val s = m.session ?: return
        if (!m.belongs(s, event.entity)) return
        if (event.entity.uniqueId == s.core.uniqueId) { m.victory(s); return }
        m.combat.seedKilled(s, event.entity.uniqueId)
        if (event.entity.uniqueId in s.mobs && s.phase == HuomoPhase.FIGHT) m.combat.corpse(s, event.entity.location)
        s.mobs.remove(event.entity.uniqueId); s.entities.remove(event.entity.uniqueId); s.born.remove(event.entity.uniqueId)
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun death(event: PlayerDeathEvent) {
        val s = m.session ?: return
        if (!m.participant(s, event.entity)) return
        event.drops.removeIf(m::temporary)
        m.detach(s, event.entity, null, event.entity.uniqueId in m.disconnecting)
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun quit(event: PlayerQuitEvent) {
        val s = m.session ?: return
        if (!m.participant(s, event.player)) return
        m.disconnecting += event.player.uniqueId
        try {
            if (s.phase == HuomoPhase.VICTORY) m.detach(s, event.player, 3, true)
            else m.fail(s, event.player, "§c中途离开服务器，火魔挑战失败！")
        } finally { m.disconnecting -= event.player.uniqueId }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun move(event: PlayerMoveEvent) {
        val s = m.session ?: return
        if (!m.participant(s, event.player)) return
        if (event.to.y - event.from.y > .10 && event.player.velocity.y > .1) {
            // 起跳边沿，持续上升不会刷新起跳时刻。
            if (s.tick - (s.lastJump[event.player.uniqueId] ?: -100) > 10) s.lastJump[event.player.uniqueId] = s.tick
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun explode(event: EntityExplodeEvent) {
        val s = m.session ?: return
        if (m.belongs(s, event.entity)) event.isCancelled = true
        else event.blockList().removeIf { it.world == s.world && m.arena.inside(it.location) }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun blockExplosion(event: BlockExplodeEvent) {
        val s = m.session ?: return
        event.blockList().removeIf { it.world == s.world && m.arena.inside(it.location) }
    }
    private fun protected(block: org.bukkit.block.Block): Boolean = m.session?.let {
        it.world == block.world && m.arena.inside(block.location)
    } == true
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun breakBlock(event: BlockBreakEvent) { if (protected(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun placeBlock(event: BlockPlaceEvent) { if (protected(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun emptyBucket(event: PlayerBucketEmptyEvent) { if (protected(event.blockClicked) || protected(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun fillBucket(event: PlayerBucketFillEvent) { if (protected(event.blockClicked) || protected(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun extend(event: BlockPistonExtendEvent) { if (protected(event.block) || event.blocks.any { protected(it) || protected(it.getRelative(event.direction)) }) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun retract(event: BlockPistonRetractEvent) { if (protected(event.block) || event.blocks.any(::protected)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun drop(event: PlayerDropItemEvent) {
        val s = m.session ?: return
        if (m.participant(s, event.player) && m.temporary(event.itemDrop.itemStack)) m.track(s, event.itemDrop)
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun pickup(event: EntityPickupItemEvent) {
        if (!m.temporary(event.item.itemStack)) return
        val s = m.session
        val p = event.entity as? Player
        if (s == null || p == null || !m.participant(s, p)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun ignite(event: BlockIgniteEvent) { if (protected(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun burn(event: BlockBurnEvent) { if (protected(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun flow(event: BlockFromToEvent) { if (protected(event.block) || protected(event.toBlock)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun entityChange(event: EntityChangeBlockEvent) { if (protected(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun plates(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL && event.clickedBlock?.let(::protected) == true) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun redstone(event: BlockRedstoneEvent) { if (protected(event.block)) event.newCurrent = 0 }
    @EventHandler(priority = EventPriority.MONITOR)
    fun loaded(event: EntitiesLoadEvent) {
        val s = m.session
        event.entities.filter { m.owned(it) && (s == null || !m.belongs(s, it) || s.phase == HuomoPhase.CLEANUP) }.forEach(Entity::remove)
    }
}
