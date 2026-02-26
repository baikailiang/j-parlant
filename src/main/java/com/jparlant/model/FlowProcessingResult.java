package com.jparlant.model;

/**
 * 流程处理结果内部流转类
 */
public record FlowProcessingResult(
        boolean success,                      // 处理是否成功
        String error                         // 错误信息
) {

    public static FlowProcessingResult ok() {
        return new FlowProcessingResult(true, null);
    }
    public static FlowProcessingResult fail(String error) {
        return new FlowProcessingResult(false, error);
    }

}
