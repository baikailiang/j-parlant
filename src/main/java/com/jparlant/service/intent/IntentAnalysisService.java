package com.jparlant.service.intent;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jparlant.constant.ContextKeys;
import com.jparlant.enums.Complexity;
import com.jparlant.enums.Emotion;
import com.jparlant.model.*;
import com.jparlant.service.flow.AgentFlowManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 意图分析服务 - 使用大模型分析用户意图
 * 类似于Parlant框架的Intent Recognition
 */
@RequiredArgsConstructor
@Slf4j
public class IntentAnalysisService {

    // todo 暂时用这个，后面做成可配置的
    private final ChatClient chatClient;
    private final AgentFlowManager agentFlowManager;
    private final ObjectMapper objectMapper;


    // 置信度阈值：低于此值认为局部匹配失败，需要扩大范围
    private static final double CONFIDENCE_THRESHOLD = 0.7;

    /**
     * 分析用户意图
     * @param userInput 用户输入
     * @param context 会话上下文
     * @param sessionState 会话状态
     * @param agentInstructions Agent指令
     * @return 意图分析结果
     */
    @SuppressWarnings("unchecked")
    public Mono<IntentAnalysisResult> analyzeIntent(String userInput, Context context, SessionState sessionState, String agentInstructions) {
        log.info("开始意图步骤分析: userInput={}， sessionId={}", userInput, context.sessionId());

        // 1. 基础校验：防止 context 或 agentId 为空
        if (context.agentId() == null) {
            log.info("用户内容【{}】没有识别到对应的Agent", userInput);
            return Mono.just(IntentAnalysisResult.fallback(userInput, null));
        }

        // 处理空消息情况
        boolean isBlankInput = !StringUtils.hasText(userInput);
        // 如果用户没说话，但当前有正在进行的步骤
        Long currentStepId = sessionState.getVariable(ContextKeys.Session.CURRENT_FLOW_STEP, Long.class);
        if (isBlankInput && currentStepId != null) {
            log.info("用户输入为空（可能是图片上传），尝试维持当前意图步骤: {}", currentStepId);
            // 我们依然继续往下走，但在 Prompt 里会给 LLM 明确暗示
        }

        // 获取该Agent下的所有意图和对应的步骤
        return agentFlowManager.getAgentFlowsByAgentId(context.agentId())
                .collectList()
                .flatMap(allFlows -> {

                    // 2. 校验配置：如果一个流程都没有，直接返回未知
                    if (allFlows == null || allFlows.isEmpty()) {
                        log.info("该Agent未配置任何流程: agentId={}", context.agentId());
                        return Mono.just(IntentAnalysisResult.fallback(userInput, null));
                    }

                    // 查找活跃意图
                    AgentFlow activeFlow = allFlows.stream()
                            .filter(f -> currentStepId != null && f.findStep(currentStepId) != null)
                            .findFirst()
                            .orElse(null);
                    log.info("sessionId:{}, 当前正在进行中的意图为：{}", sessionState.sessionId(), activeFlow);
                    AgentFlow.FlowStep currentStep = (currentStepId != null && activeFlow != null) ? activeFlow.findStep(currentStepId) : null;

                    // 计算预期下一步骤的显示文本
                    String expectedNextStepDisplay;
                    if (activeFlow != null) {
                        FlowContext flowContext = FlowContext.fromMap(sessionState.getVariable(ContextKeys.Session.FLOW_CONTEXT, Map.class));
                        AgentFlow.FlowStep nextStep = activeFlow.getNextStep(currentStepId, flowContext);
                        expectedNextStepDisplay = (nextStep != null)
                                ? String.format("%s (ID: %d)", nextStep.name(), nextStep.stepId())
                                : "当前流程所有步骤已执行完毕";
                    } else {
                        // 如果没有活跃意图，说明是新会话或刚结束旧流程
                        // 告知 AI：当前的预期是开启任意意图的第一步
                        expectedNextStepDisplay = "开启新意图的第一步";
                    }

                    // 情况：用户仅上传图片/文件（无文字），且当前处于某个意图流程中
                    if (isBlankInput && activeFlow != null) {
                        log.info("检测到空输入且存在活跃意图 [{}], 仅执行 Tier 1 意图内聚焦扫描", activeFlow.name());
                        return performLlmCall(userInput, sessionState, agentInstructions, List.of(activeFlow), currentStep, activeFlow, "INTENT_FOCUS", expectedNextStepDisplay)
                                .flatMap(res1 -> verifyAndBuildResult(res1, currentStepId, sessionState))
                                .switchIfEmpty(Mono.defer(() -> {
                                    // 如果 LLM 没识别出实体（很正常，因为没文字），强制留守当前意图
                                    log.info("空输入 Tier 1 未识别到内容，强制留在当前意图: {}", activeFlow.name());
                                    return Mono.just(createStayResult(activeFlow, currentStepId, "[图片/文件上传]"));
                                }));
                    }

                    // --- 标准逻辑（有文字输入的情况） ---
                    // --- 第一层：当前意图深度扫描---
                    Mono<IntentAnalysisResult> processChain = Mono.empty();
                    if (activeFlow != null) {
                        log.info("先在Tier 1 当前意图内进行识别，userInput={}, sessionId:{}", userInput, sessionState.sessionId());
                        // 如果存在活跃意图，尝试在当前意图内收敛识别
                        processChain = performLlmCall(userInput, sessionState, agentInstructions, List.of(activeFlow), currentStep, activeFlow, "INTENT_FOCUS", expectedNextStepDisplay)
                                .flatMap(res1 -> {
                                    // 只有在本意图内匹配成功，且校验通过，才返回结果
                                    if (isValid(res1, activeFlow)) {
                                        log.info("Tier 1 识别成功，sessionId:{}, 识别结果为:{}", sessionState.sessionId(), res1);
                                        return verifyAndBuildResult(res1, currentStepId, sessionState);
                                    }
                                    // 如果识别结果不是当前意图，或置信度低，返回 empty 以触发下面的 switchIfEmpty
                                    log.info("Tier 1 识别结果不佳或意图偏离，准备进入 Tier 2");
                                    return Mono.empty();
                                });
                    }

                    // 如果 Tier 1 返回空（没匹配上）或直接就没有 activeFlow，则执行 Tier 2
                    processChain = processChain.switchIfEmpty(Mono.defer(() -> {
                                log.info("触发 Tier 2 全量意图扫描: userInput={}, sessionId:{}", userInput, sessionState.sessionId());
                                return performLlmCall(userInput, sessionState, agentInstructions, allFlows, currentStep, activeFlow, "GLOBAL_SWITCH", expectedNextStepDisplay)
                                        .flatMap(res2 -> verifyAndBuildResult(res2, currentStepId, sessionState));
                            }));

                    return processChain.switchIfEmpty(Mono.defer(() -> {
                        // 如果 Tier 2 也没匹配上，但用户处于活跃步骤中
                        if (activeFlow != null) {
                            log.info("全量意图匹配失败，用户处于意图 [{}] 的步骤 [{}] 中，强制拦截并留在当前步", activeFlow.name(), currentStepId);

                            // 人为构建一个 Result，保持意图 ID 不变，但置信度设为 0
                            return Mono.just(IntentAnalysisResult.builder()
                                    .primaryIntentId(activeFlow.intentId())
                                    .primaryIntentName(activeFlow.name())
                                    .confidence(0.0f) // 标志为未识别，触发下游的 stay 逻辑
                                    .emotion(Emotion.NEUTRAL)
                                    .complexity(Complexity.SIMPLE)
                                    .extractedEntities(new HashMap<>()) // 清空提取内容
                                    .userInput(userInput)
                                    .stepJump(new StepJumpDetection(-1L, false, "全量扫描失败，强制守住当前流程"))
                                    .activeFlow(activeFlow)
                                    .build());
                        }

                        // 只有完全不在流程中，才执行最终 fallback
                        log.info("用户不在流程中且匹配失败，返回全量兜底回答");
                        return Mono.just(IntentAnalysisResult.fallback(userInput, null));
                    }));
                });

    }


