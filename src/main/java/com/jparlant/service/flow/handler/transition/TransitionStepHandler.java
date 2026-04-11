package com.jparlant.service.flow.handler.transition;

import com.jparlant.model.*;
import com.jparlant.service.flow.handler.StepHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class TransitionStepHandler implements StepHandler {
    @Override
    public AgentFlow.FlowStep.StepType getType() {
        return AgentFlow.FlowStep.StepType.TRANSITION;
    }

    @Override
    public Mono<FlowProcessingResult> handle(AgentFlow.FlowStep step, IntentAnalysisResult intent, SessionState sessionState, Context context) {
        return Mono.just(FlowProcessingResult.ok());
    }
}
