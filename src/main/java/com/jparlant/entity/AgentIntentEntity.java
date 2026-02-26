package com.jparlant.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;


@Data
@Table("agent_intent")
public class AgentIntentEntity {
    @Id
    private Long id;
    private Long agentId;
    private String name;
    private String description;
    private Boolean enabled;
    private String flowType;       // 对应 FlowType 枚举字符串
    private String metadataJson;   // JSON 存储 Map
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
