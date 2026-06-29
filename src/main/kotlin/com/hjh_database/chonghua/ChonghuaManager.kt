package com.hjh_database.chonghua

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File

// 定义四大区域
enum class Region(val displayName: String) {
    EAST("东方森林"), SOUTH("南方沙漠"), WEST("西方山脉"), NORTH("北方湖泊")
}

// 静态打卡点数据模型
data class Waypoint(
    val id: String,
    val region: Region,
    val name: String,
    val material: Material,
    val targetLoc: Location
)

class ChonghuaManager(private val plugin: Hjh_database) : Listener {

    // 新增：配置文件相关变量
    private val waypointsFile = File(plugin.dataFolder, "chonghua_waypoints.yml")
    private val waypointsConfig = YamlConfiguration()

    // 静态存储：所有的打卡点注册
    val waypoints = mutableMapOf<String, Waypoint>()
    // 在 ChonghuaManager 类中添加缓存 Map:
    val playerCache = java.util.concurrent.ConcurrentHashMap<java.util.UUID, ChonghuaData>()

    // 运行时存储管理员放置的方块 (保存至本地 yml 以防重启丢失)
    private val placedCrystals = mutableMapOf<Location, Region>()
    private val placedCheckins = mutableMapOf<Location, String>()
    private val dataFile = File(plugin.dataFolder, "chonghua_blocks.yml")
    private val config = YamlConfiguration()

    private val regionKey = NamespacedKey(plugin, "chonghua_region")
    private val waypointKey = NamespacedKey(plugin, "chonghua_waypoint")

