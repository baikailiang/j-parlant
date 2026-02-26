package com.jparlant.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 合规规则领域模型
 */
public record ComplianceRule(
        Long id,
        Long agentId,                   // 所属Agent
        String name,
        String description,
        ComplianceScope scope,                   // 作用域: INPUT, RESPONSE, ALL (新增)
        List<String> keywords,          // 关键词列表
        Map<String, Object> parameters, // 规则参数 (包含正则等)
        String condition,               // 触发条件 (SpEL表达式)
        String blockedResponse,         // 被阻止时的响应话术
        List<String> categories,        // 规则分类标签
        boolean enabled,                // 是否启用
        Integer priority,               // 优先级
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String guideline
) {

    // 作用域定义
    public enum ComplianceScope {
        INPUT,
        RESPONSE,
        ALL
    }
}