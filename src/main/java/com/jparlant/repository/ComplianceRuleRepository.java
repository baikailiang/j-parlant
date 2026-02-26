package com.jparlant.repository;

import com.jparlant.entity.ComplianceRuleEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ComplianceRuleRepository extends R2dbcRepository<ComplianceRuleEntity, Long> {

    /**
     * 根据 AgentId 查询所有已启用的规则，并按优先级升序排列
     */
    Flux<ComplianceRuleEntity> findByAgentIdAndEnabledTrueOrderByPriorityAsc(Long agentId);

}
