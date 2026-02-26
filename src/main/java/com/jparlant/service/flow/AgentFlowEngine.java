package com.jparlant.service.flow;

import com.jparlant.constant.ContextKeys;
import com.jparlant.enums.Complexity;
import com.jparlant.enums.Emotion;
import com.jparlant.model.*;
import com.jparlant.service.flow.handler.StepHandler;
import com.jparlant.service.flow.handler.action.ActionDispatcher;
import com.jparlant.service.session.SessionStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent流程引擎
 */
@RequiredArgsConstructor
@Slf4j
public class AgentFlowEngine {


    private final SessionStateManager sessionStateManager;
    private final ActionDispatcher actionDispatcher;


    // 自动注入所有处理器
    private Map<AgentFlow.FlowStep.StepType, StepHandler> stepHandlers;
    @Autowired
    public void setStepHandlers(List<StepHandler> handlers) {
        this.stepHandlers = handlers.stream().collect(Collectors.toMap(StepHandler::getType, h -> h));
    }


    private static final int MAX_RECURSIVE_DEPTH = 5; // 防环保护


    /**
     * 执行工作流导航与引导逻辑。
     * 该方法是意图识别后的核心调度者：
     * 1. 结合当前会话状态（SessionState）与大模型分析出的意图结果（IntentAnalysisResult）。
     * 2. 匹配或加载对应的业务流程定义（AgentFlow）。
     * 3. 驱动执行引擎计算当前应处的步骤、校验跳跃合法性，并生成下一步的引导指令。
     *
     * @param context           包含 AgentID、SessionID 等元数据的上下文
     * @param intentResult      意图分析阶段产出的结构化结果（包含匹配意图、提取实体等）
     * @return 包含下一步引导话术、动作指令及状态变更建议的 FlowGuidanceResult
     */
    public Mono<FlowGuidanceResult> navigateWorkflowStep(Context context, IntentAnalysisResult intentResult) {
        String userInput = intentResult.userInput();
        if (!shouldProcessWorkflow(intentResult)) {
            log.info("流程引擎开始处理，意图分析结果置信度低，userInput={}, sessionId={}", userInput, context.sessionId());
            return Mono.just(FlowGuidanceResult.error("因意图分析结果置信度低，流程引擎处理失败"));
        }

        log.info("流程引擎开始处理: userInput={}, sessionId={}", userInput, context.sessionId());

        return sessionStateManager.getSessionState(context)
            .flatMap(sessionState -> executeIntelligentGuidance(context, intentResult, sessionState))
            .onErrorResume(error -> {
                log.error("Agent工作流引擎处理失败", error);
                return Mono.just(FlowGuidanceResult.error("Agent工作流引擎处理失败: " + error.getMessage()));
            });
    }


    /**
     * 判断意图分析结果是否能够驱动工作流执行
     */
    private boolean shouldProcessWorkflow(IntentAnalysisResult intentResult) {
        if (intentResult == null) {
            return false;
        }

        // 逻辑：
        // 1. primaryIntentId 必须存在且有效 (>0)
        // 2. 置信度必须大于等于 0
        boolean hasValidIntent = intentResult.primaryIntentId() != null && intentResult.primaryIntentId() > 0;
        boolean isConfident = intentResult.confidence() >= 0.0;

        return hasValidIntent && isConfident;
    }