    private IntentAnalysisResult createStayResult(AgentFlow activeFlow, Long currentStepId, String userInput) {
        return IntentAnalysisResult.builder()
                .primaryIntentId(activeFlow.intentId())
                .primaryIntentName(activeFlow.name())
                .confidence(1.0f) // 强制满分，确保不触发 fallback
                .emotion(Emotion.NEUTRAL)
                .complexity(Complexity.SIMPLE)
                .extractedEntities(new HashMap<>())
                .userInput(userInput)
                .stepJump(new StepJumpDetection(-1L, false, "空消息默认留在当前步骤"))
                .activeFlow(activeFlow)
                .build();
    }


    /**
     * 校验意图是否有效，是否是同意图
     * @param res
     * @param activeFlow
     * @return
     */
    private boolean isValid(IntentAnalysisResult res, AgentFlow activeFlow) {
        return res != null && res.confidence() >= CONFIDENCE_THRESHOLD
                && null != res.primaryIntentId() && res.primaryIntentId().equals(activeFlow.intentId())
                && StringUtils.hasText(res.primaryIntentName()) && !"UNKNOWN".equals(res.primaryIntentName());
    }


    /**
     * 校验 LLM 建议的跳步是否合法
     */
    @SuppressWarnings("unchecked")
    private Mono<IntentAnalysisResult> verifyAndBuildResult(IntentAnalysisResult res, Long currentStepId, SessionState sessionState) {
        if (res.activeFlow() == null || !res.stepJump().isJump()) {
            return Mono.just(res);
        }
        Long targetStepId = res.stepJump().targetStepId();

        // 如果 currentStepId 为 null，说明是新意图首句输入，逻辑起始点应为该意图的第一步
        Long effectiveStepId = currentStepId;
        if (effectiveStepId == null) {
            effectiveStepId = res.activeFlow().getInitialStepForFlow(); // 使用拓扑算法
            if (effectiveStepId == null) {
                log.warn("意图 [{}] 未配置任何步骤，无法执行跳转校验", res.activeFlow().name());
                return Mono.just(res.withJump(false, null, "流程配置异常：无步骤定义"));
            }
        }

        // 特殊情况处理：如果 LLM 建议跳转的目标就是“逻辑起始步”本身，则不视为跳转
        if (targetStepId.equals(effectiveStepId)) {
            return Mono.just(res.withJump(false, effectiveStepId, "目标步骤即为当前起始步，无需跳转"));
        }


        // 调用 AgentFlow 里的 validateStepJump 方法
        FlowContext flowContext = FlowContext.fromMap(sessionState.getVariable(ContextKeys.Session.FLOW_CONTEXT, Map.class));
        AgentFlow.StepJumpValidation validation = res.activeFlow().validateStepJump(currentStepId, targetStepId, flowContext);

        if (!validation.isValid()) {
            log.info("LLM 建议跳步至 {} 但校验失败: {}", targetStepId, validation.reason());
            // 校验失败则取消跳步，保留实体提取结果，由业务层决定下一步
            return Mono.just(res.withJump(false, currentStepId, "校验未通过：" + validation.reason()));
        }

        log.info("跳步校验通过: {} -> {}", effectiveStepId, targetStepId);
        return Mono.just(res);
    }


