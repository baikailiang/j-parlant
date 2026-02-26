package com.jparlant.model;

import java.util.List;

public record MethodMetadata(
        String methodName,
        String displayName,
        String description,
        List<PropertySchema> parameters,
        List<PropertySchema> returnSchema
) {}
