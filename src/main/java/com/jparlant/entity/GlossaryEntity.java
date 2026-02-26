package com.jparlant.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 词汇表/术语定义模型
 * 用于精确定义领域特定术语，防止AI产生幻觉
 */
@Table("glossary")
public record GlossaryEntity(
        @Id Long id,
        String name,
        String definition,
        String category,
        String synonyms,      // 对应数据库 JSON，Java 中用 String 接收
        @Column("related_names")
        String relatedNames,  // 对应数据库 JSON，Java 中用 String 接收
        String examples,      // 对应数据库 JSON，Java 中用 String 接收
        @Column("agent_id")
        Long agentId,
        Integer priority,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {}