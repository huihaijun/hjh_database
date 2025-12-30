package com.hjh_database.util;

public class DzUtil {
    public static String getJobName(int jobId) {
        switch (jobId) {
            case 0: return "战士";
            case 1: return "弓箭手";
            case 2: return "术士";
            case 3: return "医师";
            case -1: return "全职业";
            default: return "未知职业(" + jobId + ")";
        }
    }
}