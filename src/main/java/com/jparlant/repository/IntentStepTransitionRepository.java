package com.jparlant.repository;

import com.jparlant.entity.IntentStepTransitionEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.List;

@Repository
public interface IntentStepTransitionRepository extends R2dbcRepository<IntentStepTransitionEntity, Long> {

    /**
     * 批量查询指定意图ID列表的步骤流转
     *
     * @param intentIds 意图ID列表
     * @return 步骤流转列表
     */
    Flux<IntentStepTransitionEntity> findByIntentIdInOrderByPriorityAsc(List<Long> intentIds);
}
