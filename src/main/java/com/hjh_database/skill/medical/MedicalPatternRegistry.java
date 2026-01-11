package com.hjh_database.skill.medical;

import org.bukkit.DyeColor;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 医术旗帜图案注册表
 * 用于静态存储不同医术对应的旗帜花纹
 */
public class MedicalPatternRegistry {

    private static final Map<String, List<Pattern>> patterns = new HashMap<>();

    static {
        // === 注册愈合花的图案 ===
        // 在旗帜中心画一个红色的花朵图标
        // 注意：底色由旗帜本身决定，我们只叠加图案
        patterns.put("yuhehua", Arrays.asList(
                new Pattern(DyeColor.RED, PatternType.FLOWER), // 红花
                new Pattern(DyeColor.WHITE, PatternType.CIRCLE_MIDDLE) // 中间加个白点点缀(可选)
        ));
        // === 注册退敌图案 (黑色菱形，寓意坚固、排斥) ===
        patterns.put("tuidi", Arrays.asList(
                new Pattern(DyeColor.BLACK, PatternType.RHOMBUS_MIDDLE), // 中间黑色菱形
                new Pattern(DyeColor.WHITE, PatternType.FLOWER) // 叠加一个白色花纹做底纹(可选)
        ));

        // 你可以在这里继续添加其他医术的图案
        // patterns.put("huichun", ...);
    }

    /**
     * 获取指定医术的图案列表
     */
    public static List<Pattern> getPatterns(String skillId) {
        return patterns.getOrDefault(skillId, new ArrayList<>());
    }
}