    /**
     * 执行大模型调用
     * @param userInput
     * @param sessionState
     * @param agentInstructions
     * @param flows
     * @param currentStep
     * @param activeFlow
     * @param strategy
     * @return
     */
    private Mono<IntentAnalysisResult> performLlmCall(String userInput, SessionState sessionState, String agentInstructions,
                                                      List<AgentFlow> flows, AgentFlow.FlowStep currentStep, AgentFlow activeFlow,
                                                      String strategy, String expectedNextStepDisplay) {
        // 1. 前置校验：如果没有待匹配的流量，直接返回 empty 触发 switchIfEmpty
        if (flows == null || flows.isEmpty()) {
            log.info("执行策略 [{}] 时流程列表为空，跳过此层识别", strategy);
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
                    // 构建 Prompt
                    String prompt = buildProfessionalPrompt(userInput, sessionState, flows, currentStep, activeFlow, strategy, expectedNextStepDisplay);
                    log.info("开始 LLM 意图识别调用, 策略: {}", strategy);

                    // 阻塞调用 Spring AI 客户端
                    return chatClient.prompt().user(prompt).call().content();
                })
                .subscribeOn(Schedulers.boundedElastic()) // 在弹性线程池执行阻塞 IO
                .flatMap(raw -> {
                    // 2. 如果 LLM 返回内容为空，返回 empty
                    if (StrUtil.isBlank(raw)) {
                        log.info("LLM 意图识别响应内容为空, 策略: {}", strategy);
                        return Mono.empty();
                    }

                    try {
                        // 3. 解析并校验
                        IntentAnalysisResult result = parseAndValidate(raw, userInput, flows);

                        // 如果未识别，返回 empty 以便触发 switchIfEmpty 逻辑
                        if (result == null || result.primaryIntentId() == -1) {
                            log.info("策略 [{}] 未能有效识别意图，准备切换策略", strategy);
                            return Mono.empty();
                        }

                        return Mono.just(result);
                    } catch (Exception e) {
                        log.error("解析 LLM 意图识别结果异常, 策略: {}, 错误: {}", strategy, e.getMessage());
                        return Mono.empty();
                    }
                })
                // 4. 核心优化：异常捕获。onErrorResume 才能返回信号 Mono.empty()
                .onErrorResume(e -> {
                    log.error("LLM 意图识别调用链路发生硬件或网络异常 [策略: {}]: {}", strategy, e.getMessage());
                    // 返回空信号，下游的 .switchIfEmpty(tier2Process) 才能被触发
                    return Mono.empty();
                });
    }


    /**
     * 构建意图分析prompt
     * @param userInput
     * @param sessionState
     * @param flows
     * @param currentStep
     * @param strategy
     * @return
     */
    private String buildProfessionalPrompt(String userInput, SessionState sessionState,
                                           List<AgentFlow> flows, AgentFlow.FlowStep currentStep, AgentFlow activeFlow,
                                           String strategy, String expectedNextStepDisplay) {
        String displayInput = StringUtils.hasText(userInput) ? userInput : "[用户上传了图片/文件，未提供文字说明]";
        // 在构建 flowDocs 时，增加流转关系的描述
        String flowDocs = flows.stream().map(flow -> {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("### 意图名称: %s (ID: %d)\n", flow.name(), flow.intentId()));
            sb.append(String.format("意图描述: %s\n步骤流定义:\n", flow.description()));
            for (AgentFlow.FlowStep step : flow.steps()) {
                String formattedInputs = "无";
                if (step.expectedInputs() != null && !step.expectedInputs().isEmpty()) {
                    try {
                        // 核心修改：使用 ObjectMapper 将嵌套 Map 转为格式化的 JSON 字符串
                        formattedInputs = objectMapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(step.expectedInputs());
                    } catch (Exception e) {
                        formattedInputs = "格式化异常";
                    }
                }

                sb.append(String.format("  - 步骤ID: %d | 名称: %s | 类型: %s\n    描述: %s\n    [期待提取结构]: \n%s\n",
                        step.stepId(), step.name(), step.type(),
                        step.description(), formattedInputs));

                // --- 新增：描述出度分支 ---
                if (CollectionUtil.isNotEmpty(step.outTransitions())) {
                    sb.append("    [逻辑流转/决策分支]:\n");
                    for (var t : step.outTransitions()) {
                        String branchName = t.branchCode();
                        String conditionText = "";

                        // 如果有具体的逻辑条件，转化为可读性较强的字符串
                        if (t.conditionJson() != null && !t.conditionJson().isEmpty()) {
                            conditionText = " | 逻辑判定: " + t.conditionJson().toString();
                        }

                        if ("DEFAULT".equalsIgnoreCase(branchName)) {
                            sb.append(String.format("      - ➔ 默认路径 (无其他分支匹配时走此路径) -> 跳转到步骤ID: %d\n", t.toStepId()));
                        } else {
                            // 这里是关键：突出 branchCode 的业务含义
                            sb.append(String.format("      - ➔ 决策分支: 【%s】%s -> 跳转到步骤ID: %d\n",
                                    branchName,
                                    conditionText,
                                    t.toStepId()));
                        }
                    }
                }
            }
            return sb.toString();
        }).collect(Collectors.joining("\n"));

        String strategyNote = strategy.equals("INTENT_STAY")
                ? "【意图粘性模式】请优先判断用户是否在回复当前意图的步骤。除非用户明确表达切换意图，否则不要修改 primaryIntentId。"
                : "【全局扫描模式】请在所有定义的意图中寻找最符合用户输入的一项。";

        // 处理辅助标签：仅作为背景参考
        String tagsDisplay = (sessionState.activeTags() != null && !sessionState.activeTags().isEmpty())
                ? String.join(", ", sessionState.activeTags())
                : "无";
        String multimodalRule = """
                10. **空文字输入处理**：如果 <UserInput> 显示为 [用户上传了图片/文件，未提供文字说明]，这通常意味着用户正在响应当前步骤的要求（如上传凭证）。
                    - 除非当前步骤完全不涉及文件/图片，否则必须维持 `primaryIntentId` 为当前 ID。
                    - 如果用户是在完成当前步骤，`stepJump.isJump` 应为 false。
                """;

        return """
                <Task>你是一个高性能智能体意图分析专家。你的任务是将用户输入映射到预定义的业务流程，并提取关键实体。</Task>

                <BusinessFlowDefinitions>
                %s
                </BusinessFlowDefinitions>

                <CurrentContext>
                - 当前执行意图: %s (ID: %s)
                - 当前所处步骤: %s (ID: %s)
                - 预期下一步骤: %s
                - 已有变量: %s
                - 执行策略: %s
                - 补充背景策略: %s
                </CurrentContext>

                <UserInput>
                "%s"
                </UserInput>
                            
                <ResponseFieldDefinitions>
                1. **thinking**: 字符串。必须按以下格式记录推理：
                   - [意图匹配]: 说明用户输入命中了哪个意图下的哪个步骤的“描述”，理由是什么。
                   - [实体映射]: 列出提取到的字段分别属于哪个步骤ID。
                   - [跳转判定]: 基于“后续可选路径”定义的逻辑深度。分析用户提供的信息是否跨越了当前步骤的直接后继，直接进入了更深层的逻辑节点。
                2. **primaryIntentId**: Long。匹配到的意图ID。若无法匹配任何意图，固定返回 -1。
                3. **primaryIntentName**: 字符串。匹配到的意图名称。若无法匹配，返回 "UNKNOWN"。
                4. **confidence**: Float (0.0-1.0)。识别的信心得分。完全匹配为 1.0，无法识别为 0.0。
                5. **emotion**: 枚举值。根据语气判定：NEUTRAL(平和), POSITIVE(喜悦/感激), NEGATIVE(不满/愤怒), URGENT(焦虑/急迫)。
                6. **complexity**: 枚举值。根据输入内容判定：SIMPLE(仅包含单一信息), MEDIUM(包含两到三项信息), COMPLEX(信息密集且逻辑复杂)。
                7. **extractedEntities**: Map对象。
                   - 提取逻辑：请参考各步骤定义的 [期待提取结构]。
                   - 格式要求：必须严格保持嵌套层级。例如，如果定义了 "loan_request": { "amount": "..." }，则返回的 JSON 必须包含 {"loan_request": {"amount": 100000}}。
                   - 类型映射：STRING 对应字符串，NUMBER 对应数字，BOOLEAN 对应 true/false。
                8. **stepJump**: 对象。
                   - **isJump**: Boolean。当用户提供的信息属于当前步骤的“后续可选路径”中更深层的步骤（即跳过了直接后继步骤）时，设为 true。
                   - **targetStepId**: Long。识别到的目标逻辑步骤ID。若用户仅按部就班回答当前/下一逻辑步骤，或识别失败，固定返回 -1。
                   - **reason**: 字符串。基于业务逻辑流转图，简述判定跳转到该特定节点的理由。
                </ResponseFieldDefinitions>

                <CriticalRules>
                1. **意图溯源判定**：判定 `primaryIntentId` 时，必须执行“语义下钻”。如果用户输入的内容符合某个意图下**任意一个步骤的“描述”**，则必须判定为命中了该意图。步骤描述是判断意图归属的最优先级别参考。
                2. **精准实体提取**：对比用户输入与各步骤的 [期待提取结构]。返回的提取结果必须是该结构的完整或部分实例。如果是 ARRAY 类型，请提取为对象数组。
                3. **意图确定**：必须返回 `primaryIntentId` (Long) 和 `primaryIntentName` (String)。
                4. **跳跃检测逻辑**：
                    - **逻辑定义**：若用户提供的信息不属于“预期下一步骤”，而是属于逻辑图下游更远的步骤，则判定为跳转。
                    - **严禁使用 ID 大小判断顺序**：请根据 <BusinessFlowDefinitions> 中的“后续可选路径”分析逻辑深度。
                    - **ACTION 强制约束（最高优先级）**：
                        - **严禁作为目标**：`ACTION` 类型步骤绝对不可作为跳转目标（`targetStepId`）。
                        - **禁止跨越执行**：严禁任何跳过未执行 `ACTION` 步骤的跳转。
                        - **路径重定向**：若用户输入的信息命中了某个 `ACTION` 步骤或其下游步骤，必须将跳转目标 `targetStepId` 设定为触发该动作前最近的一个 `INPUT` 步骤。
                    - **判定规则**：
                        - 若用户提供的信息触发了多个后续步骤的实体提取，在满足上述 ACTION 约束的前提下，取逻辑路径上最远的那个 `INPUT` 步骤作为 `targetStepId`。
                        - 若用户只是在纠正、重复或回答当前步骤/上一步骤的信息，`isJump` 必须为 `false`。
                5. **数据规范**：ID字段必须为数字 Long 型。严禁返回 "无"、"None"、"null" 或空字符串。
                6. **输出约束**：请严格遵守 JSON 格式规范。严禁使用任何全角字符或中文引号。
                7. **严禁注释**：输出的 JSON 内部严禁包含任何形式的注释（如 // 或 /* */）。
                8. **引号规范**：必须使用标准英文半角双引号 (")，严禁使用中文全角引号 (“ ”)。
                9. **纯净输出**：只输出 JSON 块，不要在 JSON 字段内解释原因。
                %s
                </CriticalRules>

                <ResponseFormat>
                {
                    "thinking": "用户正在回答当前步骤的问题，并未提及后续流程，因此不触发跳跃。",
                    "primaryIntentId": 1001,
                    "primaryIntentName": "办理宽带",
                    "confidence": 0.95,
                    "emotion": "NEUTRAL",
                    "complexity": "SIMPLE",
                    "extractedEntities": {
                        "name": "张三"
                    },
                    "stepJump": {
                        "isJump": false,
                        "targetStepId": -1,
                        "reason": "常规按部就班回答，无跳跃发生"
                    }
                }
                </ResponseFormat>
                """.formatted(
                flowDocs,
                activeFlow != null ? activeFlow.name() : "UNKNOWN",
                activeFlow != null ? activeFlow.intentId() : -1,
                currentStep != null ? currentStep.name() : "UNKNOWN",
                currentStep != null ? currentStep.stepId() : -1,
                expectedNextStepDisplay,
                CollectionUtil.isEmpty(sessionState.variables()) ? "暂无" : JSONUtil.toJsonPrettyStr(sessionState.variables()),
                strategyNote,
                tagsDisplay,
                displayInput,
                multimodalRule
        );
    }


    /**
     * 解析llm结果并校验
     */
    private IntentAnalysisResult parseAndValidate(String rawResponse,
                                                  String userInput,
                                                  List<AgentFlow> allowedIntents) {
        if (StrUtil.isBlank(rawResponse)) return null;

        String extractedJson = extractJson(rawResponse);
        if (extractedJson == null) {
            log.error("未提取到JSON结构: {}", rawResponse);
            return null;
        }

        LLMIntentResponse dto = tryParseWithFallback(extractedJson);

        if (dto == null) {
            log.error("JSON解析失败: {}", extractedJson);
            return null;
        }

        // ================== 业务校验 ==================
        if (dto.getConfidence() <= CONFIDENCE_THRESHOLD) {
            return null;
        }

        Long intentId = dto.safeGetPrimaryIntentId();
        if (intentId == -1L || "UNKNOWN".equalsIgnoreCase(dto.getPrimaryIntentName())) {
            return null;
        }

        AgentFlow matchedFlow = allowedIntents.stream()
                .filter(f -> f.intentId().equals(intentId))
                .findFirst()
                .orElse(null);

        if (matchedFlow == null) {
            return null;
        }

        return IntentAnalysisResult.builder()
                .primaryIntentId(matchedFlow.intentId())
                .primaryIntentName(matchedFlow.name())
                .confidence(dto.getConfidence())
                .emotion(safeEnum(dto.getEmotion(), Emotion.class))
                .complexity(safeEnum(dto.getComplexity(), Complexity.class))
                .extractedEntities(dto.getExtractedEntities())
                .userInput(userInput)
                .stepJump(buildStepJump(dto))
                .activeFlow(matchedFlow)
                .build();
    }


    private String extractJson(String text) {
        int start = text.indexOf('{');
        if (start == -1) return null;

        int braceCount = 0;
        boolean inString = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '"' && text.charAt(i - 1) != '\\') {
                inString = !inString;
            }

            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;

                if (braceCount == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }


    private LLMIntentResponse tryParseWithFallback(String json) {

        // 1️⃣ 原始解析（最快）
        try {
            return strictMapper().readValue(json, LLMIntentResponse.class);
        } catch (Exception ignored) {}

        // 2️⃣ 宽松解析（主力）
        try {
            return looseMapper().readValue(json, LLMIntentResponse.class);
        } catch (Exception ignored) {}

        // 3️⃣ 轻清洗后再解析
        try {
            String cleaned = cleanJson(json);
            return looseMapper().readValue(cleaned, LLMIntentResponse.class);
        } catch (Exception ignored) {}

        return null;
    }


    private String cleanJson(String json) {

        // 去注释（支持多行）
        json = json.replaceAll("(?s)/\\*.*?\\*/", "");
        json = json.replaceAll("(?m)//.*$", "");

        // 中文符号
        json = json
                .replace("：", ":")
                .replace("，", ",")
                .replace("“", "\"")
                .replace("”", "\"");

        // 去尾逗号
        json = json.replaceAll(",\\s*([}\\]])", "$1");

        // 不做任何“引号修复”
        return json;
    }


    private <T extends Enum<T>> T safeEnum(String value, Class<T> clazz) {
        if (value == null) return null;
        try {
            return Enum.valueOf(clazz, value);
        } catch (Exception e) {
            return null;
        }
    }

    private ObjectMapper strictMapper() {
        return new ObjectMapper();
    }

    private ObjectMapper looseMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        mapper.enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature());
        mapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        mapper.enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature());
        return mapper;
    }


    private StepJumpDetection buildStepJump(LLMIntentResponse dto) {
        if (dto.getStepJump() == null) {
            return new StepJumpDetection(null, false, null);
        }

        return new StepJumpDetection(
                dto.getStepJump().getTargetStepId(),
                dto.getStepJump().isJump(),
                dto.getStepJump().getReason()
        );
    }


    @Data
    @JsonIgnoreProperties(ignoreUnknown = true) // 忽略 LLM 可能多生成的字段
    private static class LLMIntentResponse {
        private Object primaryIntentId;
        private String primaryIntentName;
        private String thinking;
        private double confidence;
        private String emotion;
        private String complexity;
        private Map<String, Object> extractedEntities;
        private StepJumpDTO stepJump;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class StepJumpDTO {
            private boolean isJump;
            private Long targetStepId;
            private String reason;
        }

        public Long safeGetPrimaryIntentId() {
            if (primaryIntentId == null) return -1L;
            if (primaryIntentId instanceof Number n) return n.longValue();
            try {
                return Long.parseLong(primaryIntentId.toString());
            } catch (Exception e) {
                return -1L;
            }
        }

    }

}