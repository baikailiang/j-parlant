package com.jparlant.model;

import java.util.List;
import java.util.Map;

public record Context(
    String sessionId,
    String userId,
    Long agentId,
    List<String> tags,
    Map<String, Object> metadata
) {
    public static Context create(String sessionId, String userId, Long agentId, List<String> tags, Map<String, Object> metadata) {
        return new Context(
                sessionId,
                userId,
                agentId,
                tags,
                metadata
        );
    }
    
    public Context withTags(List<String> newTags) {
        return new Context(sessionId, userId, agentId, newTags, metadata);
    }
    
    public Context withMetadata(Map<String, Object> newMetadata) {
        return new Context(sessionId, userId, agentId, tags, newMetadata);
    }

}