package com.hjh_database

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

class Hjh_database : JavaPlugin() {
    // 添加伴生对象 (类似 Java 的 public static instance)
    companion object {
        lateinit var instance: Hjh_database
            private set // 外部只读，内部可写
    }

    // Kotlin 的 lateinit var 编译成 Java 后会自动生成 public getDatabaseManager() 方法
    // 同时也允许内部修改，完美对应原来的 getter 逻辑
    lateinit var databaseManager: DatabaseManager
    lateinit var playerManager: PlayerManager
    lateinit var menuManager: MenuManager // 新增
    lateinit var elementZfManager: ElementZfManager
    lateinit var resourceManager: ResourceManager
    lateinit var recipeManager: RecipeManager // 1. 声明变量

    // 在主类中添加
    lateinit var weaponSkillManager: WeaponSkillManager
    lateinit var dzLevelManager: DzLevelManager // 新增字段

    // 1. 添加成员变量
    lateinit var kaiWuManager: com.hjh_database.kaiwu.KaiWuManager

    // 【新增】在这里添加医师管理器变量
    lateinit var medicalManager: com.hjh_database.skill.medical.MedicalManager
    // 新加医师-医术管理器
    lateinit var medicalSpellManager: MedicalSpellManager

    override fun onEnable() {
        // 1. 初始化数据库连接
        // 建议：在实际生产环境中，将账号密码放入 config.yml 读取
        this.databaseManager = DatabaseManager(this)

        // 2. 初始化玩家数据管理器
        this.playerManager = PlayerManager(this)

        // 【新增】初始化菜单管理器
        this.menuManager = MenuManager(this)

        // 2. 初始化 阵法管理器 (建议放在 MenuManager 之后)
        this.elementZfManager = ElementZfManager(this)

        // 初始化资源管理器
        this.resourceManager = ResourceManager(this)

        // 【新增】 初始化 DzLevelManager
        // 建议放在 PlayerManager 之后，DzCommand 之前
        this.dzLevelManager = DzLevelManager(this)

        // 注册指令
        val adminCmd = AdminCommand(this)
        getCommand("hjhadmin")?.setExecutor(adminCmd)
        getCommand("hjhadmin")?.tabCompleter = adminCmd // 这一点很重要，不然没补全

        // 注册监听器
        server.pluginManager.registerEvents(ResourceListener(this), this)

        // 3. 注册 SpellListener
        server.pluginManager.registerEvents(com.hjh_database.listener.SpellListener(this), this)

        // 2. 初始化配方管理器 (一定要在 onEnable 里)
        this.recipeManager = RecipeManager(this)

        // 初始化技能管理器
        this.weaponSkillManager = WeaponSkillManager(this)

        // 初始化开物术管理器
        this.kaiWuManager = com.hjh_database.kaiwu.KaiWuManager(this)
        // 注册监听器 (放在 registerEvents 区域)
        server.pluginManager.registerEvents(com.hjh_database.kaiwu.KaiWuListener(this), this)

        // 注册监听器
        server.pluginManager.registerEvents(WeaponSkillListener(this), this)

        // 【新增】医师系统初始化
        // 1. 初始化管理器
        this.medicalManager = com.hjh_database.skill.medical.MedicalManager(this)
        // 2. 注册监听器 (这一步至关重要，没有它，右键织布机没反应)
        // 我们注册 MedicalEtchGui 作为监听器
        server.pluginManager.registerEvents(com.hjh_database.skill.medical.gui.MedicalEtchGui(this), this)
        // 初始化医术管理器
        this.medicalSpellManager = MedicalSpellManager(this)
        // 注册医术监听器
        server.pluginManager.registerEvents(MedicalSpellListener(this), this)

        // 3. 注册事件监听
        server.pluginManager.registerEvents(PlayerListener(this), this)
        server.pluginManager.registerEvents(CombatListener(this), this)
        // 【新增】注册菜单监听器
        server.pluginManager.registerEvents(MenuListener(this), this)
        // 在 onEnable 方法中 术士专用监听
        // (注：源代码中这里确实重复注册了一次 SpellListener，保留原逻辑)
        server.pluginManager.registerEvents(com.hjh_database.listener.SpellListener(this), this)

        // 注册命令
        getCommand("hjhdz")?.setExecutor(DzCommand(this))

        // 注册监听器
        server.pluginManager.registerEvents(StationListener(this), this)

        // 在 onEnable 方法里，指令注册的那部分下面添加：
        if (getCommand("testmob") != null) {
            getCommand("testmob")?.setExecutor(com.hjh_database.command.TestMobCommand(this))
        }
        // 注册指令
        getCommand("zfset")?.setExecutor(com.hjh_database.command.ZfCommand(this))
        // 4. 注册指令
        if (getCommand("hjhstats") != null) {
            getCommand("hjhstats")?.setExecutor(StatsCommand(this))
        }
        // 在 onEnable 中注册新指令
        if (getCommand("hjhweapon") != null) {
            getCommand("hjhweapon")?.setExecutor(com.hjh_database.command.WeaponCommand(this))
        }

        // 【新增】注册管理指令
        // (注：源代码中这里重复创建并注册了 AdminCommand，保留原逻辑)
        val adminCommand2 = AdminCommand(this)
        if (getCommand("hjhadmin") != null) {
            getCommand("hjhadmin")?.setExecutor(adminCommand2)
            getCommand("hjhadmin")?.tabCompleter = adminCommand2 // 注册 TabCompleter
        }

        // 开物术指令
        // 注册指令 (放在 getCommand 区域)
        if (getCommand("hjhkw") != null) {
            getCommand("hjhkw")?.setExecutor(com.hjh_database.kaiwu.KaiWuCommand(this))
        }


        // =========================================================
        // 【新增】注册 /hjh resourcereload 指令
        // =========================================================
        val resCmd = ResourceReloadCommand(this)
        if (getCommand("hjh") != null) {
            getCommand("hjh")?.setExecutor(resCmd)
            // Kotlin 中显式转换
            getCommand("hjh")?.tabCompleter = resCmd as TabCompleter
        } else {
            logger.warning("未在 plugin.yml 中找到 'hjh' 指令，资源重载指令无法使用！")
        }

        // 5. 【新增逻辑】处理热重载：立即加载所有在线玩家数据
        server.scheduler.runTaskLater(this, Runnable {
            for (player in server.onlinePlayers) {
                playerManager.loadAndCache(player)
            }
        }, 10L) // 延迟 10 刻（约 0.5 秒），给 Spigot 和数据库连接池时间稳定

        logger.info("画江湖核心数据系统 (HJH) 已启动 - 数据库模式")
    }

    override fun onDisable() {
        // 关闭时保存所有在线玩家数据
        // Kotlin 检查 lateinit 是否初始化替代 != null
        if (::playerManager.isInitialized) {
            playerManager.saveAllOnline()
        }

        // 关闭数据库连接
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
        logger.info("画江湖核心数据系统 (HJH) 已关闭")
    }

    // Kotlin 属性会自动生成 Getter，不需要手动编写
    // getDatabaseManager(), getPlayerManager() 等方法会自动存在
}