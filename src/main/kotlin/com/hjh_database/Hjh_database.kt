package com.hjh_database

import com.hjh_database.alchemy.listener.AlchemyListener
import com.hjh_database.alchemy.manager.AlchemyManager
import com.hjh_database.chonghua.ChonghuaManager
import com.hjh_database.command.AdminCommand
import com.hjh_database.command.ResourceReloadCommand
import com.hjh_database.command.StatsCommand
import com.hjh_database.data.DatabaseManager
import com.hjh_database.data.PlayerManager
import com.hjh_database.dz.command.DzCommand
import com.hjh_database.dz.listener.StationListener
import com.hjh_database.dz.manager.DzLevelManager
import com.hjh_database.dz.manager.RecipeManager
import com.hjh_database.jobtrial.JobTrialManager
import com.hjh_database.listener.CombatListener
import com.hjh_database.listener.MenuListener
import com.hjh_database.listener.PlayerListener
import com.hjh_database.resource.ResourceListener
import com.hjh_database.resource.ResourceManager
import com.hjh_database.skill.element_zf.ElementZfManager
import com.hjh_database.skill.medical.spell.MedicalSpellManager
import com.hjh_database.skill.medical.spell.MedicalSpellListener
import com.hjh_database.skill.weapon.WeaponSkillListener
import com.hjh_database.skill.weapon.WeaponSkillManager
import com.hjh_database.ui.MenuManager
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import com.hjh_database.npc.NpcModule
import com.hjh_database.quest.core.QuestManager
import com.hjh_database.quest.gui.QuestGui
import com.hjh_database.skill.element_zf.gui.ElementZfGui
import com.hjh_database.spawner.SpawnerBlockManager
import com.hjh_database.weapon.WeaponManager


class Hjh_database : JavaPlugin() {
    companion object {
        lateinit var instance: Hjh_database
            private set
    }

    lateinit var databaseManager: DatabaseManager
    lateinit var playerManager: PlayerManager
    lateinit var weaponManager: WeaponManager
    lateinit var menuManager: MenuManager
    lateinit var elementZfManager: ElementZfManager
    lateinit var resourceManager: ResourceManager
    lateinit var recipeManager: RecipeManager
    lateinit var weaponSkillManager: WeaponSkillManager
    lateinit var dzLevelManager: DzLevelManager
    lateinit var kaiWuManager: com.hjh_database.kaiwu.KaiWuManager
    lateinit var medicalManager: com.hjh_database.skill.medical.MedicalManager
    lateinit var medicalSpellManager: MedicalSpellManager
    lateinit var npcModule: NpcModule
    lateinit var questManager: QuestManager
    lateinit var questGui: QuestGui
    lateinit var alchemyManager: AlchemyManager
    lateinit var raceModule: com.hjh_database.race.RaceManager
    lateinit var spawnerBlockManager: SpawnerBlockManager
    lateinit var teleportManager: com.hjh_database.teleport.TeleportManager
    lateinit var jobTrialManager: JobTrialManager
    lateinit var elementZfGui: ElementZfGui
    lateinit var chonghuaManager: ChonghuaManager