    /**
     * 流程引擎中枢
     */
    @SuppressWarnings("unchecked")
    private Mono<FlowGuidanceResult> executeIntelligentGuidance(Context context, IntentAnalysisResult intentResult, SessionState sessionState) {

        AgentFlow agentFlow = intentResult.activeFlow();
        String userInput = intentResult.userInput();

        // 1. 流程上下文更新信息
        FlowContext flowContext = FlowContext.fromMap(sessionState.getVariable(ContextKeys.Session.FLOW_CONTEXT, Map.class));
        flowContext.updateFromIntent(intentResult, userInput);

        // 2. 确定初始步骤 ID
        // 首先从 Session 获取已有的步骤 ID
        Long determinedStartId = sessionState.getVariable(ContextKeys.Session.CURRENT_FLOW_STEP, Long.class);
        // 情况 A：如果是第一次进入流程（Session 中无 ID），则计算流程定义的第一步
        if (determinedStartId == null) {
            determinedStartId = agentFlow.getInitialStepForFlow();

            if (determinedStartId == null) {
                log.error("流程引擎处理失败，流程配置异常：AgentId={} 的流程无步骤定义", agentFlow.agentId());
                return Mono.just(FlowGuidanceResult.error("流程引擎处理失败，流程配置异常"));
            }
            log.info("流程引擎处理，检测到新流程启动，初始化第一步步骤 ID: {}", determinedStartId);
        }

        // 情况 B：判断大模型是否识别出“跳步”（无论是第一步还是后续步骤，跳步建议权重最高）
        if (intentResult.stepJump() != null && intentResult.stepJump().isJump()) {
            Long jumpTargetId = intentResult.stepJump().targetStepId();
            log.info("流程引擎处理，检测到意图跳跃/抢答，步骤 ID 从 {} 修正至 {}", determinedStartId, jumpTargetId);
            determinedStartId = jumpTargetId;
        }

        log.info("流程引擎开始执行步骤处理，sessionId={}, 当前的步骤ID={}", context.sessionId(), determinedStartId);

        // 3. 将确定的步骤 ID 持久化到 SessionState 中，并启动递归，逻辑：如果是静默步骤(ACTION/ROUTER)，处理完后自动进入下一步，直到需要用户输入(INPUT/CONFIRM)
        final Long finalStartId = determinedStartId;

        Map<String, Object> updates = new HashMap<>();
        updates.put(ContextKeys.Session.CURRENT_FLOW_STEP, finalStartId);
        updates.put(ContextKeys.Session.FLOW_CONTEXT, flowContext.toMap());

        // 执行批量更新sessionState，确保状态完全同步后再进入递归流程
        return sessionStateManager.setVariables(context, updates)
                .flatMap(updatedSessionState -> {
                    log.info("流程引擎开始执行步骤处理，Session 状态更新完成，准备递归处理, sessionId={}", sessionState.sessionId());

                    // 注意：这里传入的是 updatedSessionState，确保后续逻辑拿到的是最新快照
                    return recursiveProcess(context, intentResult, updatedSessionState, 0);
        });

    }

    /**
     * 处理当前步骤，寻找下一个步骤
     * 如果存在跳步，则当前步骤是跳步的那个步骤
     */
    private Mono<FlowGuidanceResult> recursiveProcess(Context context, IntentAnalysisResult intent, SessionState sessionState, int depth) {

        AgentFlow agentFlow = intent.activeFlow();

        // 1. 递归深度安全检查
        if (depth > MAX_RECURSIVE_DEPTH) {
            log.error("流程流转深度超限，可能存在环路: agentId={}，intentId={}", agentFlow.agentId(), intent.primaryIntentId());
            return Mono.just(FlowGuidanceResult.error("系统处理超时，请稍后重试"));
        }

        // 2. 从 SessionState 中获取流程是否结束的标志
        Boolean isCompleted = sessionState.getVariable(ContextKeys.Session.FLOW_COMPLETE, Boolean.class);
        if (null != isCompleted && isCompleted) {
            log.info("流程流转结束: sessionId={}", context.sessionId());
            return Mono.just(FlowGuidanceResult.completed("流程已结束"));
        }

        // 获取当前步骤
        Long currentStepId = sessionState.getVariable(ContextKeys.Session.CURRENT_FLOW_STEP, Long.class);
        if(null == currentStepId){
            log.error("流程处理失败，丢失了当前步骤: agentId={}，intentId={}", agentFlow.agentId(), intent.primaryIntentId());
            return Mono.just(FlowGuidanceResult.error("请稍后重试"));
        }
        AgentFlow.FlowStep currentStep = agentFlow.findStep(currentStepId);

        log.info("递归处理步骤: [{}]{}, 深度: {}", currentStep.type(), currentStep.name(), depth);

        // A. 执行当前步骤处理器
        return processStepWithIntent(context, intent, sessionState, currentStep)
                .flatMap(stepResult -> {

                    // B. 决策下一步
                    return determineNextAction(context, intent, agentFlow, currentStep, stepResult)
                            .flatMap(guidance -> {
                                // C. 自动流转判断，如果是静默步骤（ACTION/ROUTER）且成功指向了下一步，递归执行
                                if (guidance.hasNextStep() && isQuietStep(guidance.nextStep().type())) {
                                    Long nextStepId = guidance.nextStep().stepId();
                                    log.info("静默步骤 [{}] 执行完成，准备自动流转至: {}", currentStep.name(), nextStepId);

                                    return sessionStateManager.getSessionState(context)
                                            .flatMap(updatedState -> recursiveProcess(context, intent, updatedState, depth + 1));
                                }

                                // D. 如果是交互型步骤(INPUT/CONFIRM)，或者流程结束，则返回结果给用户
                                return Mono.just(guidance);
                            });
                })
                .onErrorResume(e -> {
                    log.error("工作流递归执行异常: sessionId={}, stepId={}", context.sessionId(), currentStepId, e);
                    return Mono.just(FlowGuidanceResult.error("系统处理异常"));
                });
    }


