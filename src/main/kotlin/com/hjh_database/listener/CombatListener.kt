package com.hjh_database.listener

import com.hjh_database.Hjh_database
import com.hjh_database.command.TestMobCommand
import com.hjh_database.spawner.MobAffix
import com.hjh_database.spawner.MobFactory
import com.hjh_database.spawner.MobRegistry
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
import java.util.EnumSet
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min

class CombatListener(private val plugin: Hjh_database) : Listener {

    // --- 预缓存所有的 NamespacedKey，避免高频事件中重复创建对象 ---
    private val armorKey = NamespacedKey(plugin, "hjh_mob_armor")
    private val weaponKey = NamespacedKey(plugin, "weapon_id")
    private val multishotSideKey = NamespacedKey(plugin, "multishot_side")
    private val isBowKey = NamespacedKey(plugin, "is_bow_shot") // [新增] 用于弓箭2.5倍伤害判断
    private val affixKey = NamespacedKey(plugin, "mob_affixes")
    private val mobIdKey = MobFactory.KEY_MOB_ID
    // [新增] 用于存储箭矢射出瞬间的属性快照
    private val storedDamageKey = NamespacedKey(plugin, "stored_arrow_damage")
    private val storedCritKey = NamespacedKey(plugin, "stored_arrow_crit")
    // === 【新增】恶土之炎的专属 PDC 烙印 Key ===
    private val desertSouthSneaksKey = NamespacedKey(plugin, "desert_south_sneaks")

    companion object {
        private const val TEST_DUMMY_TAG = "hjh_test_dummy"

        // --- 使用 EnumSet (位图向量) 提升高频事件中的判断速度，替代低效的 when 遍历 ---
        private val IGNORED_DAMAGE_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.CONTACT, EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.FALL, EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.DROWNING, EntityDamageEvent.DamageCause.STARVATION,
            EntityDamageEvent.DamageCause.CRAMMING, EntityDamageEvent.DamageCause.HOT_FLOOR
        )

