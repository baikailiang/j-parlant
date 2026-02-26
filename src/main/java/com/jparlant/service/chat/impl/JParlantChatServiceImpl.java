package com.jparlant.service.chat.impl;

import com.jparlant.model.ChatMessage;
import com.jparlant.model.ChatRequest;
import com.jparlant.service.agent.AgentDefinitionManager;
import com.jparlant.service.agent.AgentService;
import com.jparlant.service.chat.JParlantChatService;
import com.jparlant.service.compliance.ComplianceEngine;
import com.jparlant.service.exception.ParameterMissingException;
import com.jparlant.service.flow.AgentFlowManager;
import com.jparlant.service.flow.handler.action.FlowMetadataService;
import com.jparlant.service.glossary.GlossaryEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;

/**
 * J-Parlant 聊天服务实现类
 * 封装内部复杂性，只对外暴露简单的chat和chatStream方法
 */
@RequiredArgsConstructor
@Slf4j
public class JParlantChatServiceImpl implements JParlantChatService {

    private final AgentService agentService;
    private final AgentDefinitionManager agentDefinitionManager;
    private final AgentFlowManager agentFlowManager;
    private final ComplianceEngine complianceEngine;
    private final GlossaryEngine glossaryEngine;
    private final FlowMetadataService flowMetadataService;

    @Override
    public Mono<ChatMessage> chat(ChatRequest chatRequest) {
        if (chatRequest == null || !StringUtils.hasText(chatRequest.userId())) {
            return Mono.error(new ParameterMissingException("userId不能为空"));
        }

        log.info("开始处理非流式聊天请求: userId={}", chatRequest.userId());
        long startTime = System.currentTimeMillis();

        return agentService.chat(chatRequest)
                .doOnSuccess(response -> log.info("非流式聊天处理完成: userId={}, 耗时={}ms", chatRequest.userId(), System.currentTimeMillis() - startTime))
                .doOnError(e -> log.error("非流式聊天发生异常", e));
    }



    @Override
    public Flux<ChatMessage> chatStream(ChatRequest chatRequest) {
        if (chatRequest == null || !StringUtils.hasText(chatRequest.userId())) {
            return Flux.error(new ParameterMissingException("userId不能为空"));
        }

        log.info("开始处理流式聊天请求: userId={}", chatRequest.userId());

        return agentService.chatStream(chatRequest)
                .doOnComplete(() -> log.info("流式聊天传输完成: userId={}", chatRequest.userId()))
                .doOnError(e -> log.error("流式聊天发生异常", e));
    }

    @Override
    public void refreshAgent(Long agentId) {
        if (agentId == null) return;
        log.info("【全量刷新】准备刷新智能体及其所有组件缓存: agentId={}", agentId);

        // 1. 刷新智能体基础定义
        agentDefinitionManager.refreshAgent(agentId);
        // 2. 刷新业务流程(Intent/Step/Transition)
        agentFlowManager.refreshAgentFlows(agentId);
        // 3. 刷新合规引擎规则
        complianceEngine.refreshRules(agentId);
        // 4. 刷新专业术语表匹配引擎
        glossaryEngine.refreshGlossary(agentId);

        log.info("【全量刷新】智能体缓存刷新完成: agentId={}", agentId);
    }

    @Override
    public void refreshAll() {
        log.warn("【全局刷新】准备清空系统内所有智能体的所有本地缓存！");

        agentDefinitionManager.refreshCache();
        agentFlowManager.refreshAllFlows();
        complianceEngine.refreshAllRules();
        glossaryEngine.refreshAll();

        log.warn("【全局刷新】全系统本地缓存已重置完成。");
    }

    @Override
    public void refreshAgentFlows(Long agentId) {
        log.info("【精细刷新】刷新流程定义缓存: agentId={}", agentId);
        agentFlowManager.refreshAgentFlows(agentId);
    }

    @Override
    public void refreshComplianceRules(Long agentId) {
        log.info("【精细刷新】刷新合规规则缓存: agentId={}", agentId);
        complianceEngine.refreshRules(agentId);
    }

    @Override
    public void refreshGlossary(Long agentId) {
        log.info("【精细刷新】刷新词汇表匹配缓存: agentId={}", agentId);
        glossaryEngine.refreshGlossary(agentId);
    }


    /**
     * 内部校验逻辑
     */
    private boolean isInvalidRequest(ChatRequest request) {
        return request == null
                || !StringUtils.hasText(request.userId())
                || !StringUtils.hasText(request.message());
    }

    /**
     * 统一创建错误提示消息
     */
    private ChatMessage createErrorMessage(String content) {
        // 假设 ChatMessage.assistant 是创建回复的方法，且第二个参数是引用的上下文列表
        return ChatMessage.assistant(content, Collections.emptyList());
    }


}