    // 监听玩家进服：异步读取数据库，防止卡线程
    @EventHandler
    fun onPlayerJoin(e: org.bukkit.event.player.PlayerJoinEvent) {
        val player = e.player
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val data = plugin.databaseManager.loadChonghuaData(player.uniqueId, player.name)
            playerCache[player.uniqueId] = data
        })
    }

    // 监听玩家退服：保存数据并移出内存
    @EventHandler
    fun onPlayerQuit(e: org.bukkit.event.player.PlayerQuitEvent) {
        val data = playerCache.remove(e.player.uniqueId)
        if (data != null) {
            plugin.databaseManager.saveChonghuaData(data)
        }
    }

    // --- 玩家数据安全获取 ---
    private fun getChonghuaData(player: Player): com.hjh_database.chonghua.ChonghuaData? {
        val data = playerCache[player.uniqueId]
        if (data == null) {
            player.sendMessage("§c[系统] 正在紧急同步您的重华晶数据，请稍后重试...")
            // 异步加载避免卡服
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                val loaded = plugin.databaseManager.loadChonghuaData(player.uniqueId, player.name)
                playerCache[player.uniqueId] = loaded
                player.sendMessage("§a[系统] 数据同步完成！请再次右键点击方块。")
            })
        }
        return data
    }

    fun init() {
        // 1. 加载或生成 YML 打卡点配置
        loadWaypointsConfig()

        // 2. 加载管理员在地图上摆放的方块记录
        loadPlacedBlocks()

        // 3. 动态刷新 GUI Lore 的任务 (已移除 ID 显示)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()
            for (player in Bukkit.getOnlinePlayers()) {
                val view = player.openInventory
                if (view.title.startsWith("§0重华晶 -")) {
                    val inv = view.topInventory
                    val chonghuaData = getChonghuaData(player) ?: continue

                    for (i in 0 until inv.size) {
                        val item = inv.getItem(i) ?: continue
                        if (!item.hasItemMeta()) continue
                        val meta = item.itemMeta ?: continue

                        val key = NamespacedKey(plugin, "gui_waypoint_id")
                        if (meta.persistentDataContainer.has(key, PersistentDataType.STRING)) {
                            val wpId = meta.persistentDataContainer.get(key, PersistentDataType.STRING)!!

                            val isDefaultUnlocked = wpId.endsWith("huangcheng")
                            if (isDefaultUnlocked || chonghuaData.unlockedWaypoints.contains(wpId)) {
                                val lastTime = chonghuaData.waypointCooldowns[wpId] ?: 0L
                                val passSeconds = (now - lastTime) / 1000
                                val remain = 300 - passSeconds

                                val lore = mutableListOf<String>()
                                // 【优化 1】这里已经去掉了地点 ID 的显示
                                if (remain > 0) {
                                    lore.add("§c▶ 传送冷却中: ${remain}秒")
                                } else {
                                    lore.add("§a▶ 点击立即传送")
                                }
                                meta.lore = lore
                                item.itemMeta = meta
                            }
                        }
                    }
                }
            }
        }, 20L, 20L)
    }

    // --- 重载配置 ---
    fun reload() {
        // 重新加载静态打卡点配置文件
        loadWaypointsConfig()
        // 重新加载地图上管理员放置的实体方块记录 (可选)
        placedCrystals.clear()
        placedCheckins.clear()
        loadPlacedBlocks()
    }

    // --- YML 配置文件加载逻辑 ---
    private fun loadWaypointsConfig() {
        if (!waypointsFile.exists()) {
            waypointsFile.parentFile.mkdirs()
            generateDefaultWaypoints() // 如果没有文件，自动生成你之前的静态数据
        }

        waypointsConfig.load(waypointsFile)
        waypoints.clear()

        val section = waypointsConfig.getConfigurationSection("waypoints") ?: return
        for (key in section.getKeys(false)) {
            try {
                val regionStr = section.getString("$key.region") ?: "EAST"
                val region = Region.valueOf(regionStr.uppercase())
                val name = section.getString("$key.name") ?: key
                val matStr = section.getString("$key.material") ?: "FIRE_CHARGE"
                val material = Material.valueOf(matStr.uppercase())

                val w = section.getString("$key.location.world") ?: "world"
                val world = Bukkit.getWorld(w) ?: Bukkit.getWorlds()[0]
                val x = section.getDouble("$key.location.x")
                val y = section.getDouble("$key.location.y")
                val z = section.getDouble("$key.location.z")
                val yaw = section.getDouble("$key.location.yaw").toFloat()
                val pitch = section.getDouble("$key.location.pitch").toFloat()

                val loc = Location(world, x, y, z, yaw, pitch)
                waypoints[key] = Waypoint(key, region, name, material, loc)
            } catch (e: Exception) {
                plugin.logger.warning("打卡点 $key 配置有误加载失败: ${e.message}")
            }
        }
        plugin.logger.info("成功从 YML 加载了 ${waypoints.size} 个重华晶打卡点！")
    }

    private fun generateDefaultWaypoints() {
        fun add(id: String, r: String, n: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
            waypointsConfig.set("waypoints.$id.region", r)
            waypointsConfig.set("waypoints.$id.name", n)
            waypointsConfig.set("waypoints.$id.material", "FIRE_CHARGE")
            waypointsConfig.set("waypoints.$id.location.world", "world")
            waypointsConfig.set("waypoints.$id.location.x", x)
            waypointsConfig.set("waypoints.$id.location.y", y)
            waypointsConfig.set("waypoints.$id.location.z", z)
            waypointsConfig.set("waypoints.$id.location.yaw", yaw)
            waypointsConfig.set("waypoints.$id.location.pitch", pitch)
        }

        // --- 东方 ---
        add("east_huangcheng", "EAST", "皇城", 179.5, 42.5, 62.5, 180.47f, 7.05f) // 东区皇城
        add("longxuzhen", "EAST", "龙须镇", 528.5, 33.5, 25.5, -417.88f, 1.50f)
        add("chadiantan", "EAST", "茶点摊", 690.5, 70.5, 115.5, 1078.00f, 1.80f)
        add("shihuangling", "EAST", "始皇陵", 621.5, 9.5, -136.5, -629.68f, -1.05f)
        add("pobaidecunzhuang", "EAST", "破败的村庄", 556.5, 40.5, 341.5, -271.33f, 4.50f)
        add("qinglongjitan", "EAST", "青龙祭坛", 1696.5, 103.5, 867.5, -179.83f, -0.75f)

        // --- 南方 ---
        add("south_huangcheng", "SOUTH", "皇城", 179.5, 42.5, 62.5, 180.47f, 7.05f) // 南区皇城
        add("shamokezhan", "SOUTH", "沙漠客栈", -308.5, 58.5, 584.5, 449.57f, 4.80f)
        add("mazeituanchaoxue", "SOUTH", "马贼团巢穴", -162.5, 47.5, 580.5, 185.72f, 5.40f)
        add("lvzhouxiaozhen", "SOUTH", "绿洲小镇", -22.5, 47.5, 808.5, 273.32f, 4.80f)
        add("huoyanmowangdechaoxue", "SOUTH", "火焰魔王的巢穴", -298.5, 11.5, 800.5, 449.12f, 1.50f)
        add("feiqicunzhuang", "SOUTH", "废弃村庄", 335.5, 50.5, 781.5, 591.77f, -2.25f)
        add("zhuquejitan", "SOUTH", "朱雀祭坛", 3233.5, 148.5, -799.5, -90.13f, -3.60f)

        // --- 西方 ---
        add("west_huangcheng", "WEST", "皇城", 179.5, 42.5, 62.5, 180.47f, 7.05f) // 西区皇城
        add("west_zhenyaota", "WEST", "镇妖塔", -177.5, 63.5, -180.5, 90.0f, 0.0f)
        add("west_chendaifu_caoyaowu", "WEST", "陈大夫的草药屋", -132.5, 45.0, 140.5, 270.0f, 3.0f)
        add("west_hujinzhen", "WEST", "虎金镇", -400.5, 111.0, 145.5, 90.0f, 1.0f)
        add("west_baihujitan", "WEST", "白虎祭坛", 2206.5, 85.0, -895.5, 0.0f, -8.0f)

        // --- 北方 (暂未给坐标，先只放皇城) ---
        add("north_huangcheng", "NORTH", "皇城", 179.5, 42.5, 62.5, 180.47f, 7.05f) // 北区皇城

        waypointsConfig.save(waypointsFile)
    }

    // --- 方块记录读写逻辑 ---
    private fun loadPlacedBlocks() {
        if (!dataFile.exists()) return
        config.load(dataFile)
        config.getConfigurationSection("crystals")?.getKeys(false)?.forEach { key ->
            val loc = config.getLocation("crystals.$key")
            val regionName = config.getString("crystals_region.$key")
            if (loc != null && regionName != null) placedCrystals[loc] = Region.valueOf(regionName)
        }
        config.getConfigurationSection("checkins")?.getKeys(false)?.forEach { key ->
            val loc = config.getLocation("checkins.$key")
            val wpId = config.getString("checkins_id.$key")
            if (loc != null && wpId != null) placedCheckins[loc] = wpId
        }
    }

    private fun savePlacedBlocks() {
        config.set("crystals", null)
        config.set("crystals_region", null)
        placedCrystals.entries.forEachIndexed { index, entry ->
            config.set("crystals.$index", entry.key)
            config.set("crystals_region.$index", entry.value.name)
        }
        config.set("checkins", null)
        config.set("checkins_id", null)
        placedCheckins.entries.forEachIndexed { index, entry ->
            config.set("checkins.$index", entry.key)
            config.set("checkins_id.$index", entry.value)
        }
        config.save(dataFile)
    }

    // --- 事件处理: 放置与破坏 ---
    @EventHandler
    fun onBlockPlace(e: BlockPlaceEvent) {
        val meta = e.itemInHand.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        if (pdc.has(regionKey, PersistentDataType.STRING)) {
            val regionName = pdc.get(regionKey, PersistentDataType.STRING)!!
            placedCrystals[e.block.location] = Region.valueOf(regionName)
            e.player.sendMessage("§a[系统] 成功布置 ${Region.valueOf(regionName).displayName} 的重华晶！")
            savePlacedBlocks()
        } else if (pdc.has(waypointKey, PersistentDataType.STRING)) {
            val wpId = pdc.get(waypointKey, PersistentDataType.STRING)!!
            placedCheckins[e.block.location] = wpId
            e.player.sendMessage("§a[系统] 成功布置打卡点: $wpId")
            savePlacedBlocks()
        }
    }

    @EventHandler
    fun onBlockBreak(e: BlockBreakEvent) {
        val loc = e.block.location
        if (placedCrystals.remove(loc) != null || placedCheckins.remove(loc) != null) {
            e.player.sendMessage("§c[系统] 已移除该传送/打卡点方块。")
            savePlacedBlocks()
        }
    }

    // --- 事件处理: 玩家右键点击 ---
    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        // 过滤副手交互，防止触发两次
        if (e.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        if (e.action != Action.RIGHT_CLICK_BLOCK) return
        val loc = e.clickedBlock?.location ?: return

        // 点击重华晶
        if (placedCrystals.containsKey(loc)) {
            e.isCancelled = true
            openCrystalGUI(e.player, placedCrystals[loc]!!)
            return
        }

        // 点击打卡点
        if (placedCheckins.containsKey(loc)) {
            e.isCancelled = true
            val wpId = placedCheckins[loc]!!

            // 【修改点】使用安全获取方法
            val chonghuaData = getChonghuaData(e.player) ?: return

            if (!chonghuaData.unlockedWaypoints.contains(wpId)) {
                chonghuaData.unlockedWaypoints.add(wpId)
                val wp = waypoints[wpId]
                e.player.sendMessage("§a[系统] 恭喜你，已成功解锁传送地：${wp?.name ?: wpId}")
                // 【优化 2】打卡成功特效：播放升级音效与欢乐的绿色粒子
                e.player.playSound(loc, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f)
                e.player.world.spawnParticle(
                    org.bukkit.Particle.HAPPY_VILLAGER,
                    loc.clone().add(0.5, 1.2, 0.5), // 在方块上方一点点生成
                    15, // 粒子数量，15个适中不晃眼
                    0.3, 0.3, 0.3, 0.1
                )
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { plugin.databaseManager.saveChonghuaData(chonghuaData) })
            } else {
                e.player.sendMessage("§e[系统] 你已经解锁过此地点了。")
            }
        }
    }

    // --- GUI 逻辑 ---
    private fun openCrystalGUI(player: Player, region: Region) {
        val chonghuaData = getChonghuaData(player) ?: return
        val inv = Bukkit.createInventory(null, 54, "§0重华晶 - ${region.displayName}")
        val now = System.currentTimeMillis()

        // 【修改点 1】过滤出该区域的打卡点，并强制把 ID 包含 huangcheng 的排在最前面 (0在1前面)
        val waypointsInRegion = waypoints.values.filter { it.region == region }
            .sortedBy { if (it.id.endsWith("huangcheng")) 0 else 1 }

        var slot = 10
        for (wp in waypointsInRegion) {
            if (slot == 17) slot = 19
            if (slot == 26) slot = 28
            if (slot > 43) break

            val item = ItemStack(wp.material)
            val meta = item.itemMeta!!

            // 【修改点 2】特判皇城默认解锁
            val isDefaultUnlocked = wp.id.endsWith("huangcheng")
            val isUnlocked = isDefaultUnlocked || chonghuaData.unlockedWaypoints.contains(wp.id)

            if (!isUnlocked) {
                meta.setDisplayName("§7[未解锁] ${wp.name}")
                meta.lore = listOf("§c你尚未打卡此地点", "§c暂不可传送")
            } else {
                meta.setDisplayName("§a${wp.name}")
                val lastTime = chonghuaData.waypointCooldowns[wp.id] ?: 0L
                val passSeconds = (now - lastTime) / 1000
                val remain = 300 - passSeconds

                val lore = mutableListOf<String>()
                if (remain > 0) {
                    lore.add("§c▶ 传送冷却中: ${remain}秒")
                } else {
                    lore.add("§a▶ 点击立即传送")
                }
                meta.lore = lore
            }

            meta.persistentDataContainer.set(NamespacedKey(plugin, "gui_waypoint_id"), PersistentDataType.STRING, wp.id)
            item.itemMeta = meta
            inv.setItem(slot, item)
            slot++
        }
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        val view = e.view
        if (!view.title.startsWith("§0重华晶 -")) return
        e.isCancelled = true

        val player = e.whoClicked as? Player ?: return
        val item = e.currentItem ?: return
        val meta = item.itemMeta ?: return

        val key = NamespacedKey(plugin, "gui_waypoint_id")
        if (meta.persistentDataContainer.has(key, PersistentDataType.STRING)) {
            val wpId = meta.persistentDataContainer.get(key, PersistentDataType.STRING)!!
            val wp = waypoints[wpId] ?: return
            val chonghuaData = getChonghuaData(player) ?: return

            // 1. 检查是否解锁 (特判皇城)
            val isDefaultUnlocked = wpId.endsWith("huangcheng")
            if (!isDefaultUnlocked && !chonghuaData.unlockedWaypoints.contains(wpId)) {
                player.sendMessage("§c传送失败：你尚未解锁此地点！")
                player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
                return
            }

            // 2. 检查是否在冷却中
            val now = System.currentTimeMillis()
            val lastTime = chonghuaData.waypointCooldowns[wpId] ?: 0L
            val passSeconds = (now - lastTime) / 1000
            if (passSeconds < 300) {
                player.sendMessage("§c传送冷却中，还剩 ${300 - passSeconds} 秒！")
                player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f)
                return
            }

            // 3. 关闭 GUI，准备传送
            player.closeInventory()
            player.sendMessage("§e正在凝聚灵力，即将传送到 §a${wp.name} §e...")
            player.sendMessage("§a请保持原地站立 5 秒，移动将打断传送！")

            // 记录原始位置 (用于判断是否移动)
            val startLoc = player.location.clone()

            // 4. 开启 5 秒倒计时异步/同步任务
            object : org.bukkit.scheduler.BukkitRunnable() {
                var ticksPassed = 0

                override fun run() {
                    // 判断是否移动了 (distanceSquared > 0.25 相当于移动了 0.5 格，允许玩家转头)
                    if (player.location.world != startLoc.world || player.location.distanceSquared(startLoc) > 0.25) {
                        player.sendMessage("§c[系统] 传送已取消：你移动了位置！")
                        player.playSound(player.location, org.bukkit.Sound.BLOCK_GLASS_BREAK, 1.0f, 1.0f)
                        cancel()
                        return
                    }

                    ticksPassed += 10 // 每次累加 10 tick (0.5秒)

                    // 每 1 秒 (20 tick) 播放一次施法音效和粒子
                    if (ticksPassed % 20 == 0) {
                        player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.5f)
                        player.world.spawnParticle(org.bukkit.Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 5, 0.3, 0.5, 0.3, 0.01)
                    }

                    // 满 100 tick (5秒)，执行最终传送
                    if (ticksPassed >= 100) {
                        cancel() // 停止计时器

                        // 执行传送
                        player.teleport(wp.targetLoc)
                        player.sendMessage("§a传送成功！")

                        // 目的地播放到达音效和粒子
                        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f)
                        player.world.spawnParticle(org.bukkit.Particle.PORTAL, player.location.add(0.0, 1.0, 0.0), 50, 0.5, 1.0, 0.5, 0.1)

                        // 扣除冷却时间 (在真正传送成功后才进入冷却)
                        chonghuaData.waypointCooldowns[wpId] = System.currentTimeMillis()
                        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                            plugin.databaseManager.saveChonghuaData(chonghuaData)
                        })
                    }
                }
            }.runTaskTimer(plugin, 0L, 10L) // 0秒延迟，每 0.5 秒(10 tick) 运行一次
        }
    }

}
