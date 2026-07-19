package com.hjh_database.skill.medical

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.banner.Pattern
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class MedicalManager(private val plugin: Hjh_database) {
    // 缓存：ConfigKey -> 物品
    private val skillBooks: MutableMap<String, ItemStack> = HashMap()
    // 缓存：SkillID -> 稀有度
    private val skillRarityMap: MutableMap<String, Int> = HashMap()
    // 缓存：SkillID -> 技能名
    private val skillNameCache: MutableMap<String, String> = HashMap()

    val keySkillId: NamespacedKey = NamespacedKey(plugin, "med_skill_id")
    val keyIgnoreRefresh: NamespacedKey = NamespacedKey(plugin, "hjh_ignore_refresh")
    val keyMedicalStation: NamespacedKey = NamespacedKey(plugin, "hjh_medical_station")

    private val loomSessions: MutableMap<UUID, Array<ItemStack?>> = ConcurrentHashMap()

    init {
        loadSkillBooks()
    }

    fun loadSkillBooks() {
        skillBooks.clear()
        skillRarityMap.clear()
        skillNameCache.clear()

        val file = File(plugin.dataFolder, "medical_items.yml")
        if (!file.exists()) plugin.saveResource("medical_items.yml", false)
        val config = YamlConfiguration.loadConfiguration(file)

        val items = config.getConfigurationSection("items")
        if (items != null) {
            for (key in items.getKeys(false)) {
                val sec = items.getConfigurationSection(key)
                // sec 可能为 null，使用 !! 断言
                val skillId = sec!!.getString("skill_id", key)!!
                // 【修复】处理名字颜色
                val name = ChatColor.translateAlternateColorCodes('&', sec.getString("name", "未知医术")!!)
                val rarity = sec.getInt("rarity", 1)

                // 构建物品
                val matName = sec.getString("material", "PAPER")!!
                val item = ItemStack(Material.valueOf(matName))
                // 【关键点】itemMeta 可能为空，使用 !! 断言
                val meta = item.itemMeta!!
                meta.setDisplayName(name)

                // === 构建 Lore (修改点) ===
                val rawLore = sec.getStringList("lore")
                val finalLore: MutableList<String> = ArrayList()

                // 1. 【新增】手动插入稀有度行 (确保在第一行，紧跟名字)
                val rarityColor: String = when (rarity) {
                    1 -> "§f" // 白
                    2 -> "§a" // 绿
                    3 -> "§9" // 蓝
                    4 -> "§d" // 粉
                    5 -> "§e" // 黄
                    6 -> "§c" // 红
                    else -> "§7"
                }

                // 这里的 getRarityStars 方法见下方
                finalLore.add(rarityColor + "稀有度: " + getRarityStars(rarity))

                // 2. 【修复】追加配置文件的 Lore (处理颜色)
                for (line in rawLore) {
                    finalLore.add(ChatColor.translateAlternateColorCodes('&', line))
                }
                meta.lore = finalLore

                // 写入 NBT
                meta.persistentDataContainer.set(keySkillId, PersistentDataType.STRING, skillId)
                meta.persistentDataContainer.set(NamespacedKey(plugin, "rarity"), PersistentDataType.INTEGER, rarity)

                // 3. 【关键修复】加上免刷新锁！
                // 只有加上这个，ResourceListener 才会跳过它，防止Lore被刷没
                meta.persistentDataContainer.set(keyIgnoreRefresh, PersistentDataType.INTEGER, 1)
                item.itemMeta = meta

                // 存入缓存
                skillBooks[skillId] = item
                skillRarityMap[skillId] = rarity
                skillNameCache[skillId] = name
            }
        }
    }

    // 请确保类里有这个辅助方法 (生成星星)
    private fun getRarityStars(rarity: Int): String {
        val sb = StringBuilder()
        for (i in 0 until rarity) sb.append("★")
        return sb.toString()
    }

    // 获取缓存中的书籍 (带颜色和NBT的成品)
    fun getSkillBook(skillId: String): ItemStack? {
        return skillBooks[skillId]
    }

    fun getSkillName(skillId: String): String {
        return skillNameCache.getOrDefault(skillId, "未知医术")
    }

    fun getAllSkillIds(): Set<String> {
        return skillNameCache.keys
    }

    fun getLoomSession(player: Player): Array<ItemStack?>? {
        return loomSessions[player.uniqueId]
    }

    fun saveLoomSession(player: Player, contents: Array<ItemStack?>) {
        loomSessions[player.uniqueId] = contents
    }

    fun getMedicalStationItem(): ItemStack {
        val item = ItemStack(Material.END_PORTAL_FRAME)
        val meta = item.itemMeta!!
        meta.setDisplayName("§b§l医术绘制台")
        meta.lore = Arrays.asList("§7放置后右键点击打开医术界面")
        meta.persistentDataContainer.set(keyMedicalStation, PersistentDataType.STRING, "true")
        item.itemMeta = meta
        return item
    }

    fun isMedicalBanner(item: ItemStack?): Boolean {
        // 使用 !! 断言 meta 非空，前提是 hasItemMeta 为 true
        return item != null && item.hasItemMeta() && item.itemMeta!!.persistentDataContainer.has(
            keySkillId,
            PersistentDataType.STRING
        )
    }

    fun getSkillIdFromBanner(item: ItemStack?): String? {
        return if (isMedicalBanner(item)) item!!.itemMeta!!.persistentDataContainer.get(
            keySkillId,
            PersistentDataType.STRING
        ) else null
    }

    // === 刻印逻辑 ===
    fun etchSkill(player: Player, banner: ItemStack?, book: ItemStack?): ItemStack? {
        if (banner == null || book == null) return null
        if (!banner.type.name.endsWith("_BANNER")) return null

        val bookMeta = book.itemMeta ?: return null
        val skillId = bookMeta.persistentDataContainer.get(keySkillId, PersistentDataType.STRING) ?: return null

        if (isMedicalBanner(banner)) {
            player.sendMessage("§c请先将已有医术分离开来，再绘制新的医术")
            return null
        }

        // ================= 【修复 2：等阶限制】 =================
        // 获取医旗和医术书的 rarity (如果获取不到则默认按 1 阶算)
        val keyRarity = NamespacedKey(plugin, "rarity")
        val bookRarity = bookMeta.persistentDataContainer.get(keyRarity, PersistentDataType.INTEGER) ?: 1
        val bannerRarity = banner.persistentDataContainer.get(keyRarity, PersistentDataType.INTEGER) ?: 1

        if (bookRarity > bannerRarity) {
            player.sendMessage("§c[绘制失败] §7医旗等阶 ( $bannerRarity 阶) 无法承载更高阶的医术 ( $bookRarity 阶)！")
            return null
        }

        // 检查重复掌握
        // 【关键点】显式使用 !! 断言，将 PlayerData? 转为 PlayerData
        val data = plugin.playerManager.getPlayerData(player)!!

        // ================= 【修复：数量上限限制】 =================
        if (data.getMedicalLoadout().size >= 5) {
            player.sendMessage("§c[绘制失败] §7你最多只能同时掌握 5 种医术！")
            return null
        }

        if (data.getMedicalLoadout().contains(skillId)) {
            player.sendMessage("§c[绘制失败] §7你脑海中已经掌握了此医术。")
            return null
        }

        val result = banner.clone()
        result.amount = 1
        val resultMeta = result.itemMeta!!

        // 修改显示名称：原名 + [医术名]
        val skillDisplayName = getSkillName(skillId)
        if (resultMeta.hasDisplayName()) {
            resultMeta.setDisplayName(buildEtchedBannerName(resultMeta.displayName, skillDisplayName))
        } else {
            resultMeta.setDisplayName(buildEtchedBannerName("§f医旗", skillDisplayName))
        }

        // 1. 设置技能 ID
        resultMeta.persistentDataContainer.set(keySkillId, PersistentDataType.STRING, skillId)

        // 2. 【核心】加上免刷新锁 (配合 WeaponManager 修复)
        resultMeta.persistentDataContainer.set(keyIgnoreRefresh, PersistentDataType.INTEGER, 1)

        // 3. 隐藏原版旗帜图案
        resultMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)

        // 4. Lore 继承 (过滤掉提示语)
        val lore = if (resultMeta.hasLore()) resultMeta.lore!! else ArrayList()
        lore.add("§8----------------")
        lore.add("§6[医术] §e" + getSkillName(skillId))

        if (bookMeta.hasLore()) {
            for (line in bookMeta.lore!!) {
                if (line.contains("放入绘制台")) continue
                lore.add(line)
            }
        }
        resultMeta.lore = lore

        // 5. 绘制图案
        if (resultMeta is BannerMeta) {
            val patterns: List<Pattern>? = MedicalPatternRegistry.getPatterns(skillId)
            if (patterns != null) {
                for (p in patterns) resultMeta.addPattern(p)
            }
        }

        result.itemMeta = resultMeta

        data.addMedicalSkillMemory(skillId)
        CompletableFuture.runAsync { plugin.databaseManager.saveMedicalData(data) }

        player.sendMessage("§a[绘制成功] §7你将 §e" + getSkillName(skillId) + " §7刻印于旗帜之上！")
        player.playSound(player.location, Sound.UI_LOOM_TAKE_RESULT, 1f, 1f)
        return result
    }

    // === 智能刷新：重塑刻印旗帜 ===
    fun rebuildEtchedBanner(banner: ItemStack, skillId: String) {
        val meta = banner.itemMeta ?: return
        // 1. 恢复 NBT 标签 (防止丢数据)
        meta.persistentDataContainer.set(keySkillId, PersistentDataType.STRING, skillId)
        meta.persistentDataContainer.set(keyIgnoreRefresh, PersistentDataType.INTEGER, 1)
        // 2. 重新拼接名字
        val skillDisplayName = getSkillName(skillId)
        if (meta.hasDisplayName()) {
            meta.setDisplayName(buildEtchedBannerName(meta.displayName, skillDisplayName))
        } else {
            meta.setDisplayName(buildEtchedBannerName("§f医旗", skillDisplayName))
        }

        // 3. 隐藏原版旗帜图案等提示
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)

        // 4. 获取当前最新的医术秘籍 Lore，并重新拼接在医旗 Lore 的底部
        val lore = if (meta.hasLore()) meta.lore!! else ArrayList()
        lore.add("§8----------------")
        lore.add("§6[医术] §e$skillDisplayName")

        // 获取最新的医术书物品，以便读取最新的文本
        val book = getSkillBook(skillId)
        if (book != null && book.hasItemMeta() && book.itemMeta!!.hasLore()) {
            for (line in book.itemMeta!!.lore!!) {
                if (line.contains("放入绘制台")) continue // 过滤掉不需要的提示
                lore.add(line)
            }
        }
        meta.lore = lore

        // 5. 恢复旗帜图案
        if (meta is BannerMeta) {
            val patterns = MedicalPatternRegistry.getPatterns(skillId)
            if (patterns != null) {
                for (p in patterns) meta.addPattern(p)
            }
        }

        banner.itemMeta = meta
    }

    fun buildEtchedBannerName(baseName: String, skillDisplayName: String): String {
        val cleanBase = baseName.substringBefore("§r[")
        return "$cleanBase§r[$skillDisplayName§r]"
    }

    // === 分离逻辑 ===
    fun separateSkill(player: Player, inputBanner: ItemStack?): Array<ItemStack>? {
        if (!isMedicalBanner(inputBanner)) return null

        // getSkillIdFromBanner 在 isMedicalBanner 检查后一般安全，但这里为了逻辑严谨使用 !!
        val skillId = getSkillIdFromBanner(inputBanner)!!

        // 【关键点】显式使用 !! 断言
        val data = plugin.playerManager.getPlayerData(player)!!

        if (!data.getMedicalLoadout().contains(skillId)) {
            player.sendMessage("§c[分离失败] §7你并未掌握此医术。")
            return null
        }

        var book = getSkillBook(skillId)
        if (book == null) book = ItemStack(Material.PAPER)
        val returnBook = book!!.clone()
        returnBook.amount = 1

        val blankBanner = inputBanner!!.clone()
        blankBanner.amount = 1
        val meta = blankBanner.itemMeta!!

        // 还原显示名称：移除 [医术名]
        val skillDisplayName = getSkillName(skillId)
        if (meta.hasDisplayName()) {
            val currentName = meta.displayName
            // 构造后缀字符串（必须和etchSkill里加的一模一样）
            val suffix = "§r[$skillDisplayName§r]"

            if (currentName.contains(suffix)) {
                // 将后缀替换为空
                meta.setDisplayName(currentName.replace(suffix, ""))
            }
        }
        meta.persistentDataContainer.remove(keySkillId)

        // 【核心】移除免刷新锁 (让它变回普通武器，可以被 WeaponManager 刷新属性)
        meta.persistentDataContainer.remove(keyIgnoreRefresh)

        // 智能清理 Lore
        if (meta.hasLore()) {
            val bannerLore = meta.lore!!
            var originalSkillLore: List<String> = ArrayList()
            if (book.hasItemMeta() && book.itemMeta!!.hasLore()) {
                originalSkillLore = book.itemMeta!!.lore!!
            }
            val linesToRemove: MutableList<String> = ArrayList(originalSkillLore)
            linesToRemove.add("§6[医术] §e" + getSkillName(skillId))
            linesToRemove.add("§8----------------")

            for (removeTarget in linesToRemove) {
                bannerLore.remove(removeTarget)
            }
            meta.lore = bannerLore
        }

        // 清除图案
        if (meta is BannerMeta) {
            meta.patterns = ArrayList()
        }

        blankBanner.itemMeta = meta

        data.removeMedicalSkillMemory(skillId)
        CompletableFuture.runAsync { plugin.databaseManager.saveMedicalData(data) }

        player.sendMessage("§a[分离成功] §7医术已剥离。")
        player.playSound(player.location, Sound.BLOCK_GRINDSTONE_USE, 1f, 1f)

        return arrayOf(blankBanner, returnBook)
    }
}
