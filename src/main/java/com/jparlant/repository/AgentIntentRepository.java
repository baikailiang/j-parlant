package com.jparlant.repository;

import com.jparlant.entity.AgentIntentEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface AgentIntentRepository extends R2dbcRepository<AgentIntentEntity, Long> {


    /**
     * 获取指定 Agent 下所有状态为“已启用”的业务意图
     */
    Flux<AgentIntentEntity> findByAgentIdAndEnabledTrue(Long agentId);

}
