package com.jparlant.model;

import com.jparlant.enums.AgentStatus;

public record Agent(
        Long id,
        String name,
        String instructions,
        String description,
        String keywords,
        AgentStatus status
) {}