package com.jparlant.service.flow;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jparlant.entity.AgentIntentEntity;
import com.jparlant.entity.IntentStepEntity;
import com.jparlant.entity.IntentStepTransitionEntity;
import com.jparlant.model.AgentFlow;
import com.jparlant.repository.AgentIntentRepository;
import com.jparlant.repository.IntentStepRepository;
import com.jparlant.repository.IntentStepTransitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent流程数据访问接口
 */
@RequiredArgsConstructor
@Slf4j
public class AgentFlowManager {


    private final AgentIntentRepository agentIntentRepository;
    private final IntentStepRepository intentStepRepository;
    private final AgentFlowWrapper agentFlowWrapper;
    private final IntentStepTransitionRepository intentStepTransitionRepository;

    /**
     * 本地缓存：Key 为 agentId, Value 为该 Agent 下所有的 AgentFlow 列表
     */
    private final Cache<Long, List<AgentFlow>> flowCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.DAYS)
            .maximumSize(500)
            .build();



    /**
     * 获取指定 Agent 的所有业务流程定义
     */
    public Flux<AgentFlow> getAgentFlowsByAgentId(Long agentId) {
        // 1. 尝试从缓存获取
        List<AgentFlow> cachedFlows = flowCache.getIfPresent(agentId);
        if (cachedFlows != null) {
            log.debug("命中 AgentFlow 缓存, agentId={}", agentId);
            return Flux.fromIterable(cachedFlows);
        }

        // 2. 缓存未命中，从数据库加载
        return loadFlowsFromDb(agentId)
                .collectList()
                .doOnNext(flows -> {
                    if (!flows.isEmpty()) {
                        flowCache.put(agentId, flows);
                        log.info("已加载并缓存 AgentFlow, agentId={}, 该Agent下的意图数量={}", agentId, flows.size());
                    }
                })
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * 从数据库加载数据
     */
    private Flux<AgentFlow> loadFlowsFromDb(Long agentId) {
        return agentIntentRepository.findByAgentIdAndEnabledTrue(agentId)
                .collectList()
                .flatMapMany(intents -> {
                    if (intents.isEmpty()) return Flux.empty();

                    List<Long> intentIds = intents.stream()
                            .map(AgentIntentEntity::getId)
                            .toList();

                    // 1. 批量查询步骤
                    Mono<List<IntentStepEntity>> stepsMono = intentStepRepository.findByIntentIdInOrderByPriorityAsc(intentIds)
                            .collectList();

                    // 2. 批量查询步骤流转
                    Mono<List<IntentStepTransitionEntity>> transitionsMono = intentStepTransitionRepository.findByIntentIdInOrderByPriorityAsc(intentIds)
                            .collectList();

                    // 3. 同步组合
                    return Mono.zip(stepsMono, transitionsMono)
                            .flatMapMany(tuple -> {
                                List<IntentStepEntity> allSteps = tuple.getT1();
                                List<IntentStepTransitionEntity> allTransitions = tuple.getT2();

                                Map<Long, List<IntentStepEntity>> stepsGroupByIntent = allSteps.stream()
                                        .collect(Collectors.groupingBy(IntentStepEntity::getIntentId));

                                Map<Long, List<IntentStepTransitionEntity>> transitionsGroupByIntent = allTransitions.stream()
                                        .collect(Collectors.groupingBy(IntentStepTransitionEntity::getIntentId));

                                return Flux.fromIterable(intents)
                                        .map(intent -> agentFlowWrapper.toDomain(
                                                intent,
                                                stepsGroupByIntent.getOrDefault(intent.getId(), List.of()),
                                                transitionsGroupByIntent.getOrDefault(intent.getId(), List.of())
                                        ));
                            });
                });
    }



    /**
     * 1. 精准刷新：手动清除指定 Agent 的所有流程缓存
     * 当后台修改了该 Agent 下的某个意图、步骤或流转关系时调用
     */
    public void refreshAgentFlows(Long agentId) {
        if (agentId == null) return;
        log.info("手动刷新 AgentFlow 缓存, agentId={}", agentId);
        flowCache.invalidate(agentId);
    }

    /**
     * 2. 全局刷新：清除所有 Agent 的流程定义缓存
     * 用于系统重大更新、批量配置变更或内存紧急重置
     */
    public void refreshAllFlows() {
        log.warn("触发全局 AgentFlow 缓存刷新，所有流程定义将重新从数据库加载");
        flowCache.invalidateAll();
    }


    /**
     * 根据agentId和intentId获取流程定义
     */
    public Mono<AgentFlow> getFlowByAgentIdAndIntentId(Long agentId, Long intentId) {
        return this.getAgentFlowsByAgentId(agentId)
                .filter(flow -> flow.intentId().equals(intentId))
                .next();
    }


}