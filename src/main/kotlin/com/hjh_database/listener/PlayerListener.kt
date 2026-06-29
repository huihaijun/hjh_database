package com.hjh_database.listener

import com.hjh_database.Hjh_database
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.*
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class PlayerListener(private val plugin: Hjh_database) : Listener {

    private val pendingDeathEffects = mutableSetOf<java.util.UUID>()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.playerManager.loadAndCache(event.player)
        // 銆愭柊澧炪€戣繘鏈嶆椂锛屽紓姝ュ姞杞界帺瀹剁殑涓汉浠撳簱鏁版嵁
        plugin.warehouseManager.loadAndCache(event.player)
        plugin.elementCrystalManager.loadPlayer(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null) {
            data.currentHealth = player.health
        }
        plugin.playerManager.unloadAndSave(player.uniqueId)
        // 銆愭柊澧炪€戦€€鏈嶆椂锛屽紓姝ヤ繚瀛樺苟娓呯悊鐜╁鐨勪釜浜轰粨搴撴暟鎹?
        plugin.warehouseManager.saveAndRemove(player)
        plugin.elementCrystalManager.unloadPlayer(player)
    }

    // =================================================================
    //  鈿★笍 鏍稿績锛氬叏鏂逛綅鐘舵€佸悓姝ョ洃鍚?
    //  浠讳綍鍙兘瀵艰嚧鐗╁搧鏍忓彉鍔ㄧ殑浜嬩欢锛岄兘浼氳Е鍙?refreshPlayerStatus
    // =================================================================

    // 1. 鍒囨崲蹇嵎鏍?(婊氳疆/鏁板瓧閿?
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemHeld(event: PlayerItemHeldEvent) {
        refreshPlayerStatus(event.player)
    }

    // 2. 浜ゆ崲鍙屾墜鐗╁搧 (鎸塅)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        refreshPlayerStatus(event.player)
    }

    // 3. 涓㈠純鐗╁搧 (鎸塓)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        refreshPlayerStatus(event.player)
    }

    // 4. 鎹¤捣鐗╁搧
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickupItem(event: EntityPickupItemEvent) {
        if (event.entity is Player) {
            refreshPlayerStatus(event.entity as Player)
        }
    }

    // 5. 鐐瑰嚮鑳屽寘 (绉诲姩/绌挎埓/涓㈠純)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val who = event.whoClicked
        if (who is Player) {
            // 鍙鐞嗙帺瀹惰嚜宸辩殑鑳屽寘锛屾垨鑰呮秹鍙婂埌瑁呭鏍忕殑鎿嶄綔
            refreshPlayerStatus(who)
        }
    }

    // 6. 鍏抽棴鑳屽寘 (浣滀负鍏滃簳妫€鏌?
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player
        if (player is Player) {
            refreshPlayerStatus(player)
        }
    }

    // 7. 鐜╁澶嶆椿 (鏍规嵁 Status, Race, Job 鍔ㄦ€佽缃娲荤偣)
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data != null) {
            // 鑾峰彇涓栫晫锛屽鏋滀笘鐣屼笉瀛樺湪鍒欎娇鐢ㄧ帺瀹跺綋鍓嶆浜＄殑涓栫晫鍏滃簳
            val world = org.bukkit.Bukkit.getWorld("world") ?: player.world
            var targetLoc: org.bukkit.Location? = null
            when (data.status) {
                0 -> {
                    targetLoc = org.bukkit.Location(world, 1315.5, 76.5, 42.5, -90.0f, 0.0f)
                }
                1 -> {
                    targetLoc = org.bukkit.Location(world, 1248.05, 35.00, -364.01, 89.40f, 2.10f)
                }
                2 -> {
                    // 鏍规嵁绉嶆棌鍒嗛厤
                    targetLoc = when (data.race) {
                        0 -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f)
                        1 -> org.bukkit.Location(world, 3179.5, 127.0, 783.5, -90f, 0f)
                        2 -> org.bukkit.Location(world, 1689.5, 140.0, 138.5, 90f, 0f)
                        3 -> org.bukkit.Location(world, 3299.5, 22.0, -138.5, 90f, 0f)
                        4 -> org.bukkit.Location(world, 2845.5, 48.0, 899.5, 180f, -20f)
                        else -> org.bukkit.Location(world, 3208.5, 73.0, 381.5, 90f, 0f) // 榛樿鍘荤鏃?
                    }
                }
                3, 5, 6 -> {
                    // 3, 5, 6 澶嶆椿鐐逛竴鑷?
                    targetLoc = org.bukkit.Location(world, 205.0, 54.0, -1771.0, 0f, 0f)
                    // 鐘舵€佷负 3 鎴?5 鏃讹紝鏀瑰啓涓?6
                    if (data.status == 3 || data.status == 5) {
                        data.updateStatus(6)
                        // 鍙戦€佹秷鎭彁绀虹帺瀹?(鍙€?
                        // player.sendMessage("搂c浣犲湪澶ч檰/鍓湰涓櫒钀斤紝宸茶鎵撳叆濂堜綍妗?..")
                    }
                }
                4 -> {
                    // 鏍规嵁鑱屼笟鍒嗛厤 (璇风‘淇濆乏渚?0, 1, 2, 3 瀵瑰簲浣犳暟鎹簱涓疄闄呯殑鑱屼笟 ID)
                    targetLoc = when (data.job) {
                        0 -> org.bukkit.Location(world, 1247.5, 36.0, -391.5, 90.0f, 0.0f) // 鎴樺＋
                        1 -> org.bukkit.Location(world, 1247.5, 36.0, -411.5, 90.0f, 0.0f) // 寮撶鎵?
                        2 -> org.bukkit.Location(world, 1247.5, 36.0, -429.5, 90.0f, 0.0f) // 鏈＋
                        3 -> org.bukkit.Location(world, 1247.5, 36.0, -447.5, 90.0f, 0.0f) // 鍖诲笀
                        else -> org.bukkit.Location(world, 1247.5, 36.0, -391.5, 90.0f, 0.0f) // 榛樿涓㈢粰鎴樺＋
                    }
                }
            }

            // 濡傛灉鎴愬姛鍖归厤鍒颁簡鐩爣鍧愭爣锛屽垯璁剧疆澶嶆椿鐐?
            if (targetLoc != null) {
                event.respawnLocation = targetLoc
            }
        }

        // 鍒锋柊鐜╁鐘舵€?(鍘熸湁鐨勯€昏緫)
        refreshPlayerStatus(player)
        applyDeathEffectsAfterRespawn(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        pendingDeathEffects.add(event.entity.uniqueId)
    }

    /**
     * 缁熶竴鍒锋柊鏂规硶
     * 寤惰繜 1 Tick 鎵ц锛岀‘淇濅簨浠跺凡缁忓鐞嗗畬姣曪紝鐗╁搧宸茬粡鍦ㄦ柊浣嶇疆涓?
     */
    private fun refreshPlayerStatus(player: Player) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable

            // 鍒锋柊鎵€鏈夌墿鍝佺殑 Lore (瑙嗚鍙嶉)
            // 娉ㄦ剰锛氳繖閲岃皟鐢ㄧ殑鏄睘鎬?weaponManager (瀵瑰簲 Java 鐨?getWeaponManager())
            plugin.playerManager.weaponManager.refreshPlayerWeapons(player)

            // 鍒锋柊鎶ょ敳 Lore (鐘舵€佹樉绀?
            plugin.playerManager.armorManager.refreshPlayerArmors(player)

            // 鍒锋柊缁撴櫠 Lore (鐘舵€佹樉绀?
            plugin.playerManager.crystalManager.refreshPlayerCrystals(player)

            // 鍒锋柊铏庣槾瑁?Lore (鐦存皵銆佽€愪箙涓庢縺娲荤姸鎬?
            plugin.baihuDzManager.refreshPlayerEquipment(player)

            // 閲嶆柊璁＄畻鎵€鏈夊睘鎬?(鏁板€煎弽棣?
            plugin.playerManager.updateStats(player)

            // 3. (鍙€? 寮哄埗瀹㈡埛绔埛鏂拌儗鍖呮樉绀猴紝瑙ｅ喅鍋跺皵鐨?Lore 鏄剧ず寤惰繜
            // 娉ㄦ剰锛氶绻佽皟鐢?updateInventory 鍦ㄩ珮鐗堟湰閫氬父娌￠棶棰橈紝浣嗗湪鏋佹棫鐗堟湰鍙兘鏈夋€ц兘鎹熻€?
            // 濡傛灉浣犲彂鐜?Lore 杩樻槸鍋跺皵涓嶅埛鏂帮紝鍙栨秷涓嬮潰杩欒鐨勬敞閲?
            // player.updateInventory()

        }, 1L)
    }

    private fun applyDeathEffectsAfterRespawn(player: Player) {
        if (!pendingDeathEffects.remove(player.uniqueId)) return

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable

            player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false))
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 4, false, false))
            player.sendTitle("搂c搂l姝伙紒", "", 0, 40, 10)
        }, 1L)
    }
}

