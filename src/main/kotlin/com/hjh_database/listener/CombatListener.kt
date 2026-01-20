package com.hjh_database.listener

import com.hjh_database.Hjh_database
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.*
import kotlin.math.min

class CombatListener(private val plugin: Hjh_database) : Listener {
    private val armorKey = NamespacedKey(plugin, "hjh_mob_armor")
    private val weaponKey = NamespacedKey(plugin, "weapon_id")

    companion object {
        private const val TEST_DUMMY_TAG = "hjh_test_dummy"
    }

    private fun isWeaponSlotValid(player: Player, item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return true

        val meta = item.itemMeta ?: return true
        val weaponId = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING) ?: return true

        val wd = plugin.playerManager.weaponManager.getWeaponData(weaponId) ?: return true

        if (wd.activateSlot != -1 && player.inventory.heldItemSlot != wd.activateSlot) {
            return false
        }
        return true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        if (event.isCancelled) return

        // =========================================================
        // 【核心修复 A】 优先检测法术伤害标记 (HJH_MAGIC_DAMAGE)
        // =========================================================
        var isMagicDamage = false
        var magicBaseDamage = 0.0

        if (event.entity.hasMetadata("HJH_MAGIC_DAMAGE")) {
            isMagicDamage = true
            val metadataList = event.entity.getMetadata("HJH_MAGIC_DAMAGE")
            if (metadataList.isNotEmpty()) {
                magicBaseDamage = metadataList[0].asDouble()
            }
            // 立即清除标记
            event.entity.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
        }
        // =========================================================

        // 0. 横扫攻击检测
        if (event.cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            if (event is EntityDamageByEntityEvent) {
                val damager = event.damager
                if (damager is Player) {
                    val data = plugin.playerManager.getData(damager.uniqueId)
                    if (data == null || data.job == null || data.job != 0) {
                        event.isCancelled = true
                        return
                    }
                }
            }
        }

        // =========================================================
        // 【核心修复 B】 物理技能伤害 (WeaponSkill) 检测
        // =========================================================
        val isPhysicalSkill = event.entity.hasMetadata("hjh_physical_skill")
        if (isPhysicalSkill) {
            event.entity.removeMetadata("hjh_physical_skill", plugin)
        }

        var damage = event.damage

        // 如果是法术伤害，直接使用传递过来的数值，覆盖原始伤害
        if (isMagicDamage) {
            damage = magicBaseDamage
        }

        // 1. 攻击者逻辑 (物理伤害计算)
        // 只有当 [不是法术伤害] 且 [不是物理技能] 时，才执行这里的计算
        if (!isMagicDamage && !isPhysicalSkill && event is EntityDamageByEntityEvent) {
            val attacker = event.damager
            if (attacker is Player) {

                // 槽位检查
                val hand = attacker.inventory.itemInMainHand
                if (!isWeaponSlotValid(attacker, hand)) {
                    // 医师特化：如果是医师，不提示，静默取消
                    val data = plugin.playerManager.getData(attacker.uniqueId)
                    if (data != null && data.job != null && data.job == 3) {
                        event.isCancelled = true
                        return
                    }
                    // 其他人正常提示
                    event.isCancelled = true
                    attacker.sendMessage(ChatColor.RED.toString() + "武器未激活！请将武器移动到正确的槽位使用！")
                    attacker.playSound(attacker.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.5f)
                    return
                }

                val data = plugin.playerManager.getData(attacker.uniqueId)
                if (data != null) {
                    var isRpgWeapon = false
                    if (hand.hasItemMeta()) {
                        isRpgWeapon = hand.itemMeta!!.persistentDataContainer.has(weaponKey, PersistentDataType.STRING)
                    }

                    if (data.job != null && data.job == 0) { // 战士
                        val type = hand.type.name
                        if (type.endsWith("_SWORD") || type.endsWith("_AXE")) {
                            var baseAttack = data.getVal(data.attack)
                            val cooldown = attacker.attackCooldown
                            baseAttack *= cooldown.toDouble()
                            damage = baseAttack
                        }
                    }
                    // 【问题根源在这里】
                    // 如果是医师(非战士) + 拿医旗(RPG武器) -> 这里把 damage 变成了 1.0
                    // 但我们加了 !isMagicDamage 的判断，所以法术伤害会跳过这整段代码，保住了数值！
                    else if (isRpgWeapon) {
                        damage = 1.0
                    }

                    // 暴击逻辑
                    val critChance = min(0.8, data.getVal(data.critChance))
                    if (attacker.attackCooldown > 0.9F) {
                        if (Math.random() < critChance) {
                            damage *= 1.5
                            attacker.world.spawnParticle(Particle.CRIT, event.entity.location.add(0.0, 1.0, 0.0), 15)
                            attacker.playSound(attacker.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f)
                        }
                    }
                }
            } else if (attacker is AbstractArrow) {
                val shooter = attacker.shooter
                if (shooter is Player) {
                    val data = plugin.playerManager.getData(shooter.uniqueId)
                    if (data != null) {
                        val archerDmg = data.getVal(data.archerDamage)
                        val velocity = attacker.velocity.length()
                        damage = archerDmg * (min(3.0, velocity) / 3.0)
                        val critChance = min(0.8, data.getVal(data.critChance))
                        if (Math.random() < critChance) {
                            damage *= 1.5
                            attacker.isCritical = true
                            shooter.playSound(shooter.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f)
                        }
                    }
                }
            }
        }

