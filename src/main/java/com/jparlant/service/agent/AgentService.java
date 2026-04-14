package com.jparlant.service.agent;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.jparlant.constant.ContextKeys;
import com.jparlant.enums.Complexity;
import com.jparlant.enums.Emotion;
import com.jparlant.model.*;
import com.jparlant.service.compliance.ComplianceEngine;
import com.jparlant.service.exception.AgentFlowException;
import com.jparlant.service.flow.AgentFlowEngine;
import com.jparlant.service.glossary.GlossaryEngine;
import com.jparlant.service.history.ChatHistoryService;
import com.jparlant.service.intent.IntentAnalysisService;
import com.jparlant.service.monitoring.MetricsService;
import com.jparlant.service.session.SessionStateManager;
import com.jparlant.utils.ReactiveUserContext;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;

/**
 * 对话系统的编排大脑
 * 它负责协调各个子系统（意图分析、流程引擎、术语库、合规引擎），
 * 决定对于用户的输入是应该执行预定义的业务逻辑（Flow），
 * 还是交给 LLM 进行泛化处理，或者进行两者的混合增强。
 */
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    // 全部定义为 final，确保在构造时就完成注入
    // todo 暂时，后面改成可配置的
    private final ChatClient chatClient;
    private final AgentFlowEngine flowEngine;
    private final IntentAnalysisService intentAnalysisService;
    private final GlossaryEngine glossaryEngine;
    private final ComplianceEngine complianceEngine;
    private final SessionStateManager sessionStateManager;
    private final MetricsService metricsService;
    private final AgentRouter agentRouter;
    private final ChatHistoryService chatHistoryService;


    /**
     * 非流式聊天
     * 职责：调度路由引擎 -> 检查合规 -> 执行业务管道 -> 生成/验证响应
     * @param request 聊天请求
     * @return 聊天响应
     */
    public Mono<ChatMessage> chat(ChatRequest request) {
        // 启动监控
        Timer.Sample timer = metricsService.startChatTimer();

        // 1. AgentRouter 识别 Agent 并构建 Context
        return agentRouter.resolveAgentAndContext(request)
                .flatMap(tuple -> {
                    Agent agent = tuple.getT1();
                    Context context = tuple.getT2();
                    // 2. 用户输入合规性检查 (前置审计)
                    return validateUserInput(request.message(), context)
                            .flatMap(inputValidationResult -> {
                                // 处理输入不合规情况
                                if (!inputValidationResult.isCompliant()) {
                                    return handleInputComplianceViolation(inputValidationResult, context, agent, timer, request.message());
                                }

                                // 3. 执行核心处理管道 (意图分析 + 流程引导)
                                return executeFullProcessingPipeline(request.message(), request.files(), context, agent.instructions())
                                        .flatMap(result -> {
                                            // 记录日志
                                            metricsService.recordChatRequest(agent.id(), result.intentName);

                                            // 4. 响应
                                            Mono<String> responseContentMono;
                                            if (result.isDirectResponse()) {
                                                // 如果是直接返回，内容就是 result.response()
                                                responseContentMono = Mono.just(result.response());
                                            } else {
                                                // 如果不是，走原来的 LLM 调用逻辑
                                                responseContentMono = chatHistoryService.getFilteredContext(context, 10)
                                                        .collectList()
                                                        .flatMap(history -> callAI(result.systemPrompt(), result.modifiedInput(), history))
                                                        .mapNotNull(response -> response.getResult().getOutput().getText());
                                            }

                                            // 接下来的逻辑利用 responseContentMono 继续执行，保持后置合规和保存历史逻辑不变
                                            return responseContentMono.flatMap(responseContent -> {
                                                return validateResponse(responseContent, context)
                                                        .flatMap(validatedResponse -> {
                                                            ChatMessage assistantMsg = ChatMessage.assistant(validatedResponse, result.appliedGuidelines());
                                                            metricsService.recordChatDuration(timer, agent.id(), result.isDirectResponse() ? "direct_hit" : "llm_enhanced");

                                                            return sessionStateManager.extendSession(context.sessionId())
                                                                    .then(chatHistoryService.saveConversationAndReturn(context.sessionId(), request.message(), assistantMsg));
                                                        });
                                            });
                                        })
                                        .last();
                            });
                }).contextWrite(ReactiveUserContext.withUserId(request.userId()));

    }



    /**
     * 流式聊天处理 - 返回 Flux<ChatMessage>
     */
    public Flux<ChatMessage> chatStream(ChatRequest request) {
        // 启动监控
        Timer.Sample timer = metricsService.startChatTimer();

        // 1. AgentRouter 识别 Agent 并构建 Context (与非流式完全一致)
        return agentRouter.resolveAgentAndContext(request)
                .flatMapMany(tuple -> {
                    Agent agent = tuple.getT1();
                    Context context = tuple.getT2();

                    // 2. 用户输入合规性检查
                    return validateUserInput(request.message(), context)
                            .flatMapMany(inputValidationResult -> {

                                // 2.1 处理输入不合规情况 (返回违规处理后的 ChatMessage)
                                if (!inputValidationResult.isCompliant()) {
                                    return handleInputComplianceViolation(inputValidationResult, context, agent, timer, request.message())
                                            .flux(); // Mono<ChatMessage> 转为 Flux<ChatMessage>
                                }

                                // 3. 执行核心处理管道 (意图分析 + 流程引导)
                                return executeFullProcessingPipeline(request.message(), request.files(), context, agent.instructions())
                                        .concatMap(result -> {
                                            // 记录监控指标
                                            metricsService.recordChatRequest(agent.id(), result.intentName);

                                            if (result.isDirectResponse()) {
                                                log.info("执行直接返回策略: sessionId={}, intent={}", context.sessionId(), result.intentName());

                                                // 构造响应消息
                                                ChatMessage directAssistantMsg = ChatMessage.assistant(result.response(), result.appliedGuidelines());

                                                // 处理副作用并返回结果流
                                                // 流程：延长 Session -> 保存历史记录 -> 发射消息
                                                return sessionStateManager.extendSession(context.sessionId())
                                                        .then(chatHistoryService.saveConversationAndReturn(context.sessionId(), request.message(), directAssistantMsg))
                                                        .doOnSuccess(s -> metricsService.recordChatDuration(timer, agent.id(), "direct_response"))
                                                        .thenMany(Flux.just(directAssistantMsg));
                                            }

                                            // 4. 获取历史记录并调用流式 AI
                                            return chatHistoryService.getFilteredContext(context, 10)
                                                    .collectList()
                                                    .flatMapMany(history -> {
                                                        // 用于聚合完整内容的容器，供流结束后保存历史
                                                        StringBuilder fullContent = new StringBuilder();

                                                        // --- A. LLM 文本流输出部分 ---
                                                        Flux<ChatMessage> textStream = callAIWithStream(result.systemPrompt(), result.modifiedInput(), history)
                                                                .map(chunk -> {
                                                                    // 必须提取文本内容进行累加，而不是 append(chunk)
                                                                    String chunkText = chunk.getResult().getOutput().getText();
                                                                    if (chunkText != null) {
                                                                        fullContent.append(chunkText);
                                                                    }
                                                                    return ChatMessage.assistant(chunkText, result.appliedGuidelines());
                                                                });

                                                        // --- B. 后置副作用部分 (保存历史) ---
                                                        // 使用 defer 确保在 textStream 完成（onComplete）后才执行
                                                        Mono<Void> sideEffect = Mono.defer(() -> {
                                                            String finalContentStr = fullContent.toString();
                                                            log.info("流式响应结束，准备保存完整内容: sessionId={}, length={}",
                                                                    context.sessionId(), finalContentStr.length());

                                                            ChatMessage finalAssistantMsg = ChatMessage.assistant(finalContentStr, result.appliedGuidelines());

                                                            return sessionStateManager.extendSession(context.sessionId())
                                                                    .then(chatHistoryService.saveConversationAndReturn(context.sessionId(), request.message(), finalAssistantMsg))
                                                                    .doOnSuccess(s -> metricsService.recordChatDuration(timer, agent.id(), "llm_stream_enhanced"))
                                                                    .then(); // 显式转为 Mono<Void>
                                                        });

                                                        // --- C. 组合逻辑 ---
                                                        // concatWith 保证在 textStream 结束后才开始执行 sideEffect
                                                        // .then(Mono.empty()) 确保 sideEffect 执行了，但不会向前端多发一个 ChatMessage
                                                        return textStream.concatWith(sideEffect.then(Mono.empty()));

                                                    });
                                        });
                            });
                }).contextWrite(ReactiveUserContext.withUserId(request.userId()));
    }


    /**
     * 聊天统一处理违规逻辑
     */
    private Mono<ChatMessage> handleInputComplianceViolation(ComplianceEngine.ComplianceCheckResult result,
                                                             Context context,
                                                             Agent agent,
                                                             Timer.Sample timer,
                                                             String userContent) {
        ComplianceRule rule = result.triggeredRule();

        // 1. 记录通用指标
        metricsService.recordComplianceViolation(rule.id(), rule.name(), agent.id());
        metricsService.recordChatDuration(timer, agent.id(), "compliance_blocked");

        // 2. 核心逻辑：获取状态、提取 stepId、更新并保存历史
        return sessionStateManager.getSessionState(context)
                .flatMap(sessionState -> {
                    // 获取当前步骤 id
                    Long stepId = sessionState.getVariable(ContextKeys.Session.CURRENT_FLOW_STEP, Long.class);

                    // 4. 构建返回给前端的拦截消息（Assistant 角色）
                    ChatMessage complianceMessage = ChatMessage.assistant(
                            rule.blockedResponse(),
                            stepId,
                            List.of()
                    );

                    // 5. 执行组合操作：
                    // a. 将导致违规的用户输入保存到历史中（带上 stepId 以便后续做步骤隔离）
                    // b. 返回拦截消息
                    return chatHistoryService.saveMessageToHistory(context.sessionId(), ChatMessage.user(userContent, stepId))
                            .thenReturn(complianceMessage);
                });
    }



    /**
     * 完整的处理管道
     */
    private Flux<ProcessingPipelineResult> executeFullProcessingPipeline(String userInput, List<MultipartFile> files, Context context, String agentInstructions) {
        log.info("开始执行完整处理管道: userInput={}, sessionId={}", userInput, context.sessionId());
        
        return sessionStateManager.getSessionState(context)
            .flatMapMany(sessionState -> {
                log.info("当前会话: sessionState={}", sessionState);
                
                // 第一阶段：意图分析
                return intentAnalysisService.analyzeIntent(userInput, context, sessionState, agentInstructions)
                    .flatMapMany(intentResult -> {
                        if(CollectionUtil.isNotEmpty(files)){
                            intentResult = intentResult.withFiles(files);
                        }
                        log.info("意图分析完成: userInput={}, intentResult={}", userInput, intentResult);
                        
                        // 第二阶段：根据意图分析结果进行流程引导处理
                        IntentAnalysisResult finalIntentResult = intentResult;
                        return flowEngine.navigateWorkflowStep(context, intentResult)
                            .flatMap(flowGuidanceResult -> {
                                log.info("流程引导完成: userInput={}, flowGuidanceResult={}", userInput, flowGuidanceResult);
                                
                                // 第三阶段：综合以上结果决定最终处理策略
                                return determineProcessingStrategy(userInput, context, agentInstructions, finalIntentResult, flowGuidanceResult);
                            });
                    });
            });
    }
    
    /**
     * 决定处理策略
     * 目前的处理策略为只有一种：LLM_ENHANCED，即全部由大模型来生成
     */
    private Mono<ProcessingPipelineResult> determineProcessingStrategy(
            String userInput,
            Context context,
            String agentInstructions,
            IntentAnalysisResult intentResult,
            FlowGuidanceResult flowGuidanceResult) {

        log.info("执行全大模型驱动策略, userInput={}, 流程状态: {}", userInput, flowGuidanceResult.type());

        // 1. 核心逻辑：拦截流程错误
        if (flowGuidanceResult.type() == FlowGuidanceResult.ResultType.ERROR) {
            log.error("流程引导结果返回错误: userId={}, reason={}", context.userId(), flowGuidanceResult.error());

            // 抛出自定义异常，reason 可以从 flowGuidanceResult 中获取
            String errorMessage = StringUtils.hasText(flowGuidanceResult.error())
                    ? flowGuidanceResult.error()
                    : "业务流程处理异常";
            return Mono.error(new AgentFlowException(errorMessage));
        }

        // 假设 FlowStep 定义了一个 isDirectReturn() 方法，或者根据 FlowGuidanceResult 的某种状态
        if (flowGuidanceResult.currentStep() != null && flowGuidanceResult.currentStep().isDirectReturn()) {
            log.info("检测到直接返回配置，跳过大模型生成: step={}", flowGuidanceResult.currentStep().name());
            String intentName = StringUtils.hasText(intentResult.primaryIntentName()) ? intentResult.primaryIntentName() : "UNKNOWN";

            // 直接使用 flowGuidanceResult 中的 message 作为响应内容
            return Mono.just(ProcessingPipelineResult.directResponse(
                    flowGuidanceResult.message(),
                    intentName,
                    userInput,
                    List.of()
            ));
        }

        return sessionStateManager.getSessionState(context)
                .flatMap(sessionState ->
                        buildUnifiedSystemPrompt(
                                userInput,
                                context,
                                sessionState,
                                agentInstructions,
                                flowGuidanceResult)
                )
                .map(systemPrompt -> {

                    String intentName = StringUtils.hasText(intentResult.primaryIntentName()) ? intentResult.primaryIntentName() : "UNKNOWN";
                    // 将所有逻辑统一封装为 LLM_ENHANCED 策略
                    return ProcessingPipelineResult.llmEnhanced(
                            systemPrompt,
                            userInput,
                            intentName,
                            // todo 此处需要记录影响AI行为的一些规则
                            List.of()
                    );
                });
    }


    /**
     * 构建统一的模型处理prompt
     */
    @SuppressWarnings("unchecked")
    private Mono<String> buildUnifiedSystemPrompt(String userInput, Context context, SessionState sessionState, String agentInstructions, FlowGuidanceResult flowGuidanceResult) {

        return glossaryEngine.buildGlossaryPrompt(userInput, context)
                .map(glossaryPrompt -> {
                    FlowContext flowContext = FlowContext.fromMap(sessionState.getVariable(ContextKeys.Session.FLOW_CONTEXT, Map.class));
                    Map<String, Object> meta = flowContext.getGlobalMetadata();
                    if (meta == null) {
                        meta = Collections.emptyMap(); // 使用空 Map 替代 null
                    }

                    // 强类型解析枚举（增加类型转换安全检查）
                    // 使用 String.valueOf() 避免直接强转 (String) 可能触发的 NPE 或 ClassCastException
                    String emotionStr = String.valueOf(meta.getOrDefault(ContextKeys.Global.EMOTION, ""));
                    String complexityStr = String.valueOf(meta.getOrDefault(ContextKeys.Global.COMPLEXITY, ""));

                    Emotion emotion = Emotion.of(emotionStr);
                    Complexity complexity = Complexity.of(complexityStr);

                    FlowGuidanceResult.ResultType flowResultType = FlowGuidanceResult.ResultType.CONTINUE;
                    if (flowGuidanceResult != null && flowGuidanceResult.type() != null) {
                        flowResultType = flowGuidanceResult.type();
                    }

                    // 情绪指令映射
                    String emotionInstruction = switch (emotion) {
                        case POSITIVE -> "用户目前心情愉悦，请保持热情、友好的基调，给予积极的正向反馈。";
                        case NEGATIVE -> "检测到用户表达了不满或焦虑，请【先行安抚】，使用高度同理心的措辞，避免生硬，优先解决其顾虑。";
                        case URGENT   -> "用户表现出紧迫感，请回复【简洁明了】，直击要点，不要有冗余的客套话。";
                        default       -> "用户情绪平稳，请保持专业、礼貌且高效的银行服务标准。";
                    };

                    // 复杂度指令映射
                    String complexityInstruction = switch (complexity) {
                        case SIMPLE  -> "当前问题较简单，回复应力求精炼，避免过度解释。";
                        case COMPLEX -> "当前业务逻辑较复杂，请务必【分条述说】，确保步骤清晰，逻辑严密。";
                        default      -> "回复长度适中，解释清晰即可。";
                    };

                    // 流程状态描述 (ResultType)
                    String flowActionDesc = switch (flowResultType) {
                        case STAY      -> "【核心阻碍】：信息不全或校验失败，必须【停留】在当前环节，引导用户修正或补全。";
                        case CONTINUE  -> "【正常流转】：用户输入有效，正在引导其进入后续逻辑。";
                        case COMPLETED -> "【流程终点】：业务已成功办理完成，请进行礼貌的收尾。";
                        case ERROR     -> "【逻辑异常】：处理过程出现错误，请礼貌地向用户致歉，并尝试引导其重新尝试。";
                    };

                    // 辅助函数：处理空值转为"无"
                    Function<Object, String> v2s = (v) -> {
                        if (v == null) return "无";
                        if (v instanceof String s && s.isBlank()) return "无";
                        if (v instanceof Collection<?> c && c.isEmpty()) return "无";
                        if (v instanceof Map<?, ?> m && m.isEmpty()) return "无";
                        return String.valueOf(v);
                    };

                    String currentIntent = v2s.apply(meta.get(ContextKeys.Global.CURRENT_INTENT_NAME));

                    Map<String, Object> visibleEntities = filterSensitiveData(flowContext.getEntities());
                    String entitiesStr = visibleEntities.isEmpty() ? "暂无有效信息" : JSONUtil.toJsonPrettyStr(visibleEntities);

                    boolean isWorkflowActive = flowGuidanceResult != null && !flowGuidanceResult.hasError();
                    String guidanceMessage = isWorkflowActive ? v2s.apply(flowGuidanceResult.message()) : "无";

                    // 1. 【核心指令区】（最高优先级）
                    String taskSection = "";
                    if (isWorkflowActive) {
                        taskSection = """
                        # [必须优先执行：当前任务指令]
                        你正处于特定的业务流程中，请优先执行以下操作：
                        - **核心引导目标**：%s
                        - **业务动作状态**：%s
                        
                        **[强制执行约束]**：
                        1. 如果上述指令中提到“信息不完整或有误”，你必须停止执行后续任何业务步骤，转而优先执行此指令。
                        """.formatted(guidanceMessage, flowActionDesc);
                    }
                    // 2. 基础全局指令、意图分析与会话背景
                    String baseSection = """
                        # 你的角色身份
                        %s

                        # 用户与场景画像
                        - 核心诉求: %s
                        - 沟通策略建议: %s
                        - 任务复杂度: %s
                        - 已采集信息快照: %s
                        - 用户原始输入: %s

                        # 会话背景
                        - 长期记忆: %s
                        - 核心约束: %s
                        - 允许调用的权限: %s
                        """.formatted(
                            agentInstructions,
                            currentIntent,
                            emotionInstruction,
                            complexityInstruction,
                            entitiesStr,
                            userInput,
                            v2s.apply(sessionState.memory()),
                            v2s.apply(sessionState.constraints()),
                            v2s.apply(sessionState.capabilities())
                    );

                    // 3. 业务流程引导块 (动态构建)
                    String flowSection = "";
                    if (isWorkflowActive) {
                        flowSection = """
                            # 业务流转状态上下文
                            - 当前环节: %s
                            - 环节目标: %s
                            - 预期下一步: %s
                            """.formatted(
                                flowGuidanceResult.currentStep() != null ? flowGuidanceResult.currentStep().name() : "无",
                                flowGuidanceResult.currentStep() != null ? flowGuidanceResult.currentStep().description() : "无",
                                flowGuidanceResult.nextStep() != null ? flowGuidanceResult.nextStep().name() : "无"
                        );
                    }

                    // 4. 响应生成准则 (根据是否在流程中动态调整语气)
                    String guidelinesSection;
                    if (isWorkflowActive) {
                        guidelinesSection = """
                            # 回复准则
                            1. **自然转化**: 将业务指令转化为自然的、口语化回复。
                            2. **动机解释**: 索要信息时，简要说明其必要性。
                            3. **纠偏引导**: 若用户话题偏移，请礼貌地通过一句话带回【当前环节】。
                            4. **内容防御**: 严禁提及“工作流”、“系统逻辑”、“步骤ID”等表述或相关的表述。
                            """;
                    } else {
                        // 动态构建关于意图的准则描述
                        String intentRule = "无".equals(currentIntent) || "UNKNOWN".equals(currentIntent)
                                ? "1. **直接响应**: 准确回应用户的核心诉求，无需考虑工作流步骤。"
                                : "1. **直接响应**: 准确回应用户识别到的意图 [" + currentIntent + "]，无需考虑工作流步骤。";
                        guidelinesSection = """
                            # 回复准则
                            %s
                            2. **事实基础**: 严格基于 [术语表] 和你的知识库回答。
                            """.formatted(intentRule);
                    }

                    // 5. 术语表块
                    String glossarySection = StringUtils.hasText(glossaryPrompt) ? """
                    # 专业术语标准定义
                    %s
                    """.formatted(glossaryPrompt) : "";

                    // 1. 基础背景 -> 2. 业务流转 -> 3. 术语表 -> 5. 准则
                    return String.join("\n",
                            taskSection,
                            baseSection,
                            flowSection,
                            glossarySection,
                            guidelinesSection
                    ).trim();
                });
    }

    // todo 可以根据需要只传给大模型需要的数据，避免透传所有数据给大模型
    private Map<String, Object> filterSensitiveData(Map<String, Object> entities) {
        return entities;
    }


    private Mono<String> validateResponse(String response, Context context) {
        return complianceEngine.checkResponseCompliance(response, context)
            .map(complianceResult -> {
                // 1. 如果合规，直接放行原回复
                if (complianceResult.isCompliant()) {
                    return response;
                }

                ComplianceRule rule = complianceResult.triggeredRule();

                // 2. 记录合规违规指标
                metricsService.recordComplianceViolation(
                        rule.id(),
                        rule.name(),
                        context.agentId()
                );

                // 3. 返回处理：有拦截话术则返回话术，否则返回原响应内容（静默记录）
                return StringUtils.hasText(rule.blockedResponse()) ? rule.blockedResponse() : response;
            });
    }


    /**
     * 核心优化：提取公共的请求规格准备逻辑
     */
    private ChatClient.ChatClientRequestSpec prepareRequest(String systemPrompt, String userMessage, List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();

        // 1. 系统指令通常放在最前面，确保模型行为准则优先级最高
        messages.add(new SystemMessage(systemPrompt));

        // 2. 转换历史记录
        if (history != null) {
            history.stream()
                    .map(msg -> {
                        if ("user".equalsIgnoreCase(msg.role())) {
                            return new UserMessage(msg.content());
                        } else if ("assistant".equalsIgnoreCase(msg.role())) {
                            return new AssistantMessage(msg.content());
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .forEach(messages::add);
        }

        // 3. 添加当前用户消息
        messages.add(new UserMessage(userMessage));

        // 返回 ChatClient 的请求规格对象，供后续 call() 或 stream() 调用
        return chatClient.prompt().messages(messages);
    }

    private Mono<ChatResponse> callAI(String systemPrompt, String userMessage, List<ChatMessage> history) {
        return Mono.fromCallable(() ->
                        prepareRequest(systemPrompt, userMessage, history)
                                .call()
                                .chatResponse()
                )
                .doOnError(error -> log.error("AI调用失败", error));
    }

    private Flux<ChatResponse> callAIWithStream(String systemPrompt, String userMessage, List<ChatMessage> history) {
        return prepareRequest(systemPrompt, userMessage, history)
                .stream()
                .chatResponse()
                .doOnError(error -> log.error("AI流式调用失败", error));
    }



    /**
     * 验证用户输入的合规性
     */
    private Mono<ComplianceEngine.ComplianceCheckResult> validateUserInput(String userInput, Context context) {
        return complianceEngine.checkInputCompliance(userInput, context);
    }


    /**
     * 处理管道结果
     */
    public record ProcessingPipelineResult(
            boolean isDirectResponse,
            String response,
            String systemPrompt,
            String modifiedInput,
            String intentName,
            ProcessingStrategy strategy,
            List<String> appliedGuidelines
    ) {

        public enum ProcessingStrategy {
            DIRECT_RESPONSE,    // 直接响应
            LLM_ENHANCED,       // LLM增强处理
            LLM_TRADITIONAL    // 传统LLM处理
        }

        public static ProcessingPipelineResult directResponse(String response, String intent, String originalInput, List<String> appliedGuidelines) {
            return new ProcessingPipelineResult(true, response, null, originalInput, intent, ProcessingStrategy.DIRECT_RESPONSE, appliedGuidelines);
        }

        public static ProcessingPipelineResult llmEnhanced(String systemPrompt, String modifiedInput, String intent, List<String> appliedGuidelines) {
            return new ProcessingPipelineResult(false, null, systemPrompt, modifiedInput, intent, ProcessingStrategy.LLM_ENHANCED, appliedGuidelines);
        }

        public static ProcessingPipelineResult llmTraditional(String systemPrompt, String modifiedInput, String intent, List<String> appliedGuidelines) {
            return new ProcessingPipelineResult(false, null, systemPrompt, modifiedInput, intent, ProcessingStrategy.LLM_TRADITIONAL, appliedGuidelines);
        }
    }



}