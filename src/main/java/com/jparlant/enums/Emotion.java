package com.jparlant.enums;

/**
 * 用户情绪枚举
 */
public enum Emotion {
    POSITIVE,   // 积极
    NEUTRAL,    // 中性
    NEGATIVE,   // 消极/不满
    URGENT;     // 紧急

    public static Emotion of(String value) {
        try {
            return Emotion.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return NEUTRAL; // 默认中性
        }
    }
}
