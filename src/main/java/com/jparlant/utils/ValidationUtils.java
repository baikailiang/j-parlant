package com.jparlant.utils;

import java.util.Map;

public class ValidationUtils {


    /**
     * 根据路径从 Map 中获取值。
     * path 格式: $.user.age -> 从 entities 获取 user(Map)，再获取 age
     */
    public static Object getValueByPath(Map<String, Object> entities, String path) {
        if (path == null || !path.startsWith("$.")) return null;
        String[] parts = path.substring(2).split("\\.");
        Object current = entities;

        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                // 处理数组路径标识 [*]，这里简化处理，只取第一个 Key
                String key = part.replace("[*]", "");
                current = map.get(key);
            } else {
                return null;
            }
        }
        return current;
    }
}
