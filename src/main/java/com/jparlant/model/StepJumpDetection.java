package com.jparlant.model;


/**
 * 步骤跳跃检测结果
 */
public record StepJumpDetection(
        Long targetStepId,           // LLM 认为用户当前直接抵达的步骤Id
        boolean isJump,             // 是否试图跳跃
        String reason               // 跳跃原因

) {
    public static StepJumpDetection none() {
        return new StepJumpDetection(null, false, "");
    }



}