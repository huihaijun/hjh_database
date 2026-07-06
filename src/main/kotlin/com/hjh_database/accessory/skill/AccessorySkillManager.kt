package com.hjh_database.accessory

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.accessory.skill.medical.JinshengzhiSkill
import com.hjh_database.accessory.skill.medical.TaolizhiSkill
import com.hjh_database.accessory.skill.quiver.*
import com.hjh_database.accessory.skill.shield.*
import com.hjh_database.accessory.skill.warlock.BaseRefluxSkill
import com.hjh_database.accessory.skill.warlock.HuiliuyiSkill
import com.hjh_database.accessory.skill.warlock.YanlingSkill
import com.hjh_database.accessory.element.ElementCrystalArmorCalculationEvent
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalHealEvent
import com.hjh_database.weapon.CrystalData
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot // 銆愭柊澧炲鍏ャ€戠敤浜庡垽鏂富鍓墜
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class AccessorySkillManager(private val plugin: Hjh_database) : Listener {
    private val crystalKey = NamespacedKey(plugin, "crystal_id")

    // 銆愮粺涓€娉ㄥ唽琛ㄣ€?
    private val skills = mapOf<String, BaseAccessorySkill>(
        "jiandai" to JiandaiSkill(plugin),
        "ranhuojiandai" to RanhuoJiandaiSkill(plugin),
        "qingshidunpai" to QingshidunpaiSkill(plugin),
        "yanjingdunpai" to YanjingdunpaiSkill(plugin),
        "huiliuyi" to HuiliuyiSkill(plugin),
        "yanling" to YanlingSkill(plugin),
        "taolizhi" to TaolizhiSkill(plugin),
        "jinshengzhi" to JinshengzhiSkill(plugin)
    )

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
                cData.activations.containsKey(slotKey) && cData.isActivated(pData)
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

    @EventHandler
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
                cData.activations.containsKey(slotKey) && cData.isActivated(data)
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

    @EventHandler
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
                cData.isActivated(pData) && cData.activations.containsKey("offhand")
            }
            if (!active) return
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onYanjingFlameDamage(event: EntityDamageEvent) {
        val skillClass = skills["yanjingdunpai"] as? YanjingdunpaiSkill ?: return
        skillClass.onPlayerDamage(event)
    }

    @EventHandler
    fun onRanhuoArmorCalculation(event: ElementCrystalArmorCalculationEvent) {
        val skillClass = skills["ranhuojiandai"] as? RanhuoJiandaiSkill ?: return
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
                cData.activations.containsKey(slotKey) && cData.isActivated(data)
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

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        (skills["yanling"] as? YanlingSkill)?.cleanup(event.player)
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
                cData.activations.containsKey(slotKey) && cData.isActivated(data)
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
                cData.activations.containsKey(slotKey) && cData.isActivated(pData)
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
}

