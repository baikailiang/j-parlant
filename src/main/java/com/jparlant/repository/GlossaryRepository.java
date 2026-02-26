package com.jparlant.repository;

import com.jparlant.entity.GlossaryEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * 词汇表数据访问层
 */
@Repository
public interface GlossaryRepository extends ReactiveCrudRepository<GlossaryEntity, Long> {
    
    /**
     * 根据Agent ID查找词汇表
     */
    Flux<GlossaryEntity> findByAgentId(Long agentId);

}