        // 2. 受击者逻辑 (减伤)
        val victim = event.entity
        if (victim is LivingEntity) {
            // ... (原版护甲清理逻辑) ...
            // 1.21.3 适配: Attribute.GENERIC_ARMOR -> Attribute.ARMOR
            if (victim.getAttribute(Attribute.ARMOR) != null) {
                victim.getAttribute(Attribute.ARMOR)!!.baseValue = 0.0
            }

            val cause = event.cause
            val isMagic = (cause == EntityDamageEvent.DamageCause.MAGIC ||
                    cause == EntityDamageEvent.DamageCause.DRAGON_BREATH ||
                    cause == EntityDamageEvent.DamageCause.WITHER ||
                    cause == EntityDamageEvent.DamageCause.POISON)
            val isTrueDamage = (cause == EntityDamageEvent.DamageCause.VOID ||
                    cause == EntityDamageEvent.DamageCause.SUICIDE ||
                    cause == EntityDamageEvent.DamageCause.STARVATION)

            if (!isMagic && !isTrueDamage) {
                var armor = 0.0
                if (victim is Player) {
                    val data = plugin.playerManager.getData(victim.uniqueId)
                    if (data != null) armor = data.getVal(data.armor)
                } else {
                    if (victim.persistentDataContainer.has(armorKey, PersistentDataType.DOUBLE)) {
                        armor = victim.persistentDataContainer.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0
                    }
                }

                // === 【核心修复 C】如果是法术伤害，无视护甲 ===
                if (isMagicDamage || victim.hasMetadata("hjh_magic_damage")) {
                    armor = 0.0
                }
                // ==========================================

                if (armor < 0) armor = 0.0
                val multiplier = 50.0 / (50.0 + armor)
                damage *= multiplier
            }

            // 3. 测伤反馈
            if (victim.scoreboardTags.contains(TEST_DUMMY_TAG)) {
                if (event is EntityDamageByEntityEvent) {
                    var msgTarget: CommandSender? = null
                    val damager = event.damager

                    if (damager is Player) {
                        msgTarget = damager
                    } else if (damager is Projectile) {
                        val shooter = damager.shooter
                        if (shooter is Player) {
                            msgTarget = shooter
                        }
                    }

                    if (msgTarget != null) {
                        msgTarget.sendMessage(String.format(
                            ChatColor.YELLOW.toString() + "[测试] " + ChatColor.WHITE + "造成伤害: " + ChatColor.RED + "%.2f",
                            damage
                        ))
                    }
                }
            }
        }

        if (damage != event.damage) {
            event.damage = damage
        }
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val killer = entity.killer

        if (killer != null && !entity.scoreboardTags.contains(TEST_DUMMY_TAG)) {
            if (entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")) {
                val expAmount = plugin.playerManager.getMobExp() // 这里是 Kotlin 属性访问
                plugin.playerManager.giveExp(killer, expAmount)
                killer.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§e+ $expAmount 经验"))
            }
        }

        if (entity.scoreboardTags.contains(TEST_DUMMY_TAG)) {
            event.drops.clear()
            event.droppedExp = 0
            val loc = entity.location

            // 1.21.3 适配: GENERIC_MAX_HEALTH -> MAX_HEALTH
            val maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH)
            val maxHealth = maxHealthAttr?.value ?: 20.0

            var armor = 0.0
            if (entity.persistentDataContainer.has(armorKey, PersistentDataType.DOUBLE)) {
                armor = entity.persistentDataContainer.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0
            }
            val finalArmor = armor

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                loc.world?.spawn(loc, Creeper::class.java) { creeper ->
                    creeper.addScoreboardTag("panling")
                    creeper.addScoreboardTag("monster")
                    creeper.addScoreboardTag(TEST_DUMMY_TAG)
                    creeper.setAI(false)
                    creeper.isPowered = false
                    creeper.explosionRadius = 0

                    // 设置护甲数据
                    creeper.persistentDataContainer.set(armorKey, PersistentDataType.DOUBLE, finalArmor)

                    // 设置属性
                    creeper.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth
                    // 清空原版护甲
                    creeper.getAttribute(Attribute.ARMOR)?.baseValue = 0.0

                    creeper.health = maxHealth

                    creeper.customName = ChatColor.translateAlternateColorCodes('&',
                        "&c&l测伤人偶 &7(HP:${maxHealth.toInt()} 护甲:${finalArmor.toInt()})"
                    )
                    creeper.isCustomNameVisible = true
                }
            }, 20L)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onShoot(event: EntityShootBowEvent) {
        val entity = event.entity
        if (entity !is Player) return
        val player = entity
        val bow = event.bow

        // 1. 槽位检查
        if (!isWeaponSlotValid(player, bow)) {
            event.isCancelled = true
            player.sendMessage(ChatColor.RED.toString() + "弓弩未激活！请将武器移动到正确的槽位使用！")
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.5f)
            return
        }

        // 2. 职业检查 (弓箭手 Job 1)
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null && (data.job == null || data.job != 1)) {
            event.isCancelled = true
            player.sendMessage(ChatColor.RED.toString() + "只有 [弓箭手] 才能使用弓弩！")
        }
    }
}