package com.jparlant.model;

import java.util.List;

public record ChatMessage(
    String role,
    String content,
    Long stepId,
    List<String> appliedGuidelines   // 约束 AI 行为的所有逻辑标签
) {
    public static ChatMessage user(String content, Long stepId) {
        return new ChatMessage("user", content, stepId, List.of());
    }

    public static ChatMessage assistant(String content, List<String> appliedGuidelines) {
        return new ChatMessage("assistant", content, null, appliedGuidelines);
    }

    public static ChatMessage assistant(String content, Long stepId, List<String> appliedGuidelines) {
        return new ChatMessage("assistant", content, stepId, appliedGuidelines);
    }
    
    public static ChatMessage system(String content, Long stepId) {
        return new ChatMessage("system", content, stepId, List.of());
    }

    public boolean isUser() {
        return role.equals("user");
    }

    public boolean isAI() {
        return role.equals("assistant");
    }
}