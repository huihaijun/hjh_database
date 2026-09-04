package com.hjh_database.skill.element_zf

import java.util.UUID

/** 每次元素施法独有的身份；由延迟效果捕获，不能按玩家或元素共用。 */
class FormationCast {
    val id: UUID = UUID.randomUUID()
    val hitTargets: MutableSet<UUID> = HashSet()
}
