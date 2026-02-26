package com.jparlant.model;

/**
 * Agent流程引导结果
 * 封装流程引导的响应结果
 */
public record FlowGuidanceResult(
        ResultType type,
        String message,                    // 给用户的引导prompt
        AgentFlow.FlowStep currentStep,    // 当前处理的步骤
        AgentFlow.FlowStep nextStep,       // 下一步
        String error                       // 错误信息
) {

    public enum ResultType {
        CONTINUE,    // 继续流程
        COMPLETED,   // 流程完成
        ERROR,       // 发生错误
        STAY
    }

    public static FlowGuidanceResult continue_(String message, AgentFlow.FlowStep nextStep) {
        return new FlowGuidanceResult(ResultType.CONTINUE, message, null, nextStep, null);
    }

    public static FlowGuidanceResult stay(String message, AgentFlow.FlowStep currentStep) {
        return new FlowGuidanceResult(ResultType.STAY, message, currentStep, null, null);
    }

    public static FlowGuidanceResult completed(String message) {
        return new FlowGuidanceResult(ResultType.COMPLETED, message, null, null, null);
    }

    public static FlowGuidanceResult error(String error) {
        return new FlowGuidanceResult(ResultType.ERROR, null, null, null, error);
    }

    public boolean hasNextStep() {
        return type == ResultType.CONTINUE && nextStep != null;
    }

    public boolean hasError(){
        return type == ResultType.ERROR;
    }


}