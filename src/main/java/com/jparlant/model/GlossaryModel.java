package com.jparlant.model;

import java.util.List;
import java.util.Map;

public record GlossaryModel(
        Long id,
        String name,
        String definition,
        String category,
        List<String> synonyms,
        List<String> relatedNames,
        Map<String, String> examples,
        Long agentId,
        Integer priority
) {}
