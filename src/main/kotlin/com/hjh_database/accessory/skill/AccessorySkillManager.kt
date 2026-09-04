package com.hjh_database.accessory

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.accessory.skill.core.ActiveAccessoryHudState
import com.hjh_database.accessory.skill.medical.JinshengzhiSkill
import com.hjh_database.accessory.skill.medical.KanzelingzhiSkill
import com.hjh_database.accessory.skill.medical.TaolizhiSkill
import com.hjh_database.accessory.skill.quiver.*
import com.hjh_database.accessory.skill.shield.*
import com.hjh_database.accessory.skill.warlock.BaseRefluxSkill
import com.hjh_database.accessory.skill.warlock.HuiliuyiSkill
import com.hjh_database.accessory.skill.warlock.XunlilingshuSkill
import com.hjh_database.accessory.skill.warlock.YanlingSkill
import com.hjh_database.accessory.element.ElementCrystalArmorCalculationEvent
import com.hjh_database.combat.MonsterDamageClassification
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.FormationDamageEvent
import com.hjh_database.skill.medical.spell.MedicalHealEvent
import com.hjh_database.weapon.CrystalData
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot // 銆愭柊澧炲鍏ャ€戠敤浜庡垽鏂富鍓墜
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

class AccessorySkillManager(private val plugin: Hjh_database) : Listener {
    private val crystalKey = NamespacedKey(plugin, "crystal_id")
    /** 同一个 Bukkit 伤害事件只在 LOWEST/HIGHEST 两阶段短暂保存，不按玩家长期缓存。 */
    private val skillEventsBypassingShield = Collections.newSetFromMap(
        IdentityHashMap<EntityDamageByEntityEvent, Boolean>()
    )

    // 銆愮粺涓€娉ㄥ唽琛ㄣ€?
    private val skills = mapOf<String, BaseAccessorySkill>(
        "jiandai" to JiandaiSkill(plugin),
        "ranhuojiandai" to RanhuoJiandaiSkill(plugin),
        "qianzhentianji" to QianzhentianjiSkill(plugin),
        "qingshidunpai" to QingshidunpaiSkill(plugin),
        "yanjingdunpai" to YanjingdunpaiSkill(plugin),
        "zhenyuechenfeng" to ZhenyuechenfengSkill(plugin),
        "huiliuyi" to HuiliuyiSkill(plugin),
        "yanling" to YanlingSkill(plugin),
        "xunlilingshu" to XunlilingshuSkill(plugin),
        "taolizhi" to TaolizhiSkill(plugin),
        "jinshengzhi" to JinshengzhiSkill(plugin),
        "kanzelingzhi" to KanzelingzhiSkill(plugin)
    )

    // 技能实例在插件构造阶段创建；事件统一由 onEnable 注册的管理器转发。
    @EventHandler(priority = EventPriority.MONITOR)
    fun onElementFormationHit(event: FormationDamageEvent) {
        (skills["xunlilingshu"] as? XunlilingshuSkill)?.onFormationHit(event)
    }

