package com.jparlant.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jparlant.config.CacheSyncProperties;
import com.jparlant.model.CacheRefreshMessage;
import com.jparlant.service.chat.JParlantChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import reactor.core.scheduler.Schedulers;

@RequiredArgsConstructor
@Slf4j
public class ReactiveCacheMessageSubscriber implements ApplicationListener<ApplicationReadyEvent> {


    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final JParlantChatService jParlantChatService;
    private final ObjectMapper objectMapper;
    private final CacheSyncProperties properties;


    /**
     * ApplicationReadyEvent 确保在应用完全启动后开始监听
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 使用配置类中的 channel
        reactiveRedisTemplate.listenTo(ChannelTopic.of(properties.getChannel()))
                .map(ReactiveSubscription.Message::getMessage)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(this::processMessage)
                .doOnError(e -> log.error("Redis 订阅流发生错误", e))
                .retry()
                .subscribe();

        log.info("响应式 Redis 缓存监听已启动，频道: {}", properties.getChannel());
    }

    private void processMessage(String json) {
        try {
            log.info("收到 Redis 原始消息: {}", json);
            CacheRefreshMessage msg = objectMapper.readValue(json, CacheRefreshMessage.class);

            // 执行本地缓存清理 (Caffeine)
            switch (msg.getType()) {
                case SINGLE_AGENT -> jParlantChatService.refreshAgent(msg.getAgentId());
                case AGENT_FLOW -> jParlantChatService.refreshAgentFlows(msg.getAgentId());
                case COMPLIANCE -> jParlantChatService.refreshComplianceRules(msg.getAgentId());
                case GLOSSARY -> jParlantChatService.refreshGlossary(msg.getAgentId());
                case ALL -> jParlantChatService.refreshAll();
            }
            log.info("本地缓存同步成功: {}", msg.getType());
        } catch (Exception e) {
            log.error("解析缓存消息失败: {}", json, e);
        }
    }
}
