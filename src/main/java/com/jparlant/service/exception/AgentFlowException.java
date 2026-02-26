package com.jparlant.service.exception;

/**
 * 流程执行异常：当流程引擎（FlowEngine）在计算步骤、验证或跳转时发生逻辑错误时抛出
 */
public class AgentFlowException extends RuntimeException {
    public AgentFlowException(String message) {
        super(message);
    }

    public AgentFlowException(String message, Throwable cause) {
        super(message, cause);
    }
}
