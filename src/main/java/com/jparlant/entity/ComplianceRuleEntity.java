package com.jparlant.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 合规检查规则实体类
 * 对应数据库表: compliance_rules
 */
@Data
@Table(value = "compliance_rules")
public class ComplianceRuleEntity {

    @Id
    private Long id;

    @Column(value = "agent_id")
    private Long agentId;

    private String name;

    private String description;

    /**
     * 作用域: INPUT(检查用户输入), RESPONSE(检查AI回复), ALL(全量检查)
     */
    private String scope;

    /**
     * 关键词列表 (JSON Array)
     */
    private String keywords;

    /**
     * 规则参数 (JSON Object)
     */
    private String parameters;

    /**
     * SpEL触发条件表达式
     */
    @Column(value = "condition_expr")
    private String conditionExpr;

    /**
     * 拦截后返回给用户的标准话术
     */
    @Column(value = "blocked_response")
    private String blockedResponse;

    /**
     * 自定义分类标签 (JSON Array)
     */
    private String categories;

    /**
     * 状态: 0禁用, 1启用
     */
    private boolean enabled;

    /**
     * 优先级 (值越大越优先匹配)
     */
    private Integer priority;

    @Column(value = "created_at")
    private LocalDateTime createdAt;

    @Column(value = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 规则prompt
     */
    @Column(value = "guideline_prompt")
    private String guidelinePrompt;
}