        private val MAGIC_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.MAGIC, EntityDamageEvent.DamageCause.DRAGON_BREATH,
            EntityDamageEvent.DamageCause.WITHER, EntityDamageEvent.DamageCause.POISON
        )

        private val TRUE_DAMAGE_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.VOID, EntityDamageEvent.DamageCause.SUICIDE,
            EntityDamageEvent.DamageCause.STARVATION
        )
    }

    private fun isWeaponSlotValid(player: Player, item: ItemStack?): Boolean {
        if (item == null || item.type.isAir || !item.hasItemMeta()) return true

        val meta = item.itemMeta ?: return true
        val weaponId = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING) ?: return true

        val wd = plugin.playerManager.weaponManager.getWeaponData(weaponId) ?: return true
        return wd.activateSlot == -1 || player.inventory.heldItemSlot == wd.activateSlot
    }

    // === 主伤害处理逻辑 ===
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val cause = event.cause
        // 1. 高效的环境伤害过滤
        if (IGNORED_DAMAGE_CAUSES.contains(cause)) return

        val entity = event.entity
        var damage = event.damage
        var isMagicDamage = false
        var magicBaseDamage = 0.0
        var ignoreArmor = false

        // 2. 检测法术伤害标记
        if (entity.hasMetadata("HJH_MAGIC_DAMAGE")) {
            isMagicDamage = true
            entity.getMetadata("HJH_MAGIC_DAMAGE").firstOrNull()?.let {
                magicBaseDamage = it.asDouble()
            }
            entity.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
        }

        // 3. 横扫检测
        if (cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK && event is EntityDamageByEntityEvent) {
            val damager = event.damager
            if (damager is Player) {
                val data = plugin.playerManager.getPlayerData(damager)
                if (data?.job != 0) { // 只有战士(0)才能触发横扫
                    event.isCancelled = true
                    return
                }
            }
        }

        // 4. 物理技能标记清理
        val isPhysicalSkill = entity.hasMetadata("hjh_physical_skill")
        if (isPhysicalSkill) {
            entity.removeMetadata("hjh_physical_skill", plugin)
        }

        if (isMagicDamage) damage = magicBaseDamage

        // 5. 攻击者逻辑 (玩家属性 & 怪物词缀)
        if (!isMagicDamage && !isPhysicalSkill && event is EntityDamageByEntityEvent) {
            // ================= 【新增：自定义怪物伤害覆写】 =================
            // 获取真正的攻击者 (兼容近战和远程投射物，比如骷髅的箭、烈焰人的火球)
            val realAttacker: LivingEntity? = when (val damager = event.damager) {
                is LivingEntity -> damager // 近战攻击
                is org.bukkit.entity.Projectile -> damager.shooter as? LivingEntity // 远程投射物攻击
                else -> null
            }

            // 如果真正的攻击者不是玩家，检查它是不是我们的自定义怪物
            if (realAttacker != null && realAttacker !is Player) {
                val pdc = realAttacker.persistentDataContainer
                // 读取 NBT (请确保类文件顶部引入了 com.hjh_database.spawner.MobFactory 和 MobRegistry)
                val mobId = pdc.get(MobFactory.KEY_MOB_ID, PersistentDataType.STRING)
                if (mobId != null) {
                    val def = MobRegistry.get(mobId)
                    if (def != null) {
                        // ★ 核心：无论原版怎么算，直接把基础伤害覆写为 MobRegistry 配置的数值
                        damage = def.damage
                    }
                }
                if (entity is Player) {
                    val affixes = getMobAffixes(realAttacker)
                    // ================= 【恶土之炎 触发】 =================
                    if (affixes.contains(MobAffix.DESERT_SOUTH) && ThreadLocalRandom.current().nextDouble() <= 0.6) {
                        com.hjh_database.spawner.impl.DesertSouthSkill.trigger(entity)
                    }
                    // ====================================================
                }

            }
            // =============================================================
            when (val attacker = event.damager) {
                // --- A. 玩家 ---
                is Player -> {
                    val hand = attacker.inventory.itemInMainHand
                    if (!isWeaponSlotValid(attacker, hand)) {
                        val data = plugin.playerManager.getPlayerData(attacker)
                        if (data?.job == 3) {
                            event.isCancelled = true
                            return
                        }
                        event.isCancelled = true
                        attacker.sendMessage("§c武器未激活！请将武器移动到正确的槽位使用！")
                        attacker.playSound(attacker.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.5f)
                        return
                    }

                    plugin.playerManager.getPlayerData(attacker)?.let { data ->
                        val isRpgWeapon = hand.itemMeta?.persistentDataContainer?.has(weaponKey, PersistentDataType.STRING) == true

                        if (data.job == 0) { // 战士
                            val typeName = hand.type.name
                            if (typeName.endsWith("_SWORD") || typeName.endsWith("_AXE")) {
                                damage = data.attack * attacker.attackCooldown.toDouble()
                            }
                        } else if (isRpgWeapon) {
                            damage = 1.0
                        }

                        // 高性能并发安全的随机数替代 Math.random()
                        val critChance = min(0.8, data.critChance)
                        if (attacker.attackCooldown > 0.9f && ThreadLocalRandom.current().nextDouble() < critChance) {
                            damage *= 1.5
                            attacker.world.spawnParticle(Particle.CRIT, entity.location.add(0.0, 1.0, 0.0), 15)
                            attacker.playSound(attacker.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f)
                        }
                    }
                }

                // --- B. 箭矢 ---
                is AbstractArrow -> {
                    val shooter = attacker.shooter
                    if (shooter is Player) {
                        val pdc = attacker.persistentDataContainer

                        // ⭐ 【关键修复】直接从箭矢 PDC 中读取当时存入的伤害和暴击率（如果没读到则默认为0）
                        val archerDmg = pdc.get(storedDamageKey, PersistentDataType.DOUBLE) ?: 0.0
                        val critChance = pdc.get(storedCritKey, PersistentDataType.DOUBLE) ?: 0.0

                        val velocity = attacker.velocity.length()

                        // 原始伤害系数计算 (速度折算)
                        var arrowDamage = archerDmg * (min(3.0, velocity) / 3.0)

                        // 判断武器类型 (弓还是弩) - 弓伤害在拉满时额外 x2.5
                        if (pdc.has(isBowKey, PersistentDataType.BYTE)) {
                            arrowDamage *= 2.5
                        }

                        // 判断是否为多重射击的侧箭
                        if (pdc.has(multishotSideKey, PersistentDataType.BYTE)) {
                            arrowDamage *= 0.2 // 侧箭削弱到 20%
                        }

                        damage = arrowDamage

                        // 判断暴击，使用快照里的暴击率
                        val finalCritChance = min(0.8, critChance)
                        if (ThreadLocalRandom.current().nextDouble() < finalCritChance) {
                            damage *= 1.5
                            attacker.isCritical = true
                            shooter.playSound(shooter.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f)
                        }
                    }
                }
            }
        }

        // 6. 受击者逻辑 (护甲计算)
        if (entity is LivingEntity) {
            entity.getAttribute(Attribute.ARMOR)?.baseValue = 0.0
            // 防御与伤害减免计算
            val isMagic = MAGIC_CAUSES.contains(cause)
            val isTrueDamage = TRUE_DAMAGE_CAUSES.contains(cause)

            if (!isMagic && !isTrueDamage) {
                var armor = 0.0
                if (entity is Player) {
                    armor = plugin.playerManager.getPlayerData(entity)?.armor ?: 0.0
                } else {
                    armor = entity.persistentDataContainer.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0
                }

                if (isMagicDamage || entity.hasMetadata("hjh_magic_damage") || ignoreArmor) {
                    armor = 0.0
                    if (ignoreArmor && entity is Player) {
                        entity.sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§d§l警告：受到破甲伤害！"))
                    }
                }

                if (armor < 0) armor = 0.0
                val multiplier = 50.0 / (50.0 + armor)
                damage *= multiplier
            }
        }

        // === 【元素结晶·启示 触发】 ===
        val attackerPlayer = if (event is EntityDamageByEntityEvent) {
            when (val d = event.damager) {
                is Player -> d
                is Projectile -> d.shooter as? Player
                else -> null
            }
        } else null

        if (attackerPlayer != null) {
            val goldStacks = plugin.elementCrystalManager.getFengMangStacks(attackerPlayer)
            if (goldStacks > 0) {
                damage *= (1.0 + 0.04 * goldStacks)
            }
            if (entity is LivingEntity) {
                plugin.elementCrystalManager.onDamageDealt(attackerPlayer, entity)
            }
        }

        if (entity is Player) {
            plugin.elementCrystalManager.onDamageTaken(entity, event)
        }
        // ============================

        // 应用伤害修改
        if (damage != event.damage) {
            event.damage = damage
        }
    }

    // === 【测伤玩偶】专用监控逻辑 ===
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageMonitor(event: EntityDamageEvent) {
        val victim = event.entity
        if (!victim.scoreboardTags.contains(TEST_DUMMY_TAG)) return

        val damageEvent = event as? EntityDamageByEntityEvent ?: return

        val msgTarget: CommandSender? = when (val damager = damageEvent.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }

        msgTarget?.sendMessage("§e[测试] §f造成伤害: §c%.2f".format(event.finalDamage))
    }

    // 优化：采用 EnumSet 返回更轻量级和高效的枚举集合
    private fun getMobAffixes(entity: LivingEntity): Set<MobAffix> {
        val str = entity.persistentDataContainer.get(affixKey, PersistentDataType.STRING) ?: return emptySet()
        if (str.isEmpty()) return emptySet()

        val resultSet = EnumSet.noneOf(MobAffix::class.java)
        str.split(',').forEach { id ->
            MobAffix.fromId(id)?.let { resultSet.add(it) }
        }
        return resultSet
    }

    @EventHandler(ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        // === 【新增修复】拦截非最大尺寸的史莱姆和岩浆怪 ===
        // org.bukkit.entity.MagmaCube 继承自 org.bukkit.entity.Slime，所以判断 Slime 即可涵盖两者
        if (entity is org.bukkit.entity.Slime) {
            // 如果尺寸小于 4 (即非最大尺寸分裂出来的中、小体型)
            if (entity.size < 4) {
                // 清空原版掉落物
                event.drops.clear()
                // 清空原版经验掉落
                event.droppedExp = 0
                // ★ 直接 return，阻止后续 CombatListener 里的自定义经验发放和掉落逻辑
                return
            }
        }
        val killer = entity.killer

        // 1. 给击杀者发放经验
        if (killer != null && !entity.scoreboardTags.contains(TEST_DUMMY_TAG)) {
            if (entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")) {

                // === 读取自定义怪物的独立经验 (运用 Kotlin let 防空特性优化) ===
                val pdc = entity.persistentDataContainer
                val expAmount = pdc.get(mobIdKey, PersistentDataType.STRING)?.let { mobId ->
                    MobRegistry.get(mobId)?.exp
                } ?: plugin.playerManager.getMobExp() // 默认兜底兼容

                plugin.playerManager.giveExp(killer, expAmount)
                shareExpToNearbyMedicalPlayers(killer, entity, expAmount)
                killer.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§e+ $expAmount 经验"))
            }
        }

        // 2. 测伤玩偶专属：死亡后重生逻辑
        if (entity.scoreboardTags.contains(TEST_DUMMY_TAG)) {
            event.drops.clear()
            event.droppedExp = 0
            val loc = entity.location

            val maxHealth = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            val finalArmor = entity.persistentDataContainer.get(armorKey, PersistentDataType.DOUBLE) ?: 0.0

            // 延迟一秒在原地重新生成测伤玩偶
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                TestMobCommand.spawnDummy(plugin, loc, maxHealth, finalArmor)
            }, 20L)
        }
    }

    private fun shareExpToNearbyMedicalPlayers(killer: Player, source: LivingEntity, expAmount: Int) {
        if (expAmount <= 0) return
        val killerData = plugin.playerManager.getData(killer.uniqueId) ?: return
        if (killerData.job == 3) return

        val sharedExp = expAmount / 2
        if (sharedExp <= 0) return

        val radius = 10.0
        val radiusSquared = radius * radius
        val center = source.location

        for (entity in source.world.getNearbyEntities(center, radius, radius, radius)) {
            val medicalPlayer = entity as? Player ?: continue
            if (medicalPlayer.uniqueId == killer.uniqueId) continue
            if (medicalPlayer.location.distanceSquared(center) > radiusSquared) continue

            val medicalData = plugin.playerManager.getData(medicalPlayer.uniqueId) ?: continue
            if (medicalData.job != 3 || medicalData.lv >= 30) continue

            plugin.playerManager.giveExp(medicalPlayer, sharedExp)
            medicalPlayer.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent("§a医师协助 §e+ $sharedExp 经验")
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val bow = event.bow ?: return

        if (!isWeaponSlotValid(player, bow)) {
            event.isCancelled = true
            player.sendMessage("§c弓弩未激活！请将武器移动到正确的槽位使用！")
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1f, 0.5f)
            return
        }

        val data = plugin.playerManager.getData(player.uniqueId)
        if (data == null || data.job != 1) { // 必须是弓箭手(1)
            event.isCancelled = true
            player.sendMessage("§c只有 [弓箭手] 才能使用弓弩！")
            return
        }

        val proj = event.projectile
        if (proj is AbstractArrow) {
            val pdc = proj.persistentDataContainer
            // ⭐ 【关键修复】将射出瞬间的面板伤害和暴击率死死地绑定在箭矢上！
            pdc.set(storedDamageKey, PersistentDataType.DOUBLE, data.archerDamage)
            pdc.set(storedCritKey, PersistentDataType.DOUBLE, data.critChance)
            // ⭐ 判断武器材质并写入 PDC 供子弹命中间判断
            if (bow.type == Material.BOW) {
                // 打上专属标记，用于在伤害判定时实现 250% 缩放
                proj.persistentDataContainer.set(isBowKey, PersistentDataType.BYTE, 1)
            } else if (bow.type == Material.CROSSBOW && bow.containsEnchantment(Enchantment.MULTISHOT)) {
                // 原有多重射击逻辑
                val dir = player.eyeLocation.direction.normalize()
                val projDir = proj.velocity.normalize()
                if (dir.dot(projDir) < 0.99) {
                    proj.persistentDataContainer.set(multishotSideKey, PersistentDataType.BYTE, 1)
                }
            }
        }
    }
}