    override fun onEnable() {
        instance = this

        // ==========================================
        // 第一阶段：初始化核心数据与基础管理器
        // ==========================================
        this.databaseManager = DatabaseManager(this)
        this.playerManager = PlayerManager(this)

        this.weaponManager = WeaponManager(this)
        this.menuManager = MenuManager(this)
        this.elementZfManager = ElementZfManager(this)
        this.resourceManager = ResourceManager(this)
        this.dzLevelManager = DzLevelManager(this)
        this.recipeManager = RecipeManager(this)
        this.weaponSkillManager = WeaponSkillManager(this)
        this.kaiWuManager = com.hjh_database.kaiwu.KaiWuManager(this)
        this.medicalManager = com.hjh_database.skill.medical.MedicalManager(this)
        this.medicalSpellManager = MedicalSpellManager(this)
        this.questManager = QuestManager(this)
        this.raceModule = com.hjh_database.race.RaceManager(this)
        this.spawnerBlockManager = SpawnerBlockManager(this)
        this.teleportManager = com.hjh_database.teleport.TeleportManager(this)
        this.jobTrialManager = JobTrialManager(this)

        // 重华晶系统初始化
        this.chonghuaManager = ChonghuaManager(this)
        this.chonghuaManager.init()

        // 丹药系统初始化及自动注册
        this.alchemyManager = AlchemyManager(this)
        com.hjh_database.alchemy.AlchemyAutoRegister.registerAll(this, this.alchemyManager)

        // NPC模块初始化
        this.npcModule = NpcModule(this)
        this.npcModule.enable()

        // 羽毛系统（无需保存变量的直接初始化）
        com.hjh_database.feather.FeatherManager(this)


        // ==========================================
        // 第二阶段：初始化 GUI
        // ==========================================
        this.questGui = QuestGui(this)
        this.elementZfGui = ElementZfGui(this)


        // ==========================================
        // 第三阶段：注册所有事件监听器 (Listeners)
        // ==========================================
        val pm = server.pluginManager

        // 1. 基础系统监听
        pm.registerEvents(PlayerListener(this), this)
        pm.registerEvents(CombatListener(this), this)
        pm.registerEvents(MenuListener(this), this) // 依赖 questGui，放在后面注册合理
        pm.registerEvents(ResourceListener(this), this)
        pm.registerEvents(StationListener(this), this)
        pm.registerEvents(com.hjh_database.teleport.TeleportListener(this), this)
        pm.registerEvents(com.hjh_database.spawner.SpawnerListener(this), this)
        pm.registerEvents(com.hjh_database.npc.listener.NpcInteractListener(this), this)

        // 2. 技能与战斗相关监听
        pm.registerEvents(com.hjh_database.listener.SpellListener(this), this)
        pm.registerEvents(WeaponSkillListener(this), this)
        pm.registerEvents(com.hjh_database.kaiwu.KaiWuListener(this), this)
        pm.registerEvents(MedicalSpellListener(this), this)

        // 3. 独立系统与GUI监听
        pm.registerEvents(com.hjh_database.skill.medical.gui.MedicalEtchGui(this), this)
        pm.registerEvents(this.questGui, this)
        pm.registerEvents(AlchemyListener(this), this)
        pm.registerEvents(this.elementZfGui, this)
        pm.registerEvents(this.chonghuaManager, this)


        // ==========================================
        // 第四阶段：注册所有指令 (Commands)
        // ==========================================
        val adminCmd = AdminCommand(this)
        getCommand("hjhadmin")?.apply {
            setExecutor(adminCmd)
            tabCompleter = adminCmd
        }

        getCommand("hjhdz")?.setExecutor(DzCommand(this))
        getCommand("zfset")?.setExecutor(com.hjh_database.command.ZfCommand(this))

        getCommand("testmob")?.setExecutor(com.hjh_database.command.TestMobCommand(this))
        getCommand("hjhstats")?.setExecutor(StatsCommand(this))
        getCommand("hjhweapon")?.setExecutor(com.hjh_database.command.WeaponCommand(this))
        getCommand("hjhkw")?.setExecutor(com.hjh_database.kaiwu.KaiWuCommand(this))

        // 资源重载指令
        val resCmd = ResourceReloadCommand(this)
        getCommand("hjh")?.apply {
            setExecutor(resCmd)
            tabCompleter = resCmd as TabCompleter
        } ?: logger.warning("未在 plugin.yml 中找到 'hjh' 指令，资源重载指令无法使用！")


        // ==========================================
        // 第五阶段：启动后处理任务
        // ==========================================
        // 热重载加载数据 (确保在所有系统就绪后执行)
        server.scheduler.runTaskLater(this, Runnable {
            for (player in server.onlinePlayers) {
                playerManager.loadAndCache(player)
            }
        }, 10L)

        // 替换掉原来的 logger.info("画江湖核心数据系统 (HJH) 已启动 - 数据库模式")
        val startupLogo = """
            §b====================================================
            §e _   _       _   _   _ 
            §e| | | |     | | | | | |
            §e| |_| |     | | | |_| |
            §e|  _  |  _  | | |  _  |
            §e| | | | | |_| | | | | |
            §e\_| |_/  \___/  \_| |_/
            
            §a      【画江湖-梦回盘灵】插件已开启
            §b====================================================
        """.trimIndent()

        startupLogo.lines().forEach { server.consoleSender.sendMessage(it) }
    }

    override fun onDisable() {
        if (::playerManager.isInitialized) {
            playerManager.saveAllOnline()
        }

        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }

        if (::npcModule.isInitialized) {
            npcModule.disable()
        }

        // 替换掉原来的 logger.info("画江湖核心数据系统 (HJH) 已关闭")
        val shutdownLogo = """
            §b====================================================
            §e _   _       _   _   _ 
            §e| | | |     | | | | | |
            §e| |_| |     | | | |_| |
            §e|  _  |  _  | | |  _  |
            §e| | | | | |_| | | | | |
            §e\_| |_/  \___/  \_| |_/
            
            §c             【画江湖-梦回盘灵】插件已关闭
            §b====================================================
        """.trimIndent()

        shutdownLogo.lines().forEach { server.consoleSender.sendMessage(it) }
    }
}