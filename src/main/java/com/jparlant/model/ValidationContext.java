package com.jparlant.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

@Getter
@AllArgsConstructor
public class ValidationContext {

    private final AgentFlow.FlowStep step;
    private final FlowContext flowContext;

    public Map<String, Object> getConfig() { return step.validation(); }
    public Map<String, Object> getEntities() { return flowContext.getEntities(); }
    public Map<String, Object> getExpectedInputs() { return step.expectedInputs(); }

}
