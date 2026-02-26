package com.jparlant.service.chat;

import com.jparlant.model.ChatMessage;
import com.jparlant.model.ChatRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * J-Parlant 聊天服务接口 - 对外API
 * 只暴露chat和chatStream两个核心方法
 */
public interface JParlantChatService {

    /**
     * 非流式聊天
     * @param chatRequest 聊天请求
     * @return 聊天响应
     */
    Mono<ChatMessage> chat(ChatRequest chatRequest);

    /**
     * 流式聊天
     * @param chatRequest 聊天请求  
     * @return 聊天响应流
     */
    Flux<ChatMessage> chatStream(ChatRequest chatRequest);

    /**
     * 【一键全量刷新】
     * 刷新指定智能体的所有相关本地缓存（包括定义、流程步骤、合规规则、词汇表）。
     * 当后台修改了智能体的任意配置时，推荐调用此方法。
     * @param agentId 智能体ID
     */
    void refreshAgent(Long agentId);

    /**
     * 【全局全量刷新】
     * 清空系统中所有智能体的所有本地缓存。
     * 慎用：会导致后续请求产生瞬时数据库压力。
     */
    void refreshAll();

    /**
     * 【精细化刷新：流程定义】
     * 仅刷新指定智能体的流程图、步骤及流转关系缓存。
     * @param agentId 智能体ID
     */
    void refreshAgentFlows(Long agentId);

    /**
     * 【精细化刷新：合规规则】
     * 仅刷新指定智能体的输入/输出合规校验规则缓存。
     * @param agentId 智能体ID
     */
    void refreshComplianceRules(Long agentId);

    /**
     * 【精细化刷新：领域词汇表】
     * 仅刷新指定智能体的专业术语及匹配引擎缓存。
     * @param agentId 智能体ID
     */
    void refreshGlossary(Long agentId);

}