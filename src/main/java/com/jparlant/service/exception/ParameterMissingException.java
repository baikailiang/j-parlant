package com.jparlant.service.exception;

/**
 * 参数缺失异常：当请求中缺少必要的业务参数（如 userId, message 等）时抛出
 */
public class ParameterMissingException extends RuntimeException {

    public ParameterMissingException(String message) {
        super(message);
    }

    public ParameterMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}
