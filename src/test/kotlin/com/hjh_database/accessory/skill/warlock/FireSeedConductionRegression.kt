package com.hjh_database.accessory.skill.warlock

import kotlin.random.Random
import kotlin.test.Test

/** 可直接通过 Gradle test / verifyXunlilingshu 执行，不需要启动服务器。 */
class FireSeedConductionRegressionTest {
    @Test
    fun conductionAndFollowingGeometry() {
        var scenarios = 0
        fun plan(sources: List<String>, seeds: Set<String>, hits: Set<String>,
                 edges: Map<String, List<String>>, random: Random = Random(7)) =
            FireSeedConduction.plan(sources, seeds, hits, { edges[it].orEmpty() }, random)

        val abc = setOf("A", "B", "C")
        val internal = mapOf("A" to listOf("B", "C"), "B" to listOf("A", "C"), "C" to listOf("A", "B"))
        repeat(100) { seed ->
            val result = plan(abc.toList(), abc, abc, internal, Random(seed))
            check(result.size == 1 && result.single().targets.size == 1)
            check(result.single().source != result.single().targets.single())
        }
        scenarios++

        val external = internal.mapValues { (_, neighbors) -> neighbors + "D" }
        val prioritized = plan(abc.toList(), abc + "D", abc, external)
        check(prioritized.size == 1 && prioritized.single().targets == listOf("D"))
        scenarios++

        val allExternal = plan(listOf("A"), setOf("A", "D", "E"), setOf("A"),
            mapOf("A" to listOf("D", "E")))
        check(allExternal.single().targets.toSet() == setOf("D", "E"))
        scenarios++

        val consumed = (abc + setOf("D", "E")).toMutableSet()
        val disjoint = plan(listOf("A", "B"), consumed, setOf("A", "B"),
            mapOf("A" to listOf("D", "E"), "B" to listOf("D", "E")))
        val participants = disjoint.flatMap { listOf(it.source) + it.targets }
        check(participants.size == participants.toSet().size)
        disjoint.forEach { consumed.remove(it.source); consumed.removeAll(it.targets.toSet()) }
        check(plan(listOf("A", "B"), consumed, setOf("A", "B"),
            mapOf("A" to listOf("D", "E"), "B" to listOf("D", "E"))).isEmpty())
        scenarios++

        // 同一次持续阵法之前已命中的 D 不能冒充“阵法外目标”。
        check(plan(listOf("A"), setOf("A", "D"), setOf("A", "D"), mapOf("A" to listOf("D"))).isEmpty())
        scenarios++

        // B 的火种仅属于另一玩家时，对 A 的施法者不是合法目标；输入状态不被规划器修改。
        val ownerOneSeeds = setOf("A")
        check(plan(listOf("A"), ownerOneSeeds, setOf("A"), mapOf("A" to listOf("B"))).isEmpty())
        check(ownerOneSeeds == setOf("A"))
        scenarios++

        check(plan(listOf("A"), setOf("A"), setOf("A"), mapOf("A" to listOf("A"))).isEmpty())
        check(plan(abc.toList(), emptySet(), abc, internal).isEmpty())
        scenarios++

        check(FollowingWindGeometry.contains(4.0, 5.0, 10.0, 0.0))
        check(FollowingWindGeometry.contains(-4.0, -5.0, 0.0, 0.0))
        check(!FollowingWindGeometry.contains(4.01, 0.0, 5.0, 0.0))
        check(!FollowingWindGeometry.contains(0.0, 5.01, 5.0, 0.0))
        check(!FollowingWindGeometry.contains(0.0, 0.0, 10.01, 0.0))
        check(!FollowingWindGeometry.contains(0.0, 0.0, -0.01, 0.0))
        check(FollowingWindGeometry.contains(-10.0, 0.0, 4.0, 90.0))
        check(!FollowingWindGeometry.contains(0.0, 0.0, 10.0, 90.0))
        // 玩家平移后，必须以新位置重新计算偏移。
        check(FollowingWindGeometry.contains(102.0 - 100.0, 0.0, 108.0 - 100.0, 0.0))
        scenarios++

        println("Xunlilingshu regression: " + scenarios + " scenarios passed (including 100 randomized AOE trials).")
    }
}
