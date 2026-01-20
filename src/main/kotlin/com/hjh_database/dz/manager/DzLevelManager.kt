package com.hjh_database.dz.manager

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.HashMap

class DzLevelManager(private val plugin: Hjh_database) {
    private val levelExpMap: MutableMap<Int, Int> = HashMap()
    private val licenseNameMap: MutableMap<Int, String> = HashMap()

    init {
        reload()
    }

    fun reload() {
        levelExpMap.clear()
        licenseNameMap.clear()

        val file = File(plugin.dataFolder, "dzlvl.yml")
        if (!file.exists()) {
            plugin.saveResource("dzlvl.yml", false)
        }

        val config = YamlConfiguration.loadConfiguration(file)

        // 加载经验表
        val levelsSection = config.getConfigurationSection("levels")
        if (levelsSection != null) {
            for (key in levelsSection.getKeys(false)) {
                try {
                    val lv = key.toInt()
                    val exp = config.getInt("levels.$key")
                    levelExpMap[lv] = exp
                } catch (ignored: NumberFormatException) {
                }
            }
        }

        // 加载资质名
        val licensesSection = config.getConfigurationSection("licenses")
        if (licensesSection != null) {
            for (key in licensesSection.getKeys(false)) {
                try {
                    val id = key.toInt()
                    // 注意：getString 可能返回 null，这里加个简单判断保证安全
                    val name = config.getString("licenses.$key")
                    if (name != null) {
                        licenseNameMap[id] = ChatColor.translateAlternateColorCodes('&', name)
                    }
                } catch (ignored: NumberFormatException) {
                }
            }
        }

        plugin.logger.info("已加载 ${levelExpMap.size} 个锻造等级设定和 ${licenseNameMap.size} 个资质名称。")
    }

    /**
     * 获取当前等级升级所需的最大经验
     * @param level 当前等级
     * @return 所需经验，如果达到满级（没有下一级配置）返回 -1
     */
    fun getMaxExp(level: Int): Int {
        // 默认为 -1 表示满级
        return levelExpMap.getOrDefault(level, -1)
    }

    /**
     * 获取资质名称
     */
    fun getLicenseName(licenseId: Int): String {
        return licenseNameMap.getOrDefault(licenseId, "§7未知资质")
    }
}