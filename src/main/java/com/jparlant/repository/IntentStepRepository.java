package com.jparlant.repository;

import com.jparlant.entity.IntentStepEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.List;

@Repository
public interface IntentStepRepository extends R2dbcRepository<IntentStepEntity, Long> {

    /**
     * 根据意图 ID 列表批量查询，并按优先级升序排列
     */
    Flux<IntentStepEntity> findByIntentIdInOrderByPriorityAsc(List<Long> intentId);
}
