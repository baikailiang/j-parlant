package com.jparlant.service.flow.handler.action;

import com.jparlant.model.*;
import com.jparlant.service.flow.handler.StepHandler;
import com.jparlant.service.session.SessionStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ActionStepHandler implements StepHandler {


    private final ActionDispatcher actionDispatcher;
    private final SessionStateManager sessionStateManager;


    @Override public AgentFlow.FlowStep.StepType getType() { return AgentFlow.FlowStep.StepType.ACTION; }


    @Override
    public Mono<FlowProcessingResult> handle(AgentFlow.FlowStep step, IntentAnalysisResult intent, SessionState sessionState, Context context) {
        List<AgentFlow.FlowStep.ActionCall> actions = step.coreActions();

        if (actions == null || actions.isEmpty()) {
            return Mono.just(FlowProcessingResult.ok());
        }



        // 从初始的 sessionState 开始
        Mono<SessionState> chain = Mono.just(sessionState);

        // 将所有 Action 串联起来，前一个 Action 的输出 SessionState 作为后一个的输入
        for (AgentFlow.FlowStep.ActionCall action : actions) {
            chain = chain.flatMap(currentState -> actionDispatcher.execute(action, currentState));
        }

        // 最后一步：统一持久化到数据库，并返回结果
        return chain
                .flatMap(sessionStateManager::saveSessionState)
                .thenReturn(FlowProcessingResult.ok())
                .onErrorResume(e -> {
                    log.error("Action 执行失败", e);
                    return Mono.just(FlowProcessingResult.fail("执行异常: " + e.getMessage()));
                });
    }


}
