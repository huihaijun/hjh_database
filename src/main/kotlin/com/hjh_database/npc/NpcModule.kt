package com.hjh_database.npc

import com.hjh_database.Hjh_database
import com.hjh_database.npc.listener.NpcInteractListener
import com.hjh_database.npc.manager.NpcManager

/**
 * NPC 模块入口
 * 负责初始化管理器和监听器
 */
class NpcModule(private val plugin: Hjh_database) {

    lateinit var manager: NpcManager
    private lateinit var listener: NpcInteractListener

    fun enable() {
        // 1. 初始化管理器
        manager = NpcManager(plugin)

        // 2. 初始化监听器
        listener = NpcInteractListener(plugin)

        // 3. 注册事件
        plugin.server.pluginManager.registerEvents(listener, plugin)

        // 4. (可选) 注册指令，如果以后有 /npc create 这种指令的话

        plugin.logger.info("NPC 模块已加载")
    }

    fun disable() {
        // 保存数据
        if (::manager.isInitialized) {
            manager.saveData()
        }
        // 清理缓存等
    }

    // 提供给 GUI 获取 Listener 实例的方法 (解决上面的强转问题)
    fun getInteractListener(): NpcInteractListener {
        return listener
    }
}