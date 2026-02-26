package com.jparlant.repository;

import com.jparlant.entity.AgentEntity;
import com.jparlant.enums.AgentStatus;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface AgentRepository extends ReactiveCrudRepository<AgentEntity, Long> {

    /**
     * 根据状态查询Agent
     * @param status
     * @return
     */
    Flux<AgentEntity> findAgentByStatus(Integer status);
}