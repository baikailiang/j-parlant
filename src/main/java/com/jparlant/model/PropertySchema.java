package com.jparlant.model;

import java.util.List;

public record PropertySchema(
        String name,
        String type,
        String description,
        boolean complex,
        List<PropertySchema> children
) {}
