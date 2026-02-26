package com.jparlant.service.flow.handler.input;

import com.jparlant.constant.ContextKeys;
import com.jparlant.model.*;
import com.jparlant.service.flow.handler.StepHandler;
import com.jparlant.service.flow.handler.action.ActionDispatcher;
import com.jparlant.service.flow.handler.input.validation.FieldValidator;
import com.jparlant.service.session.SessionStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


@Slf4j
@RequiredArgsConstructor
public class InputStepHandler implements StepHandler {


    private final SessionStateManager sessionStateManager;
    private final List<FieldValidator> validators;    // Spring 会自动注入所有实现了 FieldValidator 接口的实现类
    private final ActionDispatcher actionDispatcher;




    @Override public AgentFlow.FlowStep.StepType getType() { return AgentFlow.FlowStep.StepType.INPUT; }


    @SuppressWarnings("unchecked")
    @Override
    public Mono<FlowProcessingResult> handle(AgentFlow.FlowStep step, IntentAnalysisResult intent, SessionState sessionState, Context context) {

        return processOcrIfNecessary(step, intent, sessionState)
                .then(sessionStateManager.getSessionState(context))
                .flatMap(latestSessionState -> {
                    // 1. 获取上下文
                    FlowContext flowContext = FlowContext.fromMap(latestSessionState.getVariable(ContextKeys.Session.FLOW_CONTEXT, Map.class));
                    FlowContext.StepState state = flowContext.getStepState(step.stepId());

                    // 2. 执行多维校验
                    ValidationContext validationContext = new ValidationContext(step, flowContext);
                    List<String> errors = new ArrayList<>();

                    for (FieldValidator validator : validators) {
                        try {
                            errors.addAll(validator.validate(validationContext));
                        } catch (Exception e) {
                            log.error("InputStepHandler Validator {} execution failed", validator.getClass().getSimpleName(), e);
                            errors.add("系统校验异常，请稍后再试");
                        }
                    }

                    // 3. 状态判定
                    boolean isPassed = errors.isEmpty();
                    String formattedErrorGuidance;
                    if (isPassed) {
                        formattedErrorGuidance = "";
                        state.setStatus(FlowContext.StepState.Status.SUCCESS);
                    } else {
                        state.setStatus(FlowContext.StepState.Status.FAIL);
                        // 将合并后的错误信息存入，供后续 Prompt 引导用户修正
                        formattedErrorGuidance = buildPromptErrorGuidance(step.name(), errors);
                    }

                    // 4. 持久化并返回
                    SessionState updatedState = latestSessionState.withVariable(ContextKeys.Session.FLOW_CONTEXT, flowContext.toMap());
                    return sessionStateManager.saveSessionState(updatedState)
                            .map(savedState -> isPassed ? FlowProcessingResult.ok() : FlowProcessingResult.fail(formattedErrorGuidance));
                });

    }


    private String buildPromptErrorGuidance(String stepName, List<String> errors) {
        // 格式化错误项，使用更清晰的数字列表
        String errorItems = IntStream.range(0, errors.size())
                .mapToObj(i -> String.format("%d. %s", i + 1, errors.get(i)))
                .collect(Collectors.joining("\n"));

        // 构建结构化指令，引导模型如何处理这些错误
        return """
        用户在【%s】环节提供的信息不完整或有误。
        需要修正的具体项如下：
        %s
        """.formatted(stepName, errorItems);
    }


    /**
     * 判断并执行 OCR 识别逻辑，并将结果直接持久化到 Redis
     */
    private Mono<Void> processOcrIfNecessary(AgentFlow.FlowStep step, IntentAnalysisResult intent, SessionState sessionState) {
        AgentFlow.FlowStep.ActionCall ocrAction = step.ocrAction();
        List<MultipartFile> files = intent.files();

        // 1. 前置检查
        if (files == null || files.isEmpty() || ocrAction == null) {
            return Mono.empty();
        }

        // 2. 封装业务参数
        Object[] args = new Object[]{ files };

        // 3. 执行逻辑并更新 Redis
        return actionDispatcher.executeWithArgs(ocrAction, sessionState, args)
                .flatMap(updatedSessionState -> {
                    // 这里的 updatedSessionState 是 actionDispatcher 处理完映射后返回的新对象
                    log.debug("OCR 识别逻辑执行完成，正在持久化到 Redis...");

                    // 直接在方法链中调用持久化逻辑
                    return sessionStateManager.saveSessionState(updatedSessionState);
                })
                .doOnSuccess(s -> log.info("OCR 结果已成功提取并持久化到 Redis"))
                .doOnError(e -> log.error("OCR 处理或持久化失败", e))
                .then(); // 返回 Mono<Void> 信号给上层
    }

}