    /**
     * 当前步骤处理
     */
    private Mono<FlowProcessingResult> processStepWithIntent(Context context, IntentAnalysisResult intent, SessionState sessionState, AgentFlow.FlowStep step) {

        // 寻找对应的策略处理器
        StepHandler handler = stepHandlers.get(step.type());
        if (handler == null) {
            log.info("未找到步骤类型 {}处理器", step.type());
            return Mono.just(FlowProcessingResult.fail("未找到步骤类型处理器"));
        }

        log.debug("开始执行步骤逻辑: [{}], 类型: [{}]", step.name(), step.type());

        // 2. 直接执行核心 Handler 逻辑（例如：INPUT 的校验、ACTION 的业务逻辑执行等）
        return handler.handle(step, intent, sessionState, context)
                .doOnNext(result -> {
                    if (result.success()) {
                        log.info("步骤 [{}] 执行成功", step.name());
                    } else {
                        log.warn("步骤 [{}] 执行未通过", step.name());
                    }
                })
                .onErrorResume(e -> {
                    log.error("步骤 [{}] 执行过程中发生异常", step.name(), e);
                    return Mono.just(FlowProcessingResult.fail("步骤执行异常: " + e.getMessage()));
                });

    }


    /**
     * 决策模型下一步的操作
     */
    @SuppressWarnings("unchecked")
    private Mono<FlowGuidanceResult> determineNextAction(Context context, IntentAnalysisResult intentResult,
                                                         AgentFlow agentFlow, AgentFlow.FlowStep currentStep, FlowProcessingResult stepResult) {

        return sessionStateManager.getSessionState(context)
                .flatMap(sessionState -> {
                    // 1. 异常干预
                    return handleSpecialCase(context, intentResult)
                            .switchIfEmpty(Mono.defer(() -> {

                                // 2. 步骤未完成：保持当前步骤，使用当前步的 prompt 引导用户补充
                                if (!stepResult.success()) {
                                    log.info("步骤 [{}] 校验未通过，原因: {}", currentStep.name(), stepResult.error());
                                    String errorPart = StringUtils.hasText(stepResult.error())
                                            ? stepResult.error()
                                            : String.format("当前处于【%s】环节，请引导用户提供相关信息。", currentStep.name());

                                    // 2. 获取步骤定义的原生引导语
                                    String originalPrompt = StringUtils.hasText(currentStep.prompt()) ? currentStep.prompt() : "";

                                    // 3. 组合成强化后的 Prompt
                                    // 格式：[错误原因] + [请引导用户：原生引导语]
                                    String enhancedPrompt = String.format("%s\n请再次引导用户：%s", errorPart, originalPrompt);

                                    return Mono.just(FlowGuidanceResult.stay(
                                            enhancedPrompt,
                                            currentStep
                                    ));
                                }

                                // 3. 确定下一步定义
                                FlowContext flowContext = FlowContext.fromMap(sessionState.getVariable(ContextKeys.Session.FLOW_CONTEXT, Map.class));
                                AgentFlow.FlowStep nextStep = agentFlow.getNextStep(currentStep.stepId(), flowContext);

                                // 4. 流程自然结束
                                if (nextStep == null) {
                                    log.info("流程办理完成: sessionId={}", context.sessionId());
                                    return completeFlow(context);
                                }

                                return sessionStateManager.setVariable(context.sessionId(), ContextKeys.Session.CURRENT_FLOW_STEP, nextStep.stepId())
                                        .then(Mono.defer(() -> {
                                            log.info("Session 状态已更新，当前步骤流转至: {}", nextStep.stepId());
                                            // 5. 自动阶段转换 (Phase Transition)
                                            if (nextStep.belongToPhase() != null && nextStep.belongToPhase() != sessionState.phase()) {
                                                return handlePhaseTransition(context, agentFlow, nextStep.belongToPhase(), nextStep.stepId());
                                            }

                                            // 6. 正常流转：返回下一步的 prompt 指令
                                            return Mono.just(FlowGuidanceResult.continue_(
                                                    nextStep.prompt(),
                                                    nextStep
                                            ));
                                        }));
                            }));
        });
    }


