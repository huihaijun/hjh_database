package com.hjh_database

import com.hjh_database.accessory.AccessorySkillManager
import com.hjh_database.accessory.skill.quiver.JiandaiSkill
import com.hjh_database.alchemy.listener.AlchemyListener
import com.hjh_database.alchemy.manager.AlchemyManager
import com.hjh_database.artifact.ArtifactManager
import com.hjh_database.artifact.LingyunsuoSkill
import com.hjh_database.artifact.QueqiaoyinSkill
import com.hjh_database.artifact.QueshuanglingSkill
import com.hjh_database.artifact.TianheyiSkill
import com.hjh_database.baihu_dz.BaihuDzManager
import com.hjh_database.baihu_dz.BaihuDzStationListener
import com.hjh_database.baihu_dz.BaihuEquipmentDamageMarkerListener
import com.hjh_database.baihu_dz.BaihuWeaponSkillListener
import com.hjh_database.baihu_dz.skill.impl.HuzhizhanqiSkill
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkillManager
import com.hjh_database.chonghua.ChonghuaManager
import com.hjh_database.client.ClientBridge
import com.hjh_database.command.AdminCommand
import com.hjh_database.command.ResourceReloadCommand
import com.hjh_database.command.StatsCommand
import com.hjh_database.data.DatabaseManager
import com.hjh_database.data.PlayerManager
import com.hjh_database.passbook.PassbookListener
import com.hjh_database.qixiazhen.farming.listener.FarmingListener
import com.hjh_database.qixiazhen.farming.manager.FarmingManager
import com.hjh_database.qixiazhen.busuan.BusuanListener
import com.hjh_database.qixiazhen.busuan.BusuanManager
import com.hjh_database.dungeon.chest.GoldenChestManager
import com.hjh_database.dungeon.chest.VaultChestListener
import com.hjh_database.dungeon.qixi.QixiDungeonManager
import com.hjh_database.dungeon.shengshan.ShengShanDungeonManager
import com.hjh_database.event.qixi.QixiBridgeBuildManager
import com.hjh_database.dungeon.baihu.trial.BaihuTrialManager
import com.hjh_database.dungeon.qinglong.QingLongManager
import com.hjh_database.dungeon.xuanwu.trial.XuanwuTrialManager
import com.hjh_database.dungeon.zhuque.ZhuQueManager
import com.hjh_database.dz.listener.StationListener
import com.hjh_database.dz.manager.DzLevelManager
import com.hjh_database.dz.manager.RecipeManager
import com.hjh_database.jobtrial.JobTrialManager
import com.hjh_database.listener.CombatListener
import com.hjh_database.listener.DamageTestManager
import com.hjh_database.listener.MenuListener
import com.hjh_database.ui.TianjiUtilityMenus
import com.hjh_database.listener.PlayerListener
import com.hjh_database.listener.RaidPreventionListener
import com.hjh_database.market.GlobalMarketManager
import com.hjh_database.rebirth.RebirthListener
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
import com.hjh_database.spawner.BaihuMiasmaManager
import com.hjh_database.spawner.BaihuTownFireManager
import com.hjh_database.ui.AccessoryManager
import com.hjh_database.weapon.WeaponManager
import com.hjh_database.medical.MedicalTrialManager // 【新增】引入医术试炼管理器
import com.hjh_database.spawner.impl.CustomMagmaCubeListener
import com.hjh_database.spawner.impl.BaihuWestSkill
import com.hjh_database.spawner.impl.DesertSouthSkill
import com.hjh_database.spawner.impl.NorthWetnessSkill
import com.hjh_database.spawner.impl.ResentmentAffixSkill
import com.hjh_database.accessory.element.ElementCrystalManager
import com.hjh_database.equipment.activation.EquipmentActivationManager
import com.hjh_database.accessory.element.ElementCrystalGui
import com.hjh_database.accessory.element.ElementCrystalInteractListener
import com.hjh_database.title.TitleListener
import com.hjh_database.title.TitleManager
import com.hjh_database.title.TitleClaimBeaconListener
import com.hjh_database.subtitle.PassiveSubtitleManager

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
    lateinit var kaiWuAdminGui: com.hjh_database.kaiwu.KaiWuAdminGui
    lateinit var medicalManager: com.hjh_database.skill.medical.MedicalManager
    lateinit var medicalSpellManager: MedicalSpellManager
    lateinit var npcModule: NpcModule
    lateinit var questManager: QuestManager
    lateinit var questGui: QuestGui
    lateinit var alchemyManager: AlchemyManager
    lateinit var raceModule: com.hjh_database.race.RaceManager
    lateinit var shenConsciousnessManager: com.hjh_database.race.shen.ShenConsciousnessManager
    lateinit var shenTributeManager: com.hjh_database.race.shen.ShenTributeManager
    lateinit var xianTalentManager: com.hjh_database.race.xian.XianTalentManager
    lateinit var spawnerBlockManager: SpawnerBlockManager
    lateinit var baihuMiasmaManager: BaihuMiasmaManager
    lateinit var baihuTownFireManager: BaihuTownFireManager
    lateinit var baihuDzManager: BaihuDzManager
    lateinit var baihuWeaponSkillManager: BaihuWeaponSkillManager
    lateinit var artifactManager: ArtifactManager
    lateinit var tianheyiSkill: TianheyiSkill
    lateinit var queqiaoyinSkill: QueqiaoyinSkill
    lateinit var queshuanglingSkill: QueshuanglingSkill
    lateinit var lingyunsuoSkill: LingyunsuoSkill
    lateinit var teleportManager: com.hjh_database.teleport.TeleportManager
    lateinit var bgmManager: com.hjh_database.bgm.BgmManager
    lateinit var jobTrialManager: JobTrialManager
    lateinit var elementZfGui: ElementZfGui
    lateinit var chonghuaManager: ChonghuaManager
    lateinit var accessoryManager: AccessoryManager // 饰品栏管理器
    lateinit var qingLongManager: QingLongManager// 青龙试炼管理器
    lateinit var zhuQueManager: ZhuQueManager// 朱雀试炼管理器
    lateinit var baihuTrialManager: BaihuTrialManager
    lateinit var xuanwuTrialManager: XuanwuTrialManager
    lateinit var goldenChestManager: GoldenChestManager //金宝箱管理器
    lateinit var qixiDungeonManager: QixiDungeonManager
    lateinit var shengShanDungeonManager: ShengShanDungeonManager
    lateinit var qixiBridgeBuildManager: QixiBridgeBuildManager
    lateinit var warehouseManager: com.hjh_database.warehouse.manager.WarehouseManager // 【新增】个人仓库管理器
    lateinit var adminWarehouseGui: com.hjh_database.warehouse.admin.AdminWarehouseGui
    lateinit var medicalTrialManager: MedicalTrialManager // 【新增】医术试炼管理器
    lateinit var jianghuXindeManager: com.hjh_database.jianghu.JianghuXindeManager
    lateinit var passbookListener: PassbookListener
    // 新增：箭袋管理器
    lateinit var jiandaiSkill: JiandaiSkill
    val accessorySkillManager = AccessorySkillManager(this)

    lateinit var elementCrystalManager: ElementCrystalManager
    lateinit var elementCrystalGui: ElementCrystalGui
    lateinit var farmingManager: FarmingManager
    lateinit var busuanManager: BusuanManager
    lateinit var globalMarketManager: GlobalMarketManager
    lateinit var titleManager: TitleManager
    lateinit var damageTestManager: DamageTestManager
    lateinit var featherManager: com.hjh_database.feather.FeatherManager
    lateinit var equipmentActivationManager: EquipmentActivationManager
    lateinit var passiveSubtitleManager: PassiveSubtitleManager
    lateinit var clientBridge: ClientBridge
    fun isBaihuDzManagerInitialized(): Boolean {
        return this::baihuDzManager.isInitialized
    }

    override fun onEnable() {
        instance = this
        TianjiUtilityMenus.closeStaleMenusOnEnable()

        // ==========================================
        // 第一阶段：初始化核心数据与基础管理器
        // ==========================================
        this.databaseManager = DatabaseManager(this)
        this.playerManager = PlayerManager(this)
        this.clientBridge = ClientBridge(this)
        this.clientBridge.start()
        this.passiveSubtitleManager = PassiveSubtitleManager(this)
        // PlayerManager 持有唯一的普通武器管理器，避免重复加载配置和热重载数据分叉。
        this.weaponManager = this.playerManager.weaponManager
        this.menuManager = MenuManager(this)
        this.elementZfManager = ElementZfManager(this)
        // 配方加载需要先按 artifact_id 构造普通法宝。
        this.artifactManager = ArtifactManager(this)
        this.resourceManager = ResourceManager(this)
        this.dzLevelManager = DzLevelManager(this)
        this.recipeManager = RecipeManager(this)
        this.weaponSkillManager = WeaponSkillManager(this)
        this.kaiWuManager = com.hjh_database.kaiwu.KaiWuManager(this)
        this.kaiWuAdminGui = com.hjh_database.kaiwu.KaiWuAdminGui(this, this.kaiWuManager)
        this.medicalManager = com.hjh_database.skill.medical.MedicalManager(this)
        this.medicalSpellManager = MedicalSpellManager(this)
        this.questManager = QuestManager(this)
        this.shenConsciousnessManager = com.hjh_database.race.shen.ShenConsciousnessManager(this)
        this.shenTributeManager = com.hjh_database.race.shen.ShenTributeManager(this)
        this.raceModule = com.hjh_database.race.RaceManager(this)
        this.spawnerBlockManager = SpawnerBlockManager(this)
        this.baihuMiasmaManager = BaihuMiasmaManager(this)
        this.baihuTownFireManager = BaihuTownFireManager(this)
        this.baihuDzManager = BaihuDzManager(this)
        this.baihuWeaponSkillManager = BaihuWeaponSkillManager(this)
        this.tianheyiSkill = TianheyiSkill(this)
        this.queqiaoyinSkill = QueqiaoyinSkill(this)
        this.queshuanglingSkill = QueshuanglingSkill(this)
        this.lingyunsuoSkill = LingyunsuoSkill(this)
        this.equipmentActivationManager = EquipmentActivationManager(this)
        this.teleportManager = com.hjh_database.teleport.TeleportManager(this)
        this.bgmManager = com.hjh_database.bgm.BgmManager(this)
        this.jobTrialManager = JobTrialManager(this)
        this.farmingManager = FarmingManager(this)
        this.busuanManager = BusuanManager(this)
        this.globalMarketManager = GlobalMarketManager(this)
        this.titleManager = TitleManager(this)
        this.damageTestManager = DamageTestManager(this)

        // 重华晶系统初始化
        this.chonghuaManager = ChonghuaManager(this)
        this.chonghuaManager.init()
        this.xianTalentManager = com.hjh_database.race.xian.XianTalentManager(this)

        // 丹药系统初始化及自动注册
        this.alchemyManager = AlchemyManager(this)
        com.hjh_database.alchemy.AlchemyAutoRegister.registerAll(this, this.alchemyManager)

        // NPC模块初始化
        this.npcModule = NpcModule(this)
        this.npcModule.enable()

        // 羽毛系统
        this.featherManager = com.hjh_database.feather.FeatherManager(this)
        // 饰品栏
        this.accessoryManager = AccessoryManager(this)
        jiandaiSkill = JiandaiSkill(this)
        server.pluginManager.registerEvents(accessorySkillManager, this)
        // 青龙试炼
        this.qingLongManager = QingLongManager(this)
        // 朱雀试炼
        this.zhuQueManager = ZhuQueManager(this)
        this.baihuTrialManager = BaihuTrialManager(this)
        this.xuanwuTrialManager = XuanwuTrialManager(this)
        // 初始化金宝箱管理器
        this.goldenChestManager = GoldenChestManager(this)
        this.qixiDungeonManager = QixiDungeonManager(this)
        this.shengShanDungeonManager = ShengShanDungeonManager(this)
        this.qixiBridgeBuildManager = QixiBridgeBuildManager(this)
        // 【新增】初始化个人仓库管理器
        this.warehouseManager = com.hjh_database.warehouse.manager.WarehouseManager(this)
        this.adminWarehouseGui = com.hjh_database.warehouse.admin.AdminWarehouseGui(this)
        // 【新增】初始化医术试炼管理器
        this.medicalTrialManager = MedicalTrialManager(this)
        this.jianghuXindeManager = com.hjh_database.jianghu.JianghuXindeManager(this)
        // 南方沙漠 着火机制
        DesertSouthSkill.init(this)
        BaihuWestSkill.init(this)
        NorthWetnessSkill.init(this)
        ResentmentAffixSkill.init(this)

        // ==========================================
        // 第二阶段：初始化 GUI
        // ==========================================
        this.questGui = QuestGui(this)
        this.elementZfGui = ElementZfGui(this)
        
        this.elementCrystalManager = ElementCrystalManager(this)
        this.elementCrystalManager.initBlock()
        server.pluginManager.registerEvents(this.elementCrystalManager, this)
        server.pluginManager.registerEvents(this.elementCrystalManager.warriorMastery, this)
        server.pluginManager.registerEvents(this.elementCrystalManager.archerMastery, this)
        server.pluginManager.registerEvents(this.elementCrystalManager.warlockMastery, this)
        server.pluginManager.registerEvents(this.elementCrystalManager.medicalMastery, this)
        this.elementCrystalGui = ElementCrystalGui(this)
        server.pluginManager.registerEvents(this.elementCrystalGui, this)
        server.pluginManager.registerEvents(ElementCrystalInteractListener(this), this)
        server.pluginManager.registerEvents(com.hjh_database.jianghu.JianghuXindeListener(this), this)

        // ==========================================
        // 第三阶段：注册所有事件监听器 (Listeners)
        // ==========================================
        val pm = server.pluginManager
        val rebirthListener = RebirthListener(this)
        rebirthListener.initAltar()

        // 1. 基础系统监听
        // 必须早于其他伤害监听器注册，以便测试仪在LOWEST阶段拿到未经插件修改的事件。
        pm.registerEvents(this.damageTestManager, this)
        pm.registerEvents(PlayerListener(this), this)
        pm.registerEvents(this.passiveSubtitleManager, this)
        pm.registerEvents(rebirthListener, this)
        pm.registerEvents(CombatListener(this), this)
        pm.registerEvents(MenuListener(this), this) // 依赖 questGui，放在后面注册合理
        pm.registerEvents(ResourceListener(this), this)
        pm.registerEvents(StationListener(this), this)
        pm.registerEvents(BaihuDzStationListener(this), this)
        pm.registerEvents(BaihuEquipmentDamageMarkerListener(this), this)
        pm.registerEvents(BaihuWeaponSkillListener(this), this)
        pm.registerEvents(HuzhizhanqiSkill(this), this)
        pm.registerEvents(this.tianheyiSkill, this)
        pm.registerEvents(this.queqiaoyinSkill, this)
        pm.registerEvents(this.queshuanglingSkill, this)
        pm.registerEvents(this.lingyunsuoSkill, this)
        pm.registerEvents(com.hjh_database.teleport.TeleportListener(this), this)
        pm.registerEvents(com.hjh_database.spawner.SpawnerListener(this), this)
        pm.registerEvents(this.baihuMiasmaManager, this)
        pm.registerEvents(this.baihuTownFireManager, this)
        this.passbookListener = PassbookListener(this)
        pm.registerEvents(this.passbookListener, this)
        pm.registerEvents(com.hjh_database.listener.TestDummySignListener(this), this)
        pm.registerEvents(RaidPreventionListener(this), this)

        // 2. 技能与战斗相关监听
        pm.registerEvents(com.hjh_database.listener.SpellListener(this), this)
        pm.registerEvents(WeaponSkillListener(this), this)
        pm.registerEvents(com.hjh_database.kaiwu.KaiWuListener(this), this)
        pm.registerEvents(this.kaiWuAdminGui, this)
        pm.registerEvents(MedicalSpellListener(this), this)
        pm.registerEvents(CustomMagmaCubeListener(this), this)

        // 3. 独立系统与GUI监听
        pm.registerEvents(com.hjh_database.skill.medical.gui.MedicalEtchGui(this), this)
        pm.registerEvents(this.questGui, this)
        pm.registerEvents(com.hjh_database.quest.impl.main.shen.Shen_07TriggerListener(this), this)
        pm.registerEvents(com.hjh_database.quest.impl.main.shen.ShenJobTicketListener(this), this)
        pm.registerEvents(AlchemyListener(this), this)
        pm.registerEvents(this.elementZfGui, this)
        pm.registerEvents(this.chonghuaManager, this)

        // 饰品栏管理器监听
        pm.registerEvents(this.accessoryManager, this)
        // 青龙试炼监听
        pm.registerEvents(this.qingLongManager, this)
        // 朱雀试炼监听
        pm.registerEvents(this.zhuQueManager, this)
        pm.registerEvents(this.baihuTrialManager, this)
        pm.registerEvents(this.xuanwuTrialManager, this)
        // 金宝箱监听
        server.pluginManager.registerEvents(VaultChestListener(this), this)
        pm.registerEvents(this.qixiDungeonManager, this)
        pm.registerEvents(this.shengShanDungeonManager, this)
        pm.registerEvents(this.qixiBridgeBuildManager, this)
        // 【新增】个人仓库系统监听
        pm.registerEvents(com.hjh_database.warehouse.listener.WarehouseBlockListener(this), this)
        // 假设你的 GUI 监听器叫 WarehouseGuiListener 并且放在 listener 包下
        pm.registerEvents(com.hjh_database.warehouse.listener.WarehouseGuiListener(this), this)
        // 【新增】医术试炼监听
        pm.registerEvents(this.medicalTrialManager, this)
        pm.registerEvents(FarmingListener(this.farmingManager), this)
        pm.registerEvents(BusuanListener(this.busuanManager), this)
        pm.registerEvents(this.globalMarketManager, this)
        pm.registerEvents(TitleListener(this.titleManager), this)
        pm.registerEvents(TitleClaimBeaconListener(this), this)

        this.baihuMiasmaManager.start()
        this.baihuTownFireManager.start()
        this.farmingManager.start()


        // ==========================================
        // 第四阶段：注册所有指令 (Commands)
        // ==========================================
        val adminCmd = AdminCommand(this)
        getCommand("hjhadmin")?.apply {
            setExecutor(adminCmd)
            tabCompleter = adminCmd
        }

        getCommand("zfset")?.setExecutor(com.hjh_database.command.ZfCommand(this))

        getCommand("testmob")?.setExecutor(com.hjh_database.command.TestMobCommand(this))
        getCommand("hjhstats")?.setExecutor(StatsCommand(this))
        getCommand("hjhweapon")?.setExecutor(com.hjh_database.command.WeaponCommand(this))
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
                playerManager.loadAndCache(player) { data ->
                    qixiDungeonManager.backfillCompletionTitle(player, data.dungeonRecords)
                }
                // 【新增】同时加载玩家的仓库数据！
                warehouseManager.loadAndCache(player)
                // 【新增】同时加载玩家的元素结晶数据！
                elementCrystalManager.loadPlayer(player)
                farmingManager.loadAndCache(player)
                titleManager.loadPlayer(player)
            }
        }, 10L)

        // 【新增】每5分钟异步保存在线玩家、仓库与元素结晶数据 (20 ticks * 60 seconds * 5 minutes)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable {
            playerManager.saveAllOnline()
            warehouseManager.saveAllOnline()
            if (::elementCrystalManager.isInitialized) {
                elementCrystalManager.saveAll()
            }
            if (::farmingManager.isInitialized) {
                farmingManager.saveAllOnline()
            }
        }, 6000L, 6000L)



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
        // 必须在监听器失效前关闭只读快照菜单，杜绝重载期间取走克隆物品。
        // 归尘匣中的真实物品会在关闭前安全退回玩家背包。
        TianjiUtilityMenus.closeOpenMenusForDisable()

        // 清理镇岳沉锋等饰品的临时属性，避免热重载后残留进攻属性加成。
        accessorySkillManager.shutdown()

        if (::clientBridge.isInitialized) {
            clientBridge.shutdown()
        }

        if (::tianheyiSkill.isInitialized) {
            tianheyiSkill.shutdown()
        }
        if (::queqiaoyinSkill.isInitialized) {
            queqiaoyinSkill.shutdown()
        }
        if (::queshuanglingSkill.isInitialized) {
            queshuanglingSkill.shutdown()
        }
        if (::lingyunsuoSkill.isInitialized) {
            lingyunsuoSkill.shutdown()
        }

        if (::globalMarketManager.isInitialized) {
            globalMarketManager.shutdown()
        }

        if (::passiveSubtitleManager.isInitialized) {
            passiveSubtitleManager.clear()
        }

        if (::titleManager.isInitialized) {
            titleManager.shutdown()
        }

        if (::featherManager.isInitialized) {
            featherManager.shutdown()
        }

        if (::elementZfManager.isInitialized) {
            elementZfManager.shutdown()
        }

        if (::shenTributeManager.isInitialized) {
            shenTributeManager.shutdown()
        }

        if (::databaseManager.isInitialized) {
            databaseManager.cancelQueuedPlayerSaves()
        }

        if (::playerManager.isInitialized) {
            playerManager.saveAllOnline()
        }

        if (::baihuMiasmaManager.isInitialized) {
            baihuMiasmaManager.shutdown()
        }
        NorthWetnessSkill.shutdown()
        ResentmentAffixSkill.shutdown()

        if (::baihuTownFireManager.isInitialized) {
            baihuTownFireManager.shutdown()
        }

        if (::baihuTrialManager.isInitialized) {
            baihuTrialManager.shutdown()
        }

        if (::xuanwuTrialManager.isInitialized) {
            xuanwuTrialManager.shutdown()
        }

        if (::qixiDungeonManager.isInitialized) {
            qixiDungeonManager.shutdown()
        }

        if (::shengShanDungeonManager.isInitialized) {
            shengShanDungeonManager.shutdown()
        }

        if (::qixiBridgeBuildManager.isInitialized) {
            qixiBridgeBuildManager.shutdown()
        }

        if (::bgmManager.isInitialized) {
            bgmManager.shutdown()
        }

        if (::elementCrystalManager.isInitialized) {
            elementCrystalManager.saveAll()
        }

        if (::farmingManager.isInitialized) {
            farmingManager.shutdown()
        }

        if (::busuanManager.isInitialized) {
            busuanManager.shutdown()
        }

        // 【新增】关服时清理所有正在进行的医术试炼，防止 BossBar 残留或刷出幽灵实体
        if (::medicalTrialManager.isInitialized) {
            medicalTrialManager.cleanUpAllTrials()
        }

        // 【新增】关服时保存所有仓库数据
        if (::warehouseManager.isInitialized) {
            if (::adminWarehouseGui.isInitialized) {
                adminWarehouseGui.flushAllToMemory()
            }
            warehouseManager.saveAllOnline()
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
