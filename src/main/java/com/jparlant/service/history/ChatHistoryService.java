package com.jparlant.service.history;


import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jparlant.constant.ContextKeys;
import com.jparlant.model.ChatMessage;
import com.jparlant.model.Context;
import com.jparlant.model.FlowContext;
import com.jparlant.service.session.SessionStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;


@RequiredArgsConstructor
@Slf4j
public class ChatHistoryService {


    private final ObjectMapper objectMapper;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final SessionStateManager sessionStateManager;



    private static final String CHAT_HISTORY_PREFIX = "jparlant:agent:chat:history:";


    /**
     * 清除指定会话的所有历史记录
     * @param sessionId 会话ID
     * @return Mono<Void>
     */
    public Mono<Void> clearHistory(String sessionId) {
        String key = CHAT_HISTORY_PREFIX + sessionId;
        return redisTemplate.delete(key)
                .doOnSuccess(v -> log.info("Successfully cleared history for session: {}", sessionId))
                .doOnError(e -> log.error("Failed to clear history for session: {}", sessionId, e))
                .then();
    }


    /**
     * 核心过滤逻辑：过滤出步骤id的历史上下文
     * @return 过滤后的消息流
     */
    @SuppressWarnings("unchecked")
    public Flux<ChatMessage> getFilteredContext(Context context, int limit) {
        String sessionId = context.sessionId();;
        // 1. 首先获取 Session 状态
        return sessionStateManager.getSessionState(context)
                .flatMapMany(sessionState -> {

                    // --- 2. 安全提取 currentStepId ---
                    Long currentStepId = sessionState.getVariable(ContextKeys.Session.CURRENT_FLOW_STEP, Long.class);

                    // 如果没有当前步骤 ID，无法进行步骤隔离，直接返回空流或处理全局逻辑
                    if (currentStepId == null) {
                        log.warn("Session {}: currentStepId is null, skipping context build.", sessionId);
                        return getConversationHistory(sessionId, limit);
                    }

                    // --- 3. 提取并判断 StepState 及 isErrorState ---
                    boolean isErrorState = false;
                    Map<String, Object> flowContextMap = sessionState.getVariable(ContextKeys.Session.FLOW_CONTEXT, Map.class);
                    if (flowContextMap != null) {
                        FlowContext flowContext = FlowContext.fromMap(flowContextMap);
                        FlowContext.StepState stepState = flowContext.getStepState(currentStepId);

                        // 只有当 stepState 存在且状态为 FAIL 时，才判定为错误状态
                        if (stepState != null && stepState.getStatus() == FlowContext.StepState.Status.FAIL) {
                            isErrorState = true;
                        }
                    }

                    final boolean finalIsErrorState = isErrorState; // 用于内部 Lambda

                    // --- 4. 获取历史记录并应用过滤逻辑 ---
                    return getConversationHistory(sessionId, limit)
                            .collectList() // 将 Flux 转为 List 以便进行截取操作
                            .flatMapMany(allMessages -> {

                                if (CollectionUtil.isEmpty(allMessages)) {
                                    return Flux.empty();
                                }

                                // A. 物理隔离：只筛选属于当前 stepId 的消息
                                List<ChatMessage> currentStepMessages = allMessages.stream()
                                        .filter(msg -> Objects.equals(msg.stepId(), currentStepId))
                                        .collect(Collectors.toList());

                                // 如果当前步骤是空的（比如刚跳入新步骤），则返回全量历史中的最后 2 条，以保持起码的对话连贯性。
                                if (currentStepMessages.isEmpty()) {
                                    int fallbackSize = Math.min(allMessages.size(), 2);
                                    return Flux.fromIterable(allMessages.subList(allMessages.size() - fallbackSize, allMessages.size()));
                                }

                                // B. 动态窗口策略
                                // 如果是 FAIL 状态，强制缩小注意力范围（最近2条）；否则全量返回当前步骤对话
                                int windowSize = finalIsErrorState ? 2 : currentStepMessages.size();
                                int start = Math.max(currentStepMessages.size() - windowSize, 0);

                                List<ChatMessage> filteredResult = currentStepMessages.subList(start, currentStepMessages.size());

                                return Flux.fromIterable(filteredResult);
                            });
                })
                .switchIfEmpty(Flux.defer(() -> {
                    log.warn("getFilteredContext方法: SessionState not found for sessionId: {}", sessionId);
                    return getConversationHistory(sessionId, limit);
                }));
    }


    public Flux<ChatMessage> getConversationHistory(String sessionId, int limit) {
        String key = CHAT_HISTORY_PREFIX + sessionId;

        // Redis range 逻辑：
        // 如果要取最近的 10 条，应该用 range(key, -10, -1)
        long start = (limit <= 0) ? 0 : -limit;
        long end = -1;

        return redisTemplate.opsForList()
                .range(key, start, end) // 获取 Flux<String>
                .flatMap(json -> {
                    try {
                        // 将 JSON 字符串转换为 ChatMessage 对象
                        ChatMessage message = objectMapper.readValue(json, ChatMessage.class);
                        return Mono.just(message);
                    } catch (JsonProcessingException e) {
                        // 如果某条记录损坏，记录日志并跳过，保证其他历史消息能正常加载
                        log.error("反序列化历史消息失败: sessionId={}, json={}", sessionId, json, e);
                        return Mono.empty();
                    }
                });
    }


    public Mono<Void> saveMessageToHistory(String sessionId, ChatMessage message) {
        String key = CHAT_HISTORY_PREFIX + sessionId;
        try {
            String json = objectMapper.writeValueAsString(message);
            return redisTemplate.opsForList()
                    .rightPush(key, json)
                    .then(redisTemplate.expire(key, Duration.ofDays(2)))
                    .then();
        } catch (JsonProcessingException e) {
            log.error("saveMessageToHistory失败: sessionId={}", sessionId, e);
            return Mono.error(new RuntimeException("模型对话记忆保存失败"));
        }
    }

    public Mono<ChatMessage> saveConversationAndReturn(String sessionId, String userMessage, ChatMessage assistantMessage) {
        // 1. 获取当前的会话状态以提取 stepId
        return sessionStateManager.getSessionState(sessionId)
                .flatMap(sessionState -> {
                    // 2. 从 Session 中获取当前的步骤 ID (Long 类型)
                    Long currentStepId = sessionState.getVariable(ContextKeys.Session.CURRENT_FLOW_STEP, Long.class);

                    // 3. 构造带 stepId 的用户消息对象
                    ChatMessage userMsg = ChatMessage.user(userMessage, currentStepId);

                    // 4. 重新构造带 stepId 的 AI 消息对象
                    // 注意：由于 record 是不可变的，必须通过属性访问器 content() 和 appliedGuidelines() 重新创建
                    ChatMessage aiMsgWithStep = ChatMessage.assistant(
                            assistantMessage.content(),
                            currentStepId,
                            assistantMessage.appliedGuidelines()
                    );

                    // 5. 串行保存消息：先存 User 消息，后存 Assistant 消息，最后返回带 stepId 的 AI 对象
                    return saveMessageToHistory(sessionId, userMsg)
                            .then(saveMessageToHistory(sessionId, aiMsgWithStep))
                            .thenReturn(aiMsgWithStep);
                });
    }

}
