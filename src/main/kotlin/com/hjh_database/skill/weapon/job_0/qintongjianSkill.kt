package com.hjh_database.skill.weapon.job_0

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class qintongjianSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 现在只需要记录谁开了技能，不需要记录数值了
    // true 代表已激活
    private val activePlayers = ConcurrentHashMap<UUID, Boolean>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || data == null || config == null) return false

        val uuid = player.uniqueId

        if (activePlayers.containsKey(uuid)) {
            deactivate(player)
            return true
        } else {
            activate(player, data, config)
            return true
        }
    }

    private fun activate(player: Player, data: PlayerData, config: ConfigurationSection) {
        val pluginMain = plugin as Hjh_database
        val manager = pluginMain.weaponSkillManager

        manager?.registerToggle(player, "qintongjian") // 注册到管理器，防止切武器

        val armorPercent = config.getDouble("armor_bonus_percent", 0.25)
        val speedMalus = config.getDouble("speed_malus_percent", 0.25)
        val messageOn = config.getString("message_on", "&e&l[御守] &f进入防御姿态！护甲UP，移速DOWN")

        // === 新架构：直接写入临时属性 Map ===

        // 1. 设置护甲百分比加成 (PlayerManager 会读取 armor_percent 并处理)
        data.tempBonuses["armor_percent"] = armorPercent

        // 2. 设置移速百分比减免 (注意：减速是负数，所以这里取负)
        // 例如 speedMalus 是 0.25，这里存入 -0.25，PlayerManager 计算时就是 speed * (1 + (-0.25)) = 0.75倍
        data.tempBonuses["speed_percent"] = -speedMalus

        // 3. 标记玩家状态
        activePlayers[player.uniqueId] = true

        // 4. ★★★ 核心：调用 updateStats 刷新属性 ★★★
        // 这一步会自动重新计算所有属性（包括护甲、移速），并同步给原版客户端
        pluginMain.playerManager.updateStats(player)

        // 5. 特效与提示
        player.world.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 0.5f)
        player.world.spawnParticle(Particle.CRIT, player.location.add(0.0, 0.5, 0.0), 10, 0.3, 0.1, 0.3, 0.0)

        if (!messageOn.isNullOrEmpty()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(formatColor(messageOn)))
        }
    }

    override fun deactivate(player: Player) {
        val mainPlugin = plugin as? Hjh_database ?: return
        val data = mainPlugin.playerManager.getData(player.uniqueId) ?: return

        // 1. 检查是否开启
        if (activePlayers.remove(player.uniqueId) == null) return

        // 2. === 新架构：移除 Key 即可还原 ===
        data.tempBonuses.remove("armor_percent")
        data.tempBonuses.remove("speed_percent")

        // 3. 刷新属性 (属性瞬间变回原样)
        mainPlugin.playerManager.updateStats(player)

        // 4. 清理管理器记录
        mainPlugin.weaponSkillManager?.unregisterToggle(player)

        // 5. 提示
        player.world.playSound(player.location, Sound.BLOCK_ANVIL_LAND, 0.8f, 2.0f)
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§7[御守] 已退出防御姿态"))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // 玩家退出时，PlayerManager 会自动丢弃内存中的 PlayerData
        // tempBonuses 本身就不保存数据库，所以不需要手动去 data 里扣除属性
        // 我们只需要把 activePlayers 里的记录删掉，防止内存泄漏即可
        val uuid = event.player.uniqueId
        activePlayers.remove(uuid)

        // 注意：这里不需要调用 deactivate，因为玩家对象即将销毁，
        // 且下次上线时加载的是全新的干净数据。
    }

    private fun formatColor(str: String): String {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', str)
    }
}