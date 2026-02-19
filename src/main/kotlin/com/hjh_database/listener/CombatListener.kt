package com.hjh_database.listener

import com.hjh_database.Hjh_database
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.enchantments.Enchantment
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
//    多重箭侧箭标记
    private val multishotSideKey = NamespacedKey(plugin, "multishot_side")


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

    // === 主伤害处理逻辑 (计算属性、护甲、职业修正) ===
    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        if (event.isCancelled) return

        // 1. 环境伤害过滤
        when (event.cause) {
            EntityDamageEvent.DamageCause.CONTACT,
            EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.FALL,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.DROWNING,
            EntityDamageEvent.DamageCause.STARVATION,
            EntityDamageEvent.DamageCause.CRAMMING,
            EntityDamageEvent.DamageCause.HOT_FLOOR
                -> return
            else -> {}
        }

        var isMagicDamage = false
        var magicBaseDamage = 0.0
        val entity = event.entity

        // 2. 检测法术伤害标记
        if (entity.hasMetadata("HJH_MAGIC_DAMAGE")) {
            isMagicDamage = true
            val metadataList = entity.getMetadata("HJH_MAGIC_DAMAGE")
            if (metadataList.isNotEmpty()) {
                magicBaseDamage = metadataList[0].asDouble()
            }
            entity.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
        }

        // 3. 横扫检测
        if (event.cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            if (event is EntityDamageByEntityEvent) {
                val damager = event.damager
                if (damager is Player) {
                    val data = plugin.playerManager.getPlayerData(damager)
                    if (data == null || data.job == null || data.job != 0) {
                        event.isCancelled = true
                        return
                    }
                }
            }
        }

        // 4. 物理技能标记清理
        val isPhysicalSkill = entity.hasMetadata("hjh_physical_skill")
        if (isPhysicalSkill) {
            entity.removeMetadata("hjh_physical_skill", plugin)
        }

        var damage = event.damage

        if (isMagicDamage) {
            damage = magicBaseDamage
        }

        var ignoreArmor = false

        // 5. 攻击者逻辑 (玩家属性 & 怪物词缀)
        if (!isMagicDamage && !isPhysicalSkill && event is EntityDamageByEntityEvent) {
            val attacker = event.damager

            // --- A. 玩家 ---
            if (attacker is Player) {
                val hand = attacker.inventory.itemInMainHand
                if (!isWeaponSlotValid(attacker, hand)) {
                    val data = plugin.playerManager.getPlayerData(attacker)
                    if (data != null && data.job != null && data.job == 3) {
                        event.isCancelled = true
                        return
                    }
                    event.isCancelled = true
                    attacker.sendMessage(ChatColor.RED.toString() + "武器未激活！请将武器移动到正确的槽位使用！")
                    attacker.playSound(attacker.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.5f)
                    return
                }

                val data = plugin.playerManager.getPlayerData(attacker)
                if (data != null) {
                    var isRpgWeapon = false
                    if (hand.hasItemMeta()) {
                        isRpgWeapon = hand.itemMeta!!.persistentDataContainer.has(weaponKey, PersistentDataType.STRING)
                    }

                    if (data.job != null && data.job == 0) { // 战士
                        val type = hand.type.name
                        if (type.endsWith("_SWORD") || type.endsWith("_AXE")) {
                            var baseAttack = data.attack
                            val cooldown = attacker.attackCooldown
                            baseAttack *= cooldown.toDouble()
                            damage = baseAttack
                        }
                    } else if (isRpgWeapon) {
                        damage = 1.0
                    }

                    val critChance = min(0.8, data.critChance)
                    if (attacker.attackCooldown > 0.9F) {
                        if (Math.random() < critChance) {
                            damage *= 1.5
                            attacker.world.spawnParticle(Particle.CRIT, entity.location.add(0.0, 1.0, 0.0), 15)
                            attacker.playSound(attacker.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f)
                        }
                    }
                }
            }
            // --- B. 怪物词缀 ---
            else if (attacker is LivingEntity) {
                val affixes = getMobAffixes(attacker)
                if (affixes.isNotEmpty()) {
                    if (affixes.contains(com.hjh_database.spawner.MobAffix.BURNING)) {
                        entity.fireTicks = 60
                    }
                    if (affixes.contains(com.hjh_database.spawner.MobAffix.PIERCING)) {
                        ignoreArmor = true
                    }
                }
            }
            // --- C. 箭矢 ---
            else if (attacker is AbstractArrow) {
                val shooter = attacker.shooter
                if (shooter is Player) {
                    val data = plugin.playerManager.getPlayerData(shooter)
                    if (data != null) {
                        val archerDmg = data.archerDamage
                        val velocity = attacker.velocity.length()

                        // 原始伤害
                        var arrowDamage = archerDmg * (min(3.0, velocity) / 3.0)
                        // ⭐ 判断是否为侧箭
                        val isSide = attacker.persistentDataContainer.has(multishotSideKey, PersistentDataType.BYTE)
                        if (isSide) {
                            arrowDamage *= 0.2 // 侧箭20%
                        }
                        damage = arrowDamage
//                        damage = archerDmg * (min(3.0, velocity) / 3.0)
                        val critChance = min(0.8, data.critChance)
                        if (Math.random() < critChance) {
                            damage *= 1.5
                            attacker.isCritical = true
                            shooter.playSound(shooter.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f)
                        }
                    }
                }
            }
        }

        // 6. 受击者逻辑 (护甲计算)
        val victim = event.entity
        if (victim is LivingEntity) {
            if (victim.getAttribute(Attribute.ARMOR) != null) {
                victim.getAttribute(Attribute.ARMOR)!!.baseValue = 0.0
            }

            // 反伤词缀
            if (victim !is Player) {
                val victimAffixes = getMobAffixes(victim)
                if (victimAffixes.contains(com.hjh_database.spawner.MobAffix.THORNS)) {
                    if (event is EntityDamageByEntityEvent && event.damager is LivingEntity) {
                        val damager = event.damager as LivingEntity
                        val reflectDmg = damage * 0.2
                        if (reflectDmg > 1.0) {
                            damager.damage(reflectDmg)
                            damager.sendMessage("§c受到反伤：${String.format("%.1f", reflectDmg)}")
                        }
                    }
                }
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
                    val data = plugin.playerManager.getPlayerData(victim)
                    if (data != null) armor = data.armor
                } else {
                    if (victim.persistentDataContainer.has(armorKey, PersistentDataType.DOUBLE)) {
                        armor = victim.persistentDataContainer.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0
                    }
                }

                if (isMagicDamage || victim.hasMetadata("hjh_magic_damage") || ignoreArmor) {
                    armor = 0.0
                    if (ignoreArmor && victim is Player) {
                        victim.sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§d§l警告：受到破甲伤害！"))
                    }
                }

                if (armor < 0) armor = 0.0
                val multiplier = 50.0 / (50.0 + armor)
                damage *= multiplier
            }
        }

        // 应用伤害修改
        if (damage != event.damage) {
            event.damage = damage
        }

        // ★★★ 注意：这里删除了原有的测伤玩偶逻辑 ★★★
        // 移到了下方的 onDamageMonitor 中
    }

    // === 【新增】测伤玩偶专用监控逻辑 ===
    // Priority.MONITOR 确保在所有插件（包括技能加成）修改完伤害之后运行
    // ignoreCancelled = true 确保只显示有效的攻击
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageMonitor(event: EntityDamageEvent) {
        val victim = event.entity

        // 检查是否是测伤玩偶
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
                    // 获取最终伤害 (event.finalDamage)
                    // 这包含了 CombatListener 的修改 + 技能的加成 + 其他任何修改
                    val finalDamage = event.finalDamage

                    msgTarget.sendMessage(String.format(
                        ChatColor.YELLOW.toString() + "[测试] " + ChatColor.WHITE + "造成伤害: " + ChatColor.RED + "%.2f",
                        finalDamage
                    ))
                }
            }
        }
    }

    private fun getMobAffixes(entity: LivingEntity): Set<com.hjh_database.spawner.MobAffix> {
        val affixKey = NamespacedKey(plugin, "mob_affixes")
        val pdc = entity.persistentDataContainer

        if (!pdc.has(affixKey, PersistentDataType.STRING)) return emptySet()

        val str = pdc.get(affixKey, PersistentDataType.STRING) ?: return emptySet()
        return str.split(",").mapNotNull { com.hjh_database.spawner.MobAffix.fromId(it) }.toSet()
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        // ... (保持原有的 onDeath 逻辑不变) ...
        val entity = event.entity
        val killer = entity.killer

        if (killer != null
            && !entity.scoreboardTags.contains(TEST_DUMMY_TAG)
            && !entity.scoreboardTags.contains("trial_mob")) {
            if (entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")) {
                val expAmount = plugin.playerManager.getMobExp()
                plugin.playerManager.giveExp(killer, expAmount)
                killer.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§e+ $expAmount 经验"))
            }
        }

        if (entity.scoreboardTags.contains(TEST_DUMMY_TAG)) {
            event.drops.clear()
            event.droppedExp = 0
            val loc = entity.location

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

                    creeper.persistentDataContainer.set(armorKey, PersistentDataType.DOUBLE, finalArmor)
                    creeper.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth
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
        // ... (保持原有的 onShoot 逻辑不变) ...
        val entity = event.entity
        if (entity !is Player) return
        val player = entity
        val bow = event.bow

        if (!isWeaponSlotValid(player, bow)) {
            event.isCancelled = true
            player.sendMessage(ChatColor.RED.toString() + "弓弩未激活！请将武器移动到正确的槽位使用！")
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.5f)
            return
        }

        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null && (data.job == null || data.job != 1)) {
            event.isCancelled = true
            player.sendMessage(ChatColor.RED.toString() + "只有 [弓箭手] 才能使用弓弩！")
        }

        val proj = event.projectile
        if (proj is AbstractArrow && bow != null && bow.type == Material.CROSSBOW) {
            if (bow.containsEnchantment(Enchantment.MULTISHOT)) {
                val dir = player.eyeLocation.direction.normalize()
                val projDir = proj.velocity.normalize()
                val dot = dir.dot(projDir)
                // 中间箭 dot ≈ 1，侧箭明显偏
                if (dot < 0.99) {
                    proj.persistentDataContainer.set(multishotSideKey, PersistentDataType.BYTE, 1)
                }
            }
        }

    }
}