package com.hjh_database.alchemy

import com.google.common.reflect.ClassPath
import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.effect.AlchemyEffect
import com.hjh_database.alchemy.manager.AlchemyManager

object AlchemyAutoRegister {

    // 指定你的丹药实现类所在的包名
    private const val EFFECT_PACKAGE = "com.hjh_database.alchemy.effect.impl"

    fun registerAll(plugin: Hjh_database, manager: AlchemyManager) {
        plugin.logger.info("正在扫描并注册丹药效果...")
        var count = 0

        try {
            // 获取插件的类加载器
            val loader = plugin.javaClass.classLoader
            // 使用 Guava 的 ClassPath 工具扫描
            val classPath = ClassPath.from(loader)

            // 遍历指定包下的所有类
            for (classInfo in classPath.getTopLevelClasses(EFFECT_PACKAGE)) {
                try {
                    val clazz = classInfo.load()

                    // 1. 检查是否实现了 AlchemyEffect 接口
                    if (AlchemyEffect::class.java.isAssignableFrom(clazz) && !clazz.isInterface) {

                        // 2. 实例化类 (前提：类必须有无参构造函数)
                        val instance = clazz.getDeclaredConstructor().newInstance() as AlchemyEffect

                        // 3. 注册
                        manager.registerEffect(instance)
                        count++
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("无法加载丹药类: ${classInfo.name}，原因: ${e.message}")
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("自动扫描丹药包失败: ${e.message}")
            e.printStackTrace()
        }

        plugin.logger.info("共自动注册了 $count 种丹药效果！")
    }
}