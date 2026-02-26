package com.hjh_database.skill.medical

import org.bukkit.DyeColor
import org.bukkit.block.banner.Pattern
import org.bukkit.block.banner.PatternType
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap

/**
 * 医术旗帜图案注册表
 * 用于静态存储不同医术对应的旗帜花纹
 */
object MedicalPatternRegistry {

    private val patterns: MutableMap<String, List<Pattern>> = HashMap()

    init {
        // === 注册愈合花的图案 ===
        // 在旗帜中心画一个红色的花朵图标
        // 注意：底色由旗帜本身决定，我们只叠加图案
        patterns["yuhehua"] = listOf(
            Pattern(DyeColor.RED, PatternType.FLOWER), // 红花
            Pattern(DyeColor.WHITE, PatternType.CIRCLE) // 中间加个白点点缀(可选)
        )
        // === 注册退敌图案 (黑色菱形，寓意坚固、排斥) ===
        patterns["tuidi"] = listOf(
            Pattern(DyeColor.BLACK, PatternType.RHOMBUS), // 中间黑色菱形
            Pattern(DyeColor.WHITE, PatternType.FLOWER) // 叠加一个白色花纹做底纹(可选)
        )
        // === 注册沐春雨图案 (蓝绿色渐变与雨丝) ===
        patterns["muchunyu"] = listOf(
            Pattern(DyeColor.LIGHT_BLUE, PatternType.GRADIENT),   // 浅蓝渐变作为背景基调
            Pattern(DyeColor.CYAN, PatternType.STRIPE_DOWNLEFT)   // 青色的斜向条纹，模拟春雨
        )
        // === 注册灵草诀图案 ===
        patterns["lingcaojue"] = listOf(
            Pattern(DyeColor.LIME, PatternType.STRIPE_BOTTOM),  // 底部亮绿色
            Pattern(DyeColor.GREEN, PatternType.CROSS),         // 绿色的交叉十字模拟草丛
            Pattern(DyeColor.WHITE, PatternType.BORDER)         // 白色边框
        )
        // === 注册护身咒图案 ===
        patterns["hushenzhou"] = listOf(
            Pattern(DyeColor.YELLOW, PatternType.RHOMBUS),      // 黄色菱形底（象征坚固护盾）
            Pattern(DyeColor.ORANGE, PatternType.CIRCLE),         // 金色的圆（象征护体的光罩）
            Pattern(DyeColor.WHITE, PatternType.BORDER)         // 白色边框加固
        )
        // === 注册毒素针图案 ===
        patterns["dusuzhen"] = listOf(
            Pattern(DyeColor.LIME, PatternType.SMALL_STRIPES),   // 亮绿色细条纹（代表密集的毒针）
            Pattern(DyeColor.GREEN, PatternType.GRADIENT_UP),   // 绿色底向上渐变（象征毒气弥漫）
            Pattern(DyeColor.BLACK, PatternType.BORDER)         // 黑色边框
        )
        // === 注册冥想图案 ===
        patterns["mingxiang"] = listOf(
            Pattern(DyeColor.PURPLE, PatternType.GRADIENT),     // 紫色渐变底（象征神秘与精神）
            Pattern(DyeColor.MAGENTA, PatternType.CIRCLE),      // 品红色的圆圈（象征凝聚与平静）
            Pattern(DyeColor.BLACK, PatternType.BORDER)         // 黑色边框
        )
        // === 注册冰清域图案 ===
        patterns["bingqingyu"] = listOf(
            Pattern(DyeColor.LIGHT_BLUE, PatternType.GRADIENT), // 浅蓝渐变作为底色
            Pattern(DyeColor.WHITE, PatternType.FLOWER),        // 白色花纹（模拟冰晶的形状）
            Pattern(DyeColor.CYAN, PatternType.BORDER)          // 青色边框
        )
        // === 注册回春域图案 ===
        patterns["huichunyu"] = listOf(
            Pattern(DyeColor.LIME, PatternType.GRADIENT_UP),   // 向上渐变的亮绿色（象征勃勃生机）
            Pattern(DyeColor.GREEN, PatternType.FLOWER),       // 绿色的花朵（治愈的标志）
            Pattern(DyeColor.YELLOW, PatternType.CIRCLE),      // 黄色的圆环（象征触发的黄心护盾）
            Pattern(DyeColor.WHITE, PatternType.BORDER)        // 白色边框
        )


        // 你可以在这里继续添加其他医术的图案
        // patterns.put("huichun", ...);
    }

    /**
     * 获取指定医术的图案列表
     */
    fun getPatterns(skillId: String?): List<Pattern> {
        return patterns.getOrDefault(skillId, ArrayList())
    }
}