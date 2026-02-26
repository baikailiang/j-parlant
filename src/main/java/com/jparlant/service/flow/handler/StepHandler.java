package com.jparlant.service.flow.handler;

import com.jparlant.model.*;
import reactor.core.publisher.Mono;

public interface StepHandler {

    AgentFlow.FlowStep.StepType getType();

    /**
     * @return 返回 Mono 以支持异步数据库校验或 API 调用
     */
    Mono<FlowProcessingResult> handle(AgentFlow.FlowStep step, IntentAnalysisResult intent, SessionState sessionState, Context context);

}
