package com.hjh_database.util

object DzUtil {
    fun getJobName(jobId: Int): String {
        when (jobId) {
            0 -> return "战士"
            1 -> return "弓箭手"
            2 -> return "术士"
            3 -> return "医师"
            -1 -> return "全职业"
            else -> return "未知职业(" + jobId + ")"
        }
    }
}