    /**
     * 处理人工介入的情况
     */
    private Mono<FlowGuidanceResult> handleSpecialCase(Context context, IntentAnalysisResult intentResult) {

        // 判定条件：负面情绪、紧急情况 或 复杂度过高
//        boolean isNegative = intentResult.emotion() == Emotion.NEGATIVE;
        // todo 后续优化，针对人工介入的方式做成更加可以控制的
        boolean isNegative = false;
        boolean isUrgent = intentResult.emotion() == Emotion.URGENT;
        boolean isComplex = intentResult.complexity() == Complexity.COMPLEX;

        if (isNegative || isUrgent || isComplex) {
            log.warn("检测到特殊情况，准备转人工: sessionId={}, emotion={}, complexity={}", context.sessionId(), intentResult.emotion(), intentResult.complexity());

            // 1. 自动将阶段切换为 HANDOVER（人工接入）
            return sessionStateManager.updatePhase(context, SessionState.SessionPhase.HANDOVER)
                    .map(updatedState -> {
                        // 构造给大模型的 Prompt 指令（guidanceMessage）
                        String prompt = switch (intentResult.emotion()) {
                            case NEGATIVE -> "检测到用户情绪愤怒或不满。请用极其诚恳的语气表达歉意，并告知用户由于问题较为棘手，您正在为您转接高级人工专家处理，请稍后。";
                            case URGENT   -> "检测到用户情况非常紧急。请告知用户您已识别到事情的紧迫性，为了最快速度解决问题，您正立即联系人工专席进行优先处理。";
                            default       -> "检测到用户输入的内容非常复杂。请告知用户为了确保信息处理的准确性，您已邀请人工专家介入协助，请稍后。";
                        };

                        // 返回结果，标识当前流程中断，进入人工阶段
                        return FlowGuidanceResult.error(prompt);
                    });
        }

        // 如果没有任何特殊情况，返回 empty，不拦截后续逻辑
        return Mono.empty();
    }


    /**
     * 是否是静默步骤
     */
    private boolean isQuietStep(AgentFlow.FlowStep.StepType type) {
        return type == AgentFlow.FlowStep.StepType.ACTION;
    }




    /**
     * 处理阶段转换逻辑
     * 作用：当流程流转到下一个阶段时，同步更新 Redis 中的阶段状态和步骤 ID
     */
    private Mono<FlowGuidanceResult> handlePhaseTransition(Context context, AgentFlow agentFlow, SessionState.SessionPhase targetPhase, Long targetStepId) {

        log.info("触发流程阶段转换: sessionId={}, 目标阶段={}, 目标步骤ID={}", context.sessionId(), targetPhase, targetStepId);

        // 1. 确定目标阶段的入口步骤 ID
        if (targetStepId == null) {
            // 如果未指定具体步骤 ID，则从流程定义中寻找属于目标阶段的第一个的步骤
            targetStepId = agentFlow.getInitialStepForPhase(targetPhase);
        }

        // 容错处理：如果目标阶段没有任何步骤定义
        if (targetStepId == null) {
            log.error("阶段转换异常: 在意图 [{},{}] 的阶段 [{}] 中未找到任何步骤定义", agentFlow.name(), agentFlow.intentId(), targetPhase);
            return Mono.just(FlowGuidanceResult.error("流程配置异常：目标阶段起始步骤缺失"));
        }

        // 3. 链式执行持久化操作
        // 第一步：更新步骤 ID
        Long finalStepId = targetStepId;
        return sessionStateManager.setVariable(context, ContextKeys.Session.CURRENT_FLOW_STEP, finalStepId)
                // 第二步：更新会话阶段 (Phase)
                .then(sessionStateManager.updatePhase(context, targetPhase))
                .map(updatedState -> {
                    // 4. 获取新步骤的详细定义
                    AgentFlow.FlowStep newStep = agentFlow.findStep(finalStepId);

                    log.info("阶段转换成功: sessionId={}, 现处于阶段={}, 现处于的步骤={}", context.sessionId(), targetPhase, newStep.name());

                    // 5. 返回流转结果
                    // message 传入 newStep.prompt() 作为大模型的引导指令
                    return FlowGuidanceResult.continue_(
                            newStep.prompt(),
                            newStep
                    );
                });
    }




    private Mono<FlowGuidanceResult> completeFlow(Context context) {
        return sessionStateManager.setVariable(context, ContextKeys.Session.FLOW_COMPLETE, true)
            .flatMap(state -> sessionStateManager.updatePhase(context, SessionState.SessionPhase.CLOSING))
            .map(state -> FlowGuidanceResult.completed("""
                    指令：当前业务流程已成功办理完成。
                    要求：请以亲切礼貌的语气告知用户业务处理完毕的结果，并询问用户是否还有其他需要咨询或办理的事项。
                    """
            ));
    }
}