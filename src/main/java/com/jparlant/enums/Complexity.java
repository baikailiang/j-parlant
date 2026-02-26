package com.jparlant.enums;

/**
 * 任务复杂度枚举
 */
public enum Complexity {
    SIMPLE,     // 简单
    MEDIUM,     // 中等
    COMPLEX;    // 复杂

    public static Complexity of(String value) {
        try {
            return Complexity.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return MEDIUM; // 默认中等
        }
    }
}