    /**
     * 銆愭牳蹇冧慨鏀癸細閫氱敤婵€娲荤姸鎬佹嫤鎴€?
     * 鐩戝惉鐜╁鍙抽敭鐐瑰嚮锛屽鏋滅浘鐗屾湭澶勪簬閰嶇疆鐨勬縺娲绘Ы浣嶏紝鎴栫帺瀹惰亴涓?绛夌骇涓嶇锛岀洿鎺ュ彇娑堜妇鐩撅紒
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onShieldInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return

        // 1. 妫€鏌ユ槸鍚︽槸鐩剧墝
        if (item.type != Material.SHIELD) return

        // 2. 妫€鏌ユ槸鍚︽槸鈥滈グ鍝佺郴缁熲€濈殑鐩剧墝锛堥€氳繃 NBT 鏍囩鍒ゆ柇锛?
        val meta = item.itemMeta ?: return
        val baihuShield = plugin.baihuDzManager.getArtifactDataFromItem(item)
        if (!meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING) && baihuShield == null) return

        // 3. 妫€鏌ュ彸閿涓猴紙涓剧浘锛?
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            val pData = plugin.playerManager.getPlayerData(player) ?: return
            val cData = if (baihuShield != null) {
                plugin.baihuDzManager.toCrystalData(baihuShield)
            } else {
                val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: return
                plugin.playerManager.crystalManager.loadedCrystals[cid] ?: return
            }

            // 鑾峰彇鐜╁褰撳墠瑙﹀彂鍙抽敭鎵€鍦ㄧ殑妲戒綅鏍囪瘑
            val slotKey = if (event.hand == EquipmentSlot.OFF_HAND) {
                "offhand"
            } else {
                // 濡傛灉鏄敤涓绘墜涓剧浘锛屾Ы浣嶅氨鏄揩鎹锋爮褰撳墠閫変腑鐨勬牸瀛?(hotbar_0 鍒?hotbar_8)
                "hotbar_${player.inventory.heldItemSlot}"
            }

            // 4. 銆愭牳蹇冮€昏緫銆戝垽鏂グ鍝佹槸鍚﹀湪璇ユЫ浣嶆縺娲伙紝涓旂帺瀹剁鍚堟縺娲昏姹?
            val active = if (baihuShield != null) {
                plugin.baihuDzManager.isArtifactActiveForSkill(player, item, baihuShield, slotKey)
            } else {
                plugin.playerManager.crystalManager.isActive(cData, pData, slotKey, player, item)
            }
            if (!active) {
                player.sendMessage("§c该盾牌未处于激活状态，无法举盾。")

                // 鍙栨秷浜嬩欢锛岄樆姝㈢帺瀹惰繘鍏モ€滀妇鐩锯€濈姸鎬?
                event.isCancelled = true
                // 鍚屾椂寮哄埗璁剧疆浣跨敤缁撴灉涓?DENY锛岄槻姝㈠師鐗堢殑鍔ㄧ敾鍜岀姸鎬佹洿鏂?
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            }
        }
    }

    // 鎻愪緵缁?AccessoryManager 璋冪敤鐨勭粺涓€璺敱锛堝鐞?Shift+鍙抽敭 鐨?UI 鎶€鑳斤級
    fun routeAccessoryClick(player: Player, item: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        val targetId = crystalData.skillId ?: crystalData.id
        val skillClass = skills[targetId] ?: return false
        return skillClass.handleShiftClick(player, item, isExtract, crystalData)
    }

    // 鑾峰彇鐜╁韬笂鎵€鏈夌敓鏁堟Ы浣嶇殑鐗╁搧 (楗板搧鏍?+ 鍓墜 + 蹇嵎鏍?
    private fun getActiveAccessories(player: Player): List<Pair<ItemStack, String>> {
        val list = mutableListOf<Pair<ItemStack, String>>()
        val contents = plugin.accessoryManager.getAccessoryContents(player)
        if (contents != null) {
            for (i in contents.indices) {
                contents[i]?.let { list.add(it to "accessory_$i") }
            }
        }
        val offHand = player.inventory.itemInOffHand
        if (offHand.type != Material.AIR) list.add(offHand to "offhand")
        for (i in 0..8) {
            player.inventory.getItem(i)?.let { list.add(it to "hotbar_$i") }
        }
        return list
    }

    /**
     * 为 Fabric 客户端选出唯一生效的职业饰品。只接受 crystals.yml 中明确列出的职业饰品，
     * 元素结晶与圣兽饰品即使复用了技能基类也不会进入这个 HUD。
     */
    fun getActiveAccessoryHudState(player: Player): ActiveAccessoryHudState? {
        val playerData = plugin.playerManager.getPlayerData(player) ?: return null
        for ((item, slotKey) in getActiveAccessories(player)) {
            val meta = item.itemMeta ?: continue
            val crystalId = meta.persistentDataContainer
                .get(crystalKey, PersistentDataType.STRING) ?: continue
            if (crystalId !in HUD_ACCESSORY_IDS) continue
            val crystalData = plugin.playerManager.crystalManager.loadedCrystals[crystalId] ?: continue
            if (!plugin.playerManager.crystalManager.isActive(crystalData, playerData, slotKey, player, item)) continue

            val skillId = crystalData.skillId ?: crystalData.id
            val skill = skills[skillId] ?: continue
            return ActiveAccessoryHudState(
                accessoryId = crystalData.id,
                materialId = crystalData.material.key.toString(),
                customModelData = crystalData.customModelData,
                state = skill.getHudState(player, item, crystalData)
            )
        }
        return null
    }

    @EventHandler(ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val data = plugin.playerManager.getPlayerData(player) ?: return
        val activeItems = getActiveAccessories(player)

        for ((item, slotKey) in activeItems) {
            val meta = item.itemMeta ?: continue
            val baihuArtifact = plugin.baihuDzManager.getArtifactDataFromItem(item)
            val cData = if (baihuArtifact != null) {
                plugin.baihuDzManager.toCrystalData(baihuArtifact)
            } else {
                val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: continue
                plugin.playerManager.crystalManager.loadedCrystals[cid] ?: continue
            }

            val active = if (baihuArtifact != null) {
                plugin.baihuDzManager.isArtifactActiveForSkill(player, item, baihuArtifact, slotKey)
            } else {
                plugin.playerManager.crystalManager.isActive(cData, data, slotKey, player, item)
            }

            if (active) {
                val targetId = cData.skillId ?: cData.id
                val skillClass = skills[targetId] as? BaseQuiverSkill ?: continue

                skillClass.onShootEffect(event, player, data)
                if (baihuArtifact != null) {
                    plugin.baihuDzManager.consumeDurability(player, item, baihuArtifact)
                }

                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    skillClass.processReplenish(player, item, cData, slotKey)
                }, 1L)
                break
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamageBlock(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        if (!player.isBlocking) return
        if (player.hasCooldown(Material.SHIELD)) return
        val offHandItem = player.inventory.itemInOffHand
        if (offHandItem.type == Material.SHIELD && offHandItem.hasItemMeta()) {
            val meta = offHandItem.itemMeta
            val cid = meta?.persistentDataContainer?.get(crystalKey, PersistentDataType.STRING)

            val pData = plugin.playerManager.getPlayerData(player) ?: return
            val baihuArtifact = plugin.baihuDzManager.getArtifactDataFromItem(offHandItem)
            val cData = if (baihuArtifact != null) {
                plugin.baihuDzManager.toCrystalData(baihuArtifact)
            } else {
                plugin.playerManager.crystalManager.loadedCrystals[cid] ?: return
            }
            // 缁熶竴妫€鏌ユ槸鍚︽縺娲讳笖鍦ㄥ壇鎵嬬敓鏁堟Ы浣?
            val active = if (baihuArtifact != null) {
                plugin.baihuDzManager.isArtifactActiveForSkill(player, offHandItem, baihuArtifact, "offhand")
            } else {
                plugin.playerManager.crystalManager.isActive(cData, pData, "offhand", player, offHandItem)
            }
            if (!active) return

            when (MonsterDamageClassification.classify(plugin, event)) {
                MonsterDamageClassification.Type.NORMAL_ATTACK -> Unit
                MonsterDamageClassification.Type.SKILL -> {
                    // 先阻止自定义盾牌效果；再在全部伤害公式结算后移除原版 BLOCKING 减伤。
                    // 用事件实例作短生命周期标识，避免技能 metadata 被 CombatListener 消费后丢失语义。
                    skillEventsBypassingShield += event
                    removeVanillaShieldReduction(event)
                    return
                }
                MonsterDamageClassification.Type.OTHER -> return
            }

            val targetId = cData.skillId ?: cData.id
            val skillClass = skills[targetId]
            // ============================================
            // 銆愭牳蹇冮噸鏋勯€昏緫銆戣В鑰︾‖缂栫爜鍒ゆ柇
            // 鍙瀹冩槸鐩剧墝鍩虹被鐨勫疄渚嬶紝灏辩洿鎺ヤ涪缁欏熀绫诲幓澶勭悊閫氱敤鏍兼尅鍔ㄤ綔
            // ============================================
            if (skillClass is BaseShieldSkill) {
                skillClass.handleShieldBlock(event, player, cData)
                if (baihuArtifact != null) {
                    plugin.baihuDzManager.consumeDurability(player, offHandItem, baihuArtifact)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onQianzhentianjiProjectileHit(event: ProjectileHitEvent) {
        (skills["qianzhentianji"] as? QianzhentianjiSkill)?.onProjectileHit(event)
    }

    /**
     * CombatListener 会在 HIGH 阶段重算基础伤害，因此在 HIGHEST 再清一次 BLOCKING 修正。
     * Boss 技能仍会正常造成完整伤害，也不会触发盾牌冷却、耐久消耗或饰品效果。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun enforceMonsterSkillShieldBypass(event: EntityDamageByEntityEvent) {
        if (!skillEventsBypassingShield.remove(event)) return
        removeVanillaShieldReduction(event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onZhenyuePreMeleeAttack(event: PrePlayerAttackEntityEvent) {
        (skills["zhenyuechenfeng"] as? ZhenyuechenfengSkill)?.onPreMeleeAttack(event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onZhenyueMeleeDamageResolved(event: EntityDamageByEntityEvent) {
        (skills["zhenyuechenfeng"] as? ZhenyuechenfengSkill)?.onMeleeDamageResolved(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onZhenyueDamageTaken(event: EntityDamageEvent) {
        (skills["zhenyuechenfeng"] as? ZhenyuechenfengSkill)?.onDamageTaken(event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onZhenyueDamageTakenResolved(event: EntityDamageEvent) {
        (skills["zhenyuechenfeng"] as? ZhenyuechenfengSkill)?.onDamageTakenResolved(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onZhenyueWeakenedMonsterDamage(event: EntityDamageByEntityEvent) {
        (skills["zhenyuechenfeng"] as? ZhenyuechenfengSkill)?.onWeakenedMonsterDamage(event)
    }

    @Suppress("DEPRECATION")
    private fun removeVanillaShieldReduction(event: EntityDamageByEntityEvent) {
        if (event.isApplicable(DamageModifier.BLOCKING)) {
            event.setDamage(DamageModifier.BLOCKING, 0.0)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onYanjingFlameDamage(event: EntityDamageEvent) {
        val skillClass = skills["yanjingdunpai"] as? YanjingdunpaiSkill ?: return
        skillClass.onPlayerDamage(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onYanjingFlamePotionEffect(event: EntityPotionEffectEvent) {
        val skillClass = skills["yanjingdunpai"] as? YanjingdunpaiSkill ?: return
        skillClass.onPotionEffect(event)
    }

    @EventHandler
    fun onRanhuoArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val skillClass = skills["ranhuojiandai"] as? RanhuoJiandaiSkill ?: return
        skillClass.onArmorCalculation(event)
    }

    @EventHandler
    fun onQianzhentianjiArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val skillClass = skills["qianzhentianji"] as? QianzhentianjiSkill ?: return
        skillClass.onArmorCalculation(event)
    }

    fun onElementFormationCast(player: Player, data: PlayerData) {
        val activeItems = getActiveAccessories(player)

        for ((item, slotKey) in activeItems) {
            val meta = item.itemMeta ?: continue
            val baihuArtifact = plugin.baihuDzManager.getArtifactDataFromItem(item)
            val cData = if (baihuArtifact != null) {
                plugin.baihuDzManager.toCrystalData(baihuArtifact)
            } else {
                val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: continue
                plugin.playerManager.crystalManager.loadedCrystals[cid] ?: continue
            }

            val active = if (baihuArtifact != null) {
                plugin.baihuDzManager.isArtifactActiveForSkill(player, item, baihuArtifact, slotKey)
            } else {
                plugin.playerManager.crystalManager.isActive(cData, data, slotKey, player, item)
            }
            if (!active) continue

            val targetId = cData.skillId ?: cData.id
            val skillClass = skills[targetId] as? YanlingSkill ?: continue
            skillClass.onElementFormationCast(player, data)
            if (baihuArtifact != null) {
                plugin.baihuDzManager.consumeDurability(player, item, baihuArtifact)
            }
            break
        }
    }

    /**
     * 元素阵法真正执行前建立风场计划，失败时可以安全丢弃。
     */
    private val activeFormationPlans = HashMap<UUID, XunlilingshuSkill.CastPlan>()

    fun prepareElementFormationCast(player: Player): XunlilingshuSkill.CastPlan? {
        activeFormationPlans.remove(player.uniqueId)
        val active = findActiveSkill(player, "xunlilingshu") ?: return null
        val skill = active.first as? XunlilingshuSkill ?: return null
        val plan = skill.prepareCast(player) ?: return null
        activeFormationPlans[player.uniqueId] = plan
        return plan
    }

    fun isXunlilingshuActive(player: Player): Boolean = findActiveSkill(player, "xunlilingshu") != null

    fun completeElementFormationCast(
        player: Player,
        plan: XunlilingshuSkill.CastPlan?,
        success: Boolean
    ) {
        if (plan == null) return
        val current = activeFormationPlans[player.uniqueId]
        if (current !== plan) return
        activeFormationPlans.remove(player.uniqueId)
        (skills["xunlilingshu"] as? XunlilingshuSkill)?.completeCast(player, plan, success)
    }

    /** 饰品栏保存后调用，卸下巽离灵枢会立即清除风场、加成和火种。 */
    fun onAccessoryLoadoutChanged(player: Player) {
        if (findActiveSkill(player, "xunlilingshu") == null) {
            (skills["xunlilingshu"] as? XunlilingshuSkill)?.cleanup(player)
            activeFormationPlans.remove(player.uniqueId)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        (skills["yanling"] as? YanlingSkill)?.cleanup(event.player)
        (skills["yanjingdunpai"] as? YanjingdunpaiSkill)?.cleanup(event.player)
        (skills["zhenyuechenfeng"] as? ZhenyuechenfengSkill)?.cleanup(event.player)
        (skills["qianzhentianji"] as? QianzhentianjiSkill)?.cleanup(event.player)
        (skills["xunlilingshu"] as? XunlilingshuSkill)?.cleanup(event.player)
        (skills["kanzelingzhi"] as? KanzelingzhiSkill)?.cleanup(event.player)
        skills.values.forEach { it.cleanupHudState(event.player) }
        activeFormationPlans.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        (skills["xunlilingshu"] as? XunlilingshuSkill)?.cleanup(event.entity)
        activeFormationPlans.remove(event.entity.uniqueId)
    }

    fun shutdown() {
        (skills["zhenyuechenfeng"] as? ZhenyuechenfengSkill)?.shutdown()
        (skills["qianzhentianji"] as? QianzhentianjiSkill)?.shutdown()
        (skills["xunlilingshu"] as? XunlilingshuSkill)?.shutdown()
        (skills["kanzelingzhi"] as? KanzelingzhiSkill)?.shutdown()
        skills.values.forEach(BaseAccessorySkill::shutdownHudState)
        activeFormationPlans.clear()
    }

    @EventHandler
    fun onMedicalHeal(event: MedicalHealEvent) {
        if (event.overflowHeal <= 0.0) return
        val player = event.caster
        val data = plugin.playerManager.getPlayerData(player) ?: return

        fun processItem(item: ItemStack?, slotKey: String): Boolean {
            if (item == null || item.type == Material.AIR) return false
            val meta = item.itemMeta ?: return false
            val baihuArtifact = plugin.baihuDzManager.getArtifactDataFromItem(item)
            val cData = if (baihuArtifact != null) {
                plugin.baihuDzManager.toCrystalData(baihuArtifact)
            } else {
                val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: return false
                plugin.playerManager.crystalManager.loadedCrystals[cid] ?: return false
            }
            val active = if (baihuArtifact != null) {
                plugin.baihuDzManager.isArtifactActiveForSkill(player, item, baihuArtifact, slotKey)
            } else {
                plugin.playerManager.crystalManager.isActive(cData, data, slotKey, player, item)
            }
            if (!active) return false

            val targetId = cData.skillId ?: cData.id
            val skillClass = skills[targetId] ?: return false
            val handled = skillClass.onMedicalHeal(event, item, slotKey, cData)
            if (handled && baihuArtifact != null) {
                plugin.baihuDzManager.consumeDurability(player, item, baihuArtifact)
            }
            return handled
        }

        if (player.openInventory.title == plugin.accessoryManager.INVENTORY_TITLE) {
            val topInv = player.openInventory.topInventory
            for (i in 0 until topInv.size) {
                val item = topInv.getItem(i)
                if (processItem(item, "accessory_$i")) {
                    topInv.setItem(i, item)
                }
            }
        } else {
            val contents = plugin.accessoryManager.getAccessoryContents(player)
            if (contents != null) {
                var changed = false
                for (i in contents.indices) {
                    val item = contents[i]
                    if (processItem(item, "accessory_$i")) {
                        contents[i] = item
                        changed = true
                    }
                }
                if (changed) {
                    plugin.accessoryManager.saveAccessoryContents(player, contents)
                }
            }
        }

        val offHand = player.inventory.itemInOffHand
        if (processItem(offHand, "offhand")) {
            player.inventory.setItemInOffHand(offHand)
        }

        for (i in 0..8) {
            val item = player.inventory.getItem(i)
            if (processItem(item, "hotbar_$i")) {
                player.inventory.setItem(i, item)
            }
        }
    }

    /**
     * 渚涘厓绱犳妧鑳借皟鐢細妫€鏌ョ帺瀹跺綋鍓嶆槸鍚︽縺娲讳簡鍥炴祦绫婚グ鍝侊紝濡傛灉婵€娲讳簡锛岃繑鍥炲畠鐨勫叿浣撳疄渚嬪拰鏁版嵁
     */
    fun getActiveRefluxData(player: Player): Pair<BaseRefluxSkill, CrystalData>? {
        // 濡傛灉鐜╁鏍规湰娌″紑鍚姸鎬侊紝鐩存帴杩斿洖 null锛岃妭鐪佹€ц兘
        if (!BaseRefluxSkill.isRefluxActive(player)) return null

        val pData = plugin.playerManager.getPlayerData(player) ?: return null
        val activeItems = getActiveAccessories(player) // 浣跨敤浣犱箣鍓嶅啓濂界殑鑾峰彇鐢熸晥楗板搧鐨勬柟娉?

        for ((item, slotKey) in activeItems) {
            val meta = item.itemMeta ?: continue
            val baihuArtifact = plugin.baihuDzManager.getArtifactDataFromItem(item)
            val cData = if (baihuArtifact != null) {
                plugin.baihuDzManager.toCrystalData(baihuArtifact)
            } else {
                val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: continue
                plugin.playerManager.crystalManager.loadedCrystals[cid] ?: continue
            }

            // 妫€鏌ヨ繖涓グ鍝佹槸鍚﹀湪婵€娲讳綅缃紝骞朵笖婊¤冻鐜╁绛夌骇/鑱屼笟瑕佹眰
            val active = if (baihuArtifact != null) {
                plugin.baihuDzManager.isArtifactActiveForSkill(player, item, baihuArtifact, slotKey)
            } else {
                plugin.playerManager.crystalManager.isActive(cData, pData, slotKey, player, item)
            }
            if (active) {
                val targetId = cData.skillId ?: cData.id
                val skillClass = skills[targetId]

                // 濡傛灉杩欎釜楗板搧鏄洖娴佺被楗板搧锛屽氨鎶婂畠鐨勫疄渚嬪拰鏁版嵁杩斿洖鍥炲幓锛?
                if (skillClass is BaseRefluxSkill) {
                    return Pair(skillClass, cData)
                }
            }
        }
        return null
    }

    private fun findActiveSkill(player: Player, requestedSkillId: String): Pair<BaseAccessorySkill, CrystalData>? {
        val pData = plugin.playerManager.getPlayerData(player) ?: return null
        for ((item, slotKey) in getActiveAccessories(player)) {
            val meta = item.itemMeta ?: continue
            val baihuArtifact = plugin.baihuDzManager.getArtifactDataFromItem(item)
            val cData = if (baihuArtifact != null) {
                plugin.baihuDzManager.toCrystalData(baihuArtifact)
            } else {
                val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: continue
                plugin.playerManager.crystalManager.loadedCrystals[cid] ?: continue
            }

            if ((cData.skillId ?: cData.id) != requestedSkillId) continue
            val active = if (baihuArtifact != null) {
                plugin.baihuDzManager.isArtifactActiveForSkill(player, item, baihuArtifact, slotKey)
            } else {
                plugin.playerManager.crystalManager.isActive(cData, pData, slotKey, player, item)
            }
            if (active) return (skills[requestedSkillId] ?: continue) to cData
        }
        return null
    }

    companion object {
        private val HUD_ACCESSORY_IDS = setOf(
            "qingshidunpai",
            "cubujiandai",
            "huiliuyi",
            "taolizhi",
            "yanjingdunpai",
            "ranhuojiandai",
            "yanling",
            "jinshengzhi",
            "zhenyuechenfeng",
            "qianzhentianji",
            "xunlilingshu",
            "kanzelingzhi"
        )
    }
}

