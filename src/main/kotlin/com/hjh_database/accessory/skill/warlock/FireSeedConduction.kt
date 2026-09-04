package com.hjh_database.accessory.skill.warlock

import kotlin.random.Random

/** 无 Bukkit 依赖的同批命中规划：先外部目标，完全没有外部目标时只随机选一条内部边。 */
internal object FireSeedConduction {
    data class Transfer<T>(val source: T, val targets: List<T>)

    fun <T> plan(
        sources: List<T>,
        seeded: Set<T>,
        castHits: Set<T>,
        neighbors: (T) -> List<T>,
        random: Random = Random.Default
    ): List<Transfer<T>> {
        val eligibleSources = sources.distinct().filter { it in seeded }
        val external = eligibleSources.associateWith { source ->
            neighbors(source).distinct().filter { it != source && it in seeded && it !in castHits }
        }
        if (external.values.any { it.isNotEmpty() }) {
            val available = seeded.toMutableSet()
            return buildList {
                for (source in eligibleSources.shuffled(random)) {
                    if (source !in available) continue
                    val targets = external.getValue(source).filter { it in available }
                    if (targets.isEmpty()) continue
                    available.remove(source)
                    available.removeAll(targets.toSet())
                    add(Transfer(source, targets))
                }
            }
        }

        val batchSources = eligibleSources.toSet()
        val pairs = eligibleSources.flatMap { source ->
            neighbors(source).distinct()
                .filter { it != source && it in seeded && it in batchSources }
                .map { target -> Transfer(source, listOf(target)) }
        }
        return pairs.randomOrNull(random)?.let(::listOf) ?: emptyList()
    }
}
