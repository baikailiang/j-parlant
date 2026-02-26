package com.jparlant.service.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jparlant.entity.AgentEntity;
import com.jparlant.enums.AgentStatus;
import com.jparlant.model.Agent;
import com.jparlant.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class AgentDefinitionManager {

    private final AgentRepository agentRepository;



    private static final String ALL_AGENTS_KEY = "ACTIVE_AGENTS_LIST";


    /**
     * 缓存所有活跃 Agent 的列表
     * 设置 2 天写入过期，最大容量 1 个（即那个 List）
     */
    private final Cache<String, List<Agent>> allAgentsCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofDays(2))
            .maximumSize(1)
            .build();


    /**
     * 缓存单个 Agent 详情
     * 设置 2 天过期，最大缓存 500 个 Agent
     */
    private final Cache<Long, Agent> agentDetailCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofDays(2))
            .maximumSize(500)
            .build();




    /**
     * 获取所有活跃 Agent - 优先从缓存读取
     */
    public Flux<Agent> getAllActiveAgents() {
        List<Agent> cachedList = allAgentsCache.getIfPresent(ALL_AGENTS_KEY);
        if (cachedList != null) {
            return Flux.fromIterable(cachedList);
        }

        // 缓存未命中，查库并回填
        return agentRepository.findAgentByStatus(AgentStatus.ACTIVE.getCode())
                .map(AgentEntity::toDomain)
                .collectList()
                .doOnNext(list -> {
                    log.info("从数据库同步 Agent 列表到缓存, 数量: {}", list.size());
                    allAgentsCache.put(ALL_AGENTS_KEY, list);
                })
                .flatMapMany(Flux::fromIterable);
    }


    /**
     * 根据 ID 获取 Agent - 优先从缓存读取
     */
    public Mono<Agent> getAgentById(Long id) {
        Agent cachedAgent = agentDetailCache.getIfPresent(id);
        if (cachedAgent != null) {
            return Mono.just(cachedAgent);
        }
        // 缓存未命中，查库并回填
        return agentRepository.findById(id)
                .map(AgentEntity::toDomain)
                .doOnNext(agent -> {
                    log.info("缓存单个 Agent 详情: {}", agent.name());
                    agentDetailCache.put(id, agent);
                });
    }


    /**
     * 彻底刷新所有 Agent 相关缓存
     */
    public void refreshCache() {
        allAgentsCache.invalidateAll();
        agentDetailCache.invalidateAll();
        log.info("Agent Definition Caches (List & Details) have been cleared.");
    }

    /**
     * 针对特定 Agent 修改时的精准刷新
     */
    public void refreshAgent(Long id) {
        agentDetailCache.invalidate(id);
        allAgentsCache.invalidate(ALL_AGENTS_KEY);
        log.info("Cache invalidated for Agent ID: {}", id);
    }

}