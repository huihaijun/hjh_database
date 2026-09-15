package com.hjh_database.dungeon.zhenyao

/** 只有全部方块恢复、世界保存、恢复日志提交均成功，才释放内存快照。失败可安全重试。 */
internal fun <K, V> restoreTowerSnapshot(
    snapshot: MutableMap<K, V>,
    restoreAndVerify: (K, V) -> Boolean,
    saveWorld: () -> Unit,
    commitCompletion: () -> Unit
): Boolean {
    if (snapshot.isEmpty()) return true
    var complete = true
    snapshot.forEach { (key, value) -> if (!restoreAndVerify(key, value)) complete = false }
    if (!complete) return false
    saveWorld()
    commitCompletion()
    snapshot.clear()
    return true
}
