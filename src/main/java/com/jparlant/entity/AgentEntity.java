package com.jparlant.entity;

import com.jparlant.enums.AgentStatus;
import com.jparlant.model.Agent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("agents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEntity {
    @Id
    private Long id;
    private String name;
    private String instructions;
    private String description;
    private String keywords;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 转换方法：Entity -> Domain
    public Agent toDomain() {
        return new Agent(
                this.id,
                this.name,
                this.instructions,
                this.description,
                this.keywords,
                AgentStatus.fromCode(this.status)
        );
    }
}
