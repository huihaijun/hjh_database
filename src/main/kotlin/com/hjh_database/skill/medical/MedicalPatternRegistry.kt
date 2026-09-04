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
        // === 注册杏花雨图案 ===
        patterns["xinghuayu"] = listOf(
            Pattern(DyeColor.PINK, PatternType.GRADIENT_UP),   // 向上渐变的粉色（象征漫天花雨）
            Pattern(DyeColor.WHITE, PatternType.FLOWER),       // 白色的花朵（杏花的纯洁）
            Pattern(DyeColor.MAGENTA, PatternType.CIRCLE),     // 洋红色的圆（花雨的范围域）
            Pattern(DyeColor.WHITE, PatternType.BORDER)        // 白色边框
        )
        // === 注册念气劲图案 ===
        patterns["nianqijin"] = listOf(
            Pattern(DyeColor.CYAN, PatternType.STRIPE_CENTER), // 中间的青色竖直条纹（象征贯穿的气波）
            Pattern(DyeColor.WHITE, PatternType.CROSS),        // 白色的十字（象征准星和爆发）
            Pattern(DyeColor.LIGHT_BLUE, PatternType.BORDER)   // 浅蓝色边框收束能量
        )
        // === 注册魂灵游图案 ===
        patterns["hunlingyou"] = listOf(
            Pattern(DyeColor.CYAN, PatternType.GRADIENT),       // 青色渐变（象征灵魂的底色）
            Pattern(DyeColor.BLACK, PatternType.HALF_VERTICAL), // 黑色半垂直（象征肉体与灵魂的剥离）
            Pattern(DyeColor.LIGHT_BLUE, PatternType.CIRCLE),   // 浅蓝色圆（核心魂体）
            Pattern(DyeColor.BLACK, PatternType.BORDER)         // 黑色边框
        )
        // === 注册天佑图案 ===
        patterns["tianyou"] = listOf(
            Pattern(DyeColor.YELLOW, PatternType.GRADIENT_UP),  // 向上渐变的黄色（象征圣光冲天）
            Pattern(DyeColor.ORANGE, PatternType.FLOWER),         // 金色花纹（象征祥瑞）
            Pattern(DyeColor.WHITE, PatternType.RHOMBUS),       // 白色的菱形（象征坚固护盾）
            Pattern(DyeColor.ORANGE, PatternType.BORDER)          // 金色边框
        )
        // === 注册降天光图案 ===
        patterns["jiangtianguang"] = listOf(
            Pattern(DyeColor.WHITE, PatternType.STRIPE_CENTER), // 白色的中央竖条纹（象征天光降临）
            Pattern(DyeColor.YELLOW, PatternType.GRADIENT_UP),  // 黄色向上渐变（象征圣洁光辉）
            Pattern(DyeColor.PINK, PatternType.FLOWER),         // 粉色花朵（象征治疗与生命）
            Pattern(DyeColor.ORANGE, PatternType.BORDER)          // 金色边框
        )
        // === 注册万象苏图案 ===
        patterns["wanxiangsu"] = listOf(
            Pattern(DyeColor.LIME, PatternType.GRADIENT),       // 亮绿色渐变（象征生机勃发）
            Pattern(DyeColor.GREEN, PatternType.FLOWER),        // 绿色花朵（万物复苏）
            Pattern(DyeColor.WHITE, PatternType.GLOBE),         // 白色地球（象征万象森罗）
            Pattern(DyeColor.YELLOW, PatternType.BORDER)        // 黄色边框（五阶的神圣感）
        )
        // === 注册唤圣羽图案 ===
        patterns["huanshengyu"] = listOf(
            Pattern(DyeColor.WHITE, PatternType.GRADIENT),       // 白色渐变（圣洁的底色）
            Pattern(DyeColor.LIGHT_GRAY, PatternType.FLOWER),    // 浅灰色花纹（勾勒羽毛的轮廓）
            Pattern(DyeColor.WHITE, PatternType.CREEPER),        // 白色苦力怕头（叠加形成羽翼展开的质感）
            Pattern(DyeColor.ORANGE, PatternType.BORDER)           // 金色边框（五阶医术的尊贵）
        )
        // === 注册八阵诀图案 ===
        patterns["bazhenjue"] = listOf(
            Pattern(DyeColor.CYAN, PatternType.RHOMBUS),        // 青色菱形（阵法基础）
            Pattern(DyeColor.BLACK, PatternType.CROSS),         // 黑色十字（分割八卦）
            Pattern(DyeColor.WHITE, PatternType.CIRCLE), // 白色中心圆（太极阴阳眼）
            Pattern(DyeColor.CYAN, PatternType.BORDER)          // 青色边框
        )
        // === 注册凝气爆图案 ===
        patterns["ningqibao"] = listOf(
            Pattern(DyeColor.PURPLE, PatternType.GRADIENT),       // 紫色渐变（压缩的灵力）
            Pattern(DyeColor.BLACK, PatternType.SKULL),           // 黑色骷髅（代表毁灭性的威力）
            Pattern(DyeColor.MAGENTA, PatternType.CIRCLE), // 品红色中心圆（爆炸的核心）
            Pattern(DyeColor.RED, PatternType.BORDER)             // 红色边框（警示危险）
        )
        // === 注册瘴气散图案 ===
        patterns["zhangqisan"] = listOf(
            Pattern(DyeColor.LIME, PatternType.BASE),            // 黄绿色底色（毒气）
            Pattern(DyeColor.GREEN, PatternType.GRADIENT_UP),    // 绿色上渐变（蔓延的瘴气）
            Pattern(DyeColor.BLACK, PatternType.CREEPER),        // 黑色苦力怕（代表危险与怪物干扰）
            Pattern(DyeColor.BROWN, PatternType.BORDER)          // 棕色边框（药粉感）
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