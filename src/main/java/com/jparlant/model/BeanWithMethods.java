package com.jparlant.model;

import java.util.List;

public record BeanWithMethods(
        String beanName,
        String displayName,
        List<MethodMetadata> methods
) {}
