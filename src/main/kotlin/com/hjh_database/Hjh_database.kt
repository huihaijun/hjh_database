package com.hjh_database

import com.hjh_database.alchemy.listener.AlchemyListener
import com.hjh_database.alchemy.manager.AlchemyManager
import com.hjh_database.command.AdminCommand
import com.hjh_database.command.ResourceReloadCommand
import com.hjh_database.command.StatsCommand
import com.hjh_database.data.DatabaseManager
import com.hjh_database.data.PlayerManager
import com.hjh_database.dz.command.DzCommand
import com.hjh_database.dz.listener.StationListener
import com.hjh_database.dz.manager.DzLevelManager
import com.hjh_database.dz.manager.RecipeManager
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
// 【新增】导入任务管理器
import com.hjh_database.quest.core.QuestManager
// 【建议】导入 QuestGui，代码看着干净点
import com.hjh_database.quest.gui.QuestGui

class Hjh_database : JavaPlugin() {
    companion object {
        lateinit var instance: Hjh_database
            private set
    }

    lateinit var databaseManager: DatabaseManager
    lateinit var playerManager: PlayerManager
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

    // 【新增】任务管理器变量
    lateinit var questManager: QuestManager
    lateinit var questGui: QuestGui // 变量声明在这里
    // 【新增】丹药管理器变量
    lateinit var alchemyManager: AlchemyManager
    // 【新增】种族祝福模块
    lateinit var raceModule: com.hjh_database.race.RaceManager

    override fun onEnable() {

        instance = this
        // 1. 初始化数据库
        this.databaseManager = DatabaseManager(this)

        // 2. 初始化玩家管理器
        this.playerManager = PlayerManager(this)

        // 初始化菜单
        this.menuManager = MenuManager(this)

        // 初始化阵法
        this.elementZfManager = ElementZfManager(this)

        // 初始化资源
        this.resourceManager = ResourceManager(this)

        // 初始化锻造等级
        this.dzLevelManager = DzLevelManager(this)

        // 初始化 NPC
        npcModule = NpcModule(this)
        npcModule.enable()

        // 注册指令
        val adminCmd = AdminCommand(this)
        getCommand("hjhadmin")?.setExecutor(adminCmd)
        getCommand("hjhadmin")?.tabCompleter = adminCmd

        // 注册资源监听
        server.pluginManager.registerEvents(ResourceListener(this), this)

        // 注册 SpellListener
        server.pluginManager.registerEvents(com.hjh_database.listener.SpellListener(this), this)

        // 初始化配方
        this.recipeManager = RecipeManager(this)

        // 初始化武器技能
        this.weaponSkillManager = WeaponSkillManager(this)

        // 初始化开物术
        this.kaiWuManager = com.hjh_database.kaiwu.KaiWuManager(this)
        server.pluginManager.registerEvents(com.hjh_database.kaiwu.KaiWuListener(this), this)

        // 注册武器技能监听
        server.pluginManager.registerEvents(WeaponSkillListener(this), this)

        // === 医师系统初始化 ===
        this.medicalManager = com.hjh_database.skill.medical.MedicalManager(this)
        server.pluginManager.registerEvents(com.hjh_database.skill.medical.gui.MedicalEtchGui(this), this)
        this.medicalSpellManager = MedicalSpellManager(this)
        server.pluginManager.registerEvents(MedicalSpellListener(this), this)

        // === 【新增】 任务系统初始化 ===
        // 1. 初始化 QuestManager
        this.questManager = QuestManager(this)

        // 2. 初始化 QuestGui 并赋值给变量 (修复报错的关键点)
        this.questGui = QuestGui(this)
        // 3. 注册 QuestGui 监听器
        server.pluginManager.registerEvents(this.questGui, this)

        // 【新增】丹药 (Alchemy) 系统初始化
        // ==========================================
        // 1. 初始化管理器 (加载配方)
        this.alchemyManager = AlchemyManager(this)
        // 2. 注册交互监听器 (处理锅的交互、木锄编辑等)
        server.pluginManager.registerEvents(AlchemyListener(this), this)
        // === 自动注册丹药 ===
        com.hjh_database.alchemy.AlchemyAutoRegister.registerAll(this, this.alchemyManager)

        // 初始化种族祝福模块
        raceModule = com.hjh_database.race.RaceManager(this)

        // 注册通用监听
        server.pluginManager.registerEvents(PlayerListener(this), this)
        server.pluginManager.registerEvents(CombatListener(this), this)
        // 注册 MenuListener (它会调用上面的 questGui，所以必须在后面注册)
        server.pluginManager.registerEvents(MenuListener(this), this)

        // 注册 NPC 交互监听 (它会调用 questManager，所以必须在后面注册)
        server.pluginManager.registerEvents(com.hjh_database.npc.listener.NpcInteractListener(this), this)

        // 注册其他命令
        getCommand("hjhdz")?.setExecutor(DzCommand(this))
        server.pluginManager.registerEvents(StationListener(this), this)

        if (getCommand("testmob") != null) {
            getCommand("testmob")?.setExecutor(com.hjh_database.command.TestMobCommand(this))
        }
        getCommand("zfset")?.setExecutor(com.hjh_database.command.ZfCommand(this))

        if (getCommand("hjhstats") != null) {
            getCommand("hjhstats")?.setExecutor(StatsCommand(this))
        }
        if (getCommand("hjhweapon") != null) {
            getCommand("hjhweapon")?.setExecutor(com.hjh_database.command.WeaponCommand(this))
        }
        if (getCommand("hjhkw") != null) {
            getCommand("hjhkw")?.setExecutor(com.hjh_database.kaiwu.KaiWuCommand(this))
        }

        // 资源重载指令
        val resCmd = ResourceReloadCommand(this)
        if (getCommand("hjh") != null) {
            getCommand("hjh")?.setExecutor(resCmd)
            getCommand("hjh")?.tabCompleter = resCmd as TabCompleter
        } else {
            logger.warning("未在 plugin.yml 中找到 'hjh' 指令，资源重载指令无法使用！")
        }

        // 热重载加载数据
        server.scheduler.runTaskLater(this, Runnable {
            for (player in server.onlinePlayers) {
                playerManager.loadAndCache(player)
            }
        }, 10L)

        logger.info("画江湖核心数据系统 (HJH) 已启动 - 数据库模式")
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

        logger.info("画江湖核心数据系统 (HJH) 已关闭")
    }
}