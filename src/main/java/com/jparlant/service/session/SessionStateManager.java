package com.jparlant.service.session;

import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jparlant.model.Context;
import com.jparlant.model.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 会话状态管理服务
 * 提供深度的行为上下文管理能力
 */
@RequiredArgsConstructor
@Slf4j
public class SessionStateManager {


    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;


    private static final String SESSION_PREFIX = "jparlant:agent:";
    private static final Duration SESSION_STATE_TTL = Duration.ofDays(2);


    public static SessionState initSessionState(String sessionId, String userId) {
        return SessionState.initial(sessionId, userId, null);
    }


    /**
     * 创建sessionId
     * @param userId
     * @return
     */
    public String generateSessionId(String userId) {
        // 采用固定格式，确保同一个用户在有效期内始终进入同一个会话
        return "session:u_" + userId;
    }

    private String getSessionKey(String sessionId){
        return SESSION_PREFIX + sessionId;
    }

    private Mono<SessionState> findSessionState(String key){
        return redisTemplate.opsForValue()
                .get(key)
                .flatMap(json -> {
                    try {
                        // 手动反序列化
                        SessionState state = objectMapper.readValue(json, SessionState.class);
                        return Mono.just(state);
                    } catch (JsonProcessingException e) {
                        log.error("SessionState 反序列化失败: key={}", key, e);
                        return Mono.error(new RuntimeException("会话数据解析失败"));
                    }
                })
                .doOnNext(state -> log.debug("获取到会话状态: key={}, agentId={}", key, state.agentId()));
    }


    /**
     * 逻辑：只查询 Redis。
     * 如果 Key 不存在，返回 Mono.empty()。
     */
    public Mono<SessionState> getSessionState(Context context) {
        return findSessionState(getSessionKey(context.sessionId()));
    }


    public Mono<SessionState> getSessionState(String sessionId) {
        return findSessionState(getSessionKey(sessionId));
    }


    /**
     * 根据传入的上下文（必须包含确定的 agentId）创建并保存初始状态。
     */
    public Mono<SessionState> initializeAndSaveSession(Context context) {
        log.info("正在为 {} 初始化SessionState", context.sessionId());
        SessionState initialState = SessionState.initial(
                context.sessionId(),
                context.userId(),
                context.agentId()
        );
        // 激活标签
        if(CollectionUtil.isNotEmpty(context.tags())){
            for(String tag : context.tags()){
                initialState = initialState.withAddedTag(tag);
            }
        }
        // 已知变量
        if(CollectionUtil.isNotEmpty(context.metadata())){
            initialState = initialState.withVariables(context.metadata());
        }
        return saveSessionState(initialState);
    }


    /**
     * 保存会话状态
     */
    public Mono<SessionState> saveSessionState(SessionState sessionState) {
        String key = SESSION_PREFIX + sessionState.sessionId();

        try {
            // 手动序列化为 JSON 字符串
            String json = objectMapper.writeValueAsString(sessionState);

            return redisTemplate.opsForValue()
                    .set(key, json, SESSION_STATE_TTL)
                    .thenReturn(sessionState)
                    .doOnSuccess(state -> log.info("保存会话状态成功, sessionState最新值为：{}", state));

        } catch (JsonProcessingException e) {
            log.error("SessionState 序列化失败: sessionId={}", sessionState.sessionId(), e);
            return Mono.error(new RuntimeException("会话数据保存失败"));
        }
    }
    
    /**
     * 更新会话阶段
     */
    public Mono<SessionState> updatePhase(Context context, SessionState.SessionPhase newPhase) {
        String sessionId = context.sessionId();
        return getSessionState(context)
            .map(state -> state.withPhase(newPhase))
            .flatMap(this::saveSessionState)
            .doOnSuccess(state -> log.info("会话阶段更新: sessionId={}，最新阶段为：{}", sessionId, newPhase));
    }
    
    /**
     * 设置会话变量
     */
    public Mono<SessionState> setVariable(Context context, String key, Object value) {
        String sessionId = context.sessionId();
        return getSessionState(context)
            .map(state -> state.withVariable(key, value))
            .flatMap(this::saveSessionState)
            .doOnSuccess(state -> log.debug("设置会话变量: sessionId={}, {}={}", 
                sessionId, key, value));
    }


    public Mono<SessionState> setVariable(String sessionId, String key, Object value) {
        return getSessionState(sessionId)
                .map(state -> state.withVariable(key, value))
                .flatMap(this::saveSessionState)
                .doOnSuccess(state -> log.debug("设置会话变量: sessionId={}, {}={}",
                        sessionId, key, value));
    }


    /**
     * 批量设置会话变量
     */
    public Mono<SessionState> setVariables(Context context, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return getSessionState(context);
        }

        String sessionId = context.sessionId();
        return getSessionState(context)
                .map(state -> state.withVariables(updates))
                .flatMap(this::saveSessionState)
                .doOnSuccess(state -> log.debug("批量设置会话变量成功: sessionId={}, keys={}",
                        sessionId, updates.keySet()));
    }



    /**
     * 添加会话标签
     */
    public Mono<SessionState> addTag(Context context, String tag) {
        String sessionId = context.sessionId();
        return getSessionState(context)
            .map(state -> state.withAddedTag(tag))
            .flatMap(this::saveSessionState)
            .doOnSuccess(state -> log.debug("添加会话标签: sessionId={}, tag={}", 
                sessionId, tag));
    }
    
    /**
     * 添加约束条件
     */
    public Mono<SessionState> addConstraint(Context context, String key, Object constraint) {
        String sessionId = context.sessionId();
        return getSessionState(context)
            .map(state -> state.withConstraint(key, constraint))
            .flatMap(this::saveSessionState)
            .doOnSuccess(state -> log.debug("添加会话约束: sessionId={}, {}={}", 
                sessionId, key, constraint));
    }
    
    /**
     * 清理会话状态
     */
    public Mono<Void> clearSessionState(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        return redisTemplate.opsForValue()
            .delete(key)
            .then()
            .doOnSuccess(v -> log.info("清理会话状态: {}", sessionId));
    }
    
    /**
     * 检查会话是否存在
     */
    public Mono<Boolean> sessionExists(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        return redisTemplate.hasKey(key);
    }
    
    /**
     * 延长会话过期时间
     */
    public Mono<Boolean> extendSession(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        return redisTemplate.expire(key, SESSION_STATE_TTL)
            .doOnSuccess(success -> {
                if (success) {
                    log.debug("延长会话过期时间: {}", sessionId);
                }
            });
    }
}