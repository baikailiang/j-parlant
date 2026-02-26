package com.jparlant.service.flow.handler.input.validation;

import com.jparlant.model.ValidationContext;
import com.jparlant.utils.ValidationUtils;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@RequiredArgsConstructor
public class BasicValidator implements FieldValidator {

    @SuppressWarnings("unchecked")
    @Override
    public List<String> validate(ValidationContext context) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> cfg = context.getConfig(); // 对应前端 validationJson
        Map<String, Object> entities = context.getEntities();

        // 1. 必填校验 (保持原样，从顶层 required 获取)
        if ((boolean) cfg.getOrDefault("required", true)) {
            context.getExpectedInputs().forEach((key, label) -> {
                if (!entities.containsKey(key) || entities.get(key) == null) {
                    errors.add("缺少必要信息：" + label);
                }
            });
        }

        // 2. 统一规则校验 (适配前端 rules 数组)
        List<Map<String, Object>> rules = (List<Map<String, Object>>) cfg.get("rules");
        if (rules == null) return errors;

        for (Map<String, Object> rule : rules) {
            String path = (String) rule.get("path");
            String type = (String) rule.get("ruleType");
            String errorMsg = (String) rule.get("error");
            Object value = ValidationUtils.getValueByPath(entities, path);

            if (value == null) continue; // 非必填项且无值，跳过

            switch (type) {
                case "REGEX":
                    String pattern = (String) rule.get("criterion");
                    if (!String.valueOf(value).matches(pattern)) errors.add(errorMsg);
                    break;
                case "LENGTH":
                    int len = String.valueOf(value).length();
                    checkRange(len, rule, errorMsg, errors);
                    break;
                case "RANGE":
                    Double num = parseDouble(value);
                    if (num != null) checkRange(num, rule, errorMsg, errors);
                    break;
                case "ENUM":
                    List<String> options = (List<String>) rule.get("options");
                    if (options != null && !options.contains(String.valueOf(value))) errors.add(errorMsg);
                    break;
                case "INTEGER":
                    if (!String.valueOf(value).matches("^-?\\d+$")) errors.add(errorMsg);
                    break;
                case "BOOLEAN_CHECK":
                    String s = String.valueOf(value).toLowerCase();
                    if (!s.equals("true") && !s.equals("false")) errors.add(errorMsg);
                    break;
                case "COLLECTION_LIMIT":
                    if (value instanceof List<?> list) {
                        Integer minItems = (Integer) rule.get("minItems");
                        Integer maxItems = (Integer) rule.get("maxItems");
                        if (minItems != null && list.size() < minItems) errors.add(errorMsg);
                        if (maxItems != null && list.size() > maxItems) errors.add(errorMsg);
                    }
                    break;
                case "UNIQUE":
                    if (value instanceof List<?> list) {
                        if (list.stream().distinct().count() != list.size()) errors.add(errorMsg);
                    }
                    break;
                default:
                    // SPEL 交给 SpelBusinessValidator 处理
                    break;
            }
        }
        return errors;
    }

    private void checkRange(double val, Map<String, Object> rule, String error, List<String> errors) {
        if (rule.get("min") != null && val < ((Number) rule.get("min")).doubleValue()) errors.add(error);
        if (rule.get("max") != null && val > ((Number) rule.get("max")).doubleValue()) errors.add(error);
    }

    private Double parseDouble(Object obj) {
        try {
            return Double.parseDouble(String.valueOf(obj));
        } catch (Exception e) {
            return null;
        }
    }
}
