package com.hjh_database;

import com.hjh_database.command.AdminCommand;
import com.hjh_database.command.ResourceReloadCommand;
import com.hjh_database.command.StatsCommand;
import com.hjh_database.data.DatabaseManager;
import com.hjh_database.data.PlayerManager;
import com.hjh_database.dz.command.DzCommand;
import com.hjh_database.dz.listener.StationListener;
import com.hjh_database.dz.manager.DzLevelManager;
import com.hjh_database.dz.manager.RecipeManager;
import com.hjh_database.listener.CombatListener;
import com.hjh_database.listener.MenuListener;
import com.hjh_database.listener.PlayerListener;
import com.hjh_database.listener.WeaponSkillListener;
import com.hjh_database.resource.ResourceListener;
import com.hjh_database.resource.ResourceManager;
import com.hjh_database.skill.element_zf.ElementZfManager;
import com.hjh_database.skill.weapon.WeaponSkillManager;
import com.hjh_database.ui.MenuManager;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class Hjh_database extends JavaPlugin {

    private DatabaseManager databaseManager;
    private PlayerManager playerManager;
    private MenuManager menuManager; // 新增
    private com.hjh_database.skill.element_zf.ElementZfManager elementZfManager;
    private ResourceManager resourceManager;
    private RecipeManager recipeManager; // 1. 声明变量
    // 在主类中添加
    private WeaponSkillManager weaponSkillManager;
    private DzLevelManager dzLevelManager; // 新增字段

    // 1. 添加成员变量
    private com.hjh_database.kaiwu.KaiWuManager kaiWuManager;


    @Override
    public void onEnable() {
        // 1. 初始化数据库连接
        // 建议：在实际生产环境中，将账号密码放入 config.yml 读取
        this.databaseManager = new DatabaseManager(this);

        // 2. 初始化玩家数据管理器
        this.playerManager = new PlayerManager(this);

        // 【新增】初始化菜单管理器
        this.menuManager = new MenuManager(this);

        // 2. 初始化 阵法管理器 (建议放在 MenuManager 之后)
        this.elementZfManager = new com.hjh_database.skill.element_zf.ElementZfManager(this);

        // 初始化资源管理器
        this.resourceManager = new ResourceManager(this);

        // 【新增】 初始化 DzLevelManager
        // 建议放在 PlayerManager 之后，DzCommand 之前
        this.dzLevelManager = new DzLevelManager(this);

        // 注册指令
        AdminCommand adminCmd = new AdminCommand(this);
        getCommand("hjhadmin").setExecutor(adminCmd);
        getCommand("hjhadmin").setTabCompleter(adminCmd); // 这一点很重要，不然没补全

        // 注册监听器
        getServer().getPluginManager().registerEvents(new ResourceListener(this), this);

        // 3. 注册 SpellListener
        getServer().getPluginManager().registerEvents(new com.hjh_database.listener.SpellListener(this), this);

        // 2. 初始化配方管理器 (一定要在 onEnable 里)
        this.recipeManager = new RecipeManager(this);

        // 初始化技能管理器
        this.weaponSkillManager = new WeaponSkillManager(this);

        // 初始化开物术管理器
        this.kaiWuManager = new com.hjh_database.kaiwu.KaiWuManager(this);
        // 注册监听器 (放在 registerEvents 区域)
        getServer().getPluginManager().registerEvents(new com.hjh_database.kaiwu.KaiWuListener(this), this);

        // 注册监听器
        getServer().getPluginManager().registerEvents(new WeaponSkillListener(this), this);


        // 3. 注册事件监听
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        // 【新增】注册菜单监听器
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        // 在 onEnable 方法中 术士专用监听
        getServer().getPluginManager().registerEvents(new com.hjh_database.listener.SpellListener(this), this);

        // 注册命令
        getCommand("hjhdz").setExecutor(new DzCommand(this));

            // 注册监听器
        getServer().getPluginManager().registerEvents(new StationListener(this), this);


        // 在 onEnable 方法里，指令注册的那部分下面添加：
        if (getCommand("testmob") != null) {
            getCommand("testmob").setExecutor(new com.hjh_database.command.TestMobCommand(this));
        }
        // 注册指令
        getCommand("zfset").setExecutor(new com.hjh_database.command.ZfCommand(this));
        // 4. 注册指令
        if (getCommand("hjhstats") != null) {
            getCommand("hjhstats").setExecutor(new StatsCommand(this));
        }
        // 在 onEnable 中注册新指令
        if (getCommand("hjhweapon") != null) {
            getCommand("hjhweapon").setExecutor(new com.hjh_database.command.WeaponCommand(this));
        }

        // 【新增】注册管理指令
        AdminCommand adminCommand = new AdminCommand(this);
        if (getCommand("hjhadmin") != null) {
            getCommand("hjhadmin").setExecutor(adminCommand);
            getCommand("hjhadmin").setTabCompleter(adminCommand); // 注册 TabCompleter
        }

        // 开物术指令
        // 注册指令 (放在 getCommand 区域)
        if (getCommand("hjhkw") != null) {
            getCommand("hjhkw").setExecutor(new com.hjh_database.kaiwu.KaiWuCommand(this));
        }


        // =========================================================
        // 【新增】注册 /hjh resourcereload 指令
        // =========================================================
        ResourceReloadCommand resCmd = new ResourceReloadCommand(this);
        if (getCommand("hjh") != null) {
            getCommand("hjh").setExecutor(resCmd);
            getCommand("hjh").setTabCompleter((TabCompleter) resCmd);
        } else {
            getLogger().warning("未在 plugin.yml 中找到 'hjh' 指令，资源重载指令无法使用！");
        }

        // 5. 【新增逻辑】处理热重载：立即加载所有在线玩家数据
        getServer().getScheduler().runTaskLater(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                playerManager.loadAndCache(player);
            }
        }, 10L); // 延迟 10 刻（约 0.5 秒），给 Spigot 和数据库连接池时间稳定

        getLogger().info("画江湖核心数据系统 (HJH) 已启动 - 数据库模式");
    }

    @Override
    public void onDisable() {
        // 关闭时保存所有在线玩家数据
        if (playerManager != null) {
            playerManager.saveAllOnline();
        }

        // 关闭数据库连接
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("画江湖核心数据系统 (HJH) 已关闭");
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    // 【新增】Getter 方法
    public MenuManager getMenuManager() {
        return menuManager;
    }

    // 4. 添加 Getter 方法 (让 SpellListener 可以调用)
    public com.hjh_database.skill.element_zf.ElementZfManager getElementZfManager() {
        return elementZfManager;
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }
    // 3. 添加 Getter 方法 (解决报错的关键)

    public RecipeManager getRecipeManager() {
        return recipeManager;
    }

    public WeaponSkillManager getWeaponSkillManager() {
        return weaponSkillManager;
    }

    // 【新增】 Getter 方法
    public DzLevelManager getDzLevelManager() {
        return dzLevelManager;
    }

    // 3. 添加 Getter 方法 (供其他类调用)
    public com.hjh_database.kaiwu.KaiWuManager getKaiWuManager() {
        return kaiWuManager;
    }
}