package com.jparlant.model;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jparlant.constant.ContextKeys;
import com.jparlant.service.flow.evaluator.FlowExpressionEvaluator;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 结构化流程上下文 (优化版)
 * 职责：负责流程数据的存储、分流、合并及生命周期状态管理
 */
@Data
@NoArgsConstructor
@Slf4j
public class FlowContext {

    private static final FlowExpressionEvaluator evaluator = new FlowExpressionEvaluator();

    // 定义一个静态的 JsonPath 配置，支持 POJO 读取
    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .build();

    // 1. 业务实体数据 (业务领域对象：如 phone, orderId)
    private Map<String, Object> entities = new HashMap<>();

    // 2. 步骤运行状态 (框架管理对象：Key 为 stepId，Value为该步骤的状态)
    private Map<String, StepState> stepStates = new HashMap<>();

    // 3. 全局元数据 (系统级标记：意图、情绪、SLA)
    private Map<String, Object> globalMetadata = new HashMap<>();


    /**
     * 在 FlowContext 中判断条件是否满足（支持普通值、集合、操作符、SpEL表达式）
     * @param conditionJson 条件 Map，例如：
     * {
     *   "verified": true,
     *   "age": {"$gte": 18},
     *   "status": ["ACTIVE", "PENDING"],
     *   "$expr": "#score >= 60 && #level == 'A'"
     * }
     * @return 条件是否满足
     */
    public boolean evaluateCondition(Map<String, Object> conditionJson) {
        if (conditionJson == null || conditionJson.isEmpty()) {
            return true; // 没有条件，默认满足
        }

        // 遍历每个条件
        for (Map.Entry<String, Object> entry : conditionJson.entrySet()) {
            String path = entry.getKey();  // 这里现在是 $.loan_request.amount
            Object expectedValue = entry.getValue();

            // 1. SpEL 表达式条件
            if ("$expr".equals(path) || "_spel".equals(path)) {
                if (!(expectedValue instanceof String exprStr)) {
                    log.warn("SpEL 表达式必须是字符串: key={}, value={}", path, expectedValue);
                    return false;
                }
                boolean exprResult = evaluator.evaluate(exprStr, entities);
                if (!exprResult) {
                    log.debug("SpEL 条件不满足: {}", exprStr);
                    return false;
                }
                continue; // 下一条条件
            }

            // 2. 普通值判断
            Object actualValue = null;
            try {
                // 将 entities 作为上下文，通过路径取值
                actualValue = JsonPath.read(this.entities, path);
            } catch (PathNotFoundException e) {
                log.error("JsonPath 未找到路径: {}", path);
                return false; // 路径不存在，视为不满足
            }

            if (expectedValue instanceof Collection<?> expectedCollection) {
                // 条件是集合，检查实际值是否在集合中
                if (!expectedCollection.contains(actualValue)) {
                    log.debug("集合条件不满足：key={}, expected={}, actual={}", path, expectedCollection, actualValue);
                    return false;
                }
            } else if (expectedValue instanceof Map<?, ?> expectedMap) {
                // 支持操作符条件
                if (!evaluateOperatorCondition(expectedMap, actualValue)) {
                    log.debug("操作符条件不满足：key={}, expected={}, actual={}", path, expectedMap, actualValue);
                    return false;
                }
            } else {
                // 普通值
                if (!Objects.equals(expectedValue, actualValue)) {
                    log.debug("普通条件不满足：key={}, expected={}, actual={}", path, expectedValue, actualValue);
                    return false;
                }
            }
        }

        return true;
    }


    /**
     * 支持简单操作符条件判断
     * @param operatorMap 例如 {"$gt": 18} / {"$lte": 100}
     * @param actualValue 实际值
     * @return 是否满足
     */
    private boolean evaluateOperatorCondition(Map<?, ?> operatorMap, Object actualValue) {
        if (actualValue == null) return false;

        for (Map.Entry<?, ?> opEntry : operatorMap.entrySet()) {
            String op = opEntry.getKey().toString();
            Object value = opEntry.getValue();

            try {
                double actualNum = Double.parseDouble(actualValue.toString());
                double expectedNum = Double.parseDouble(value.toString());

                switch (op) {
                    case "$gt" -> { if (!(actualNum > expectedNum)) return false; }
                    case "$gte" -> { if (!(actualNum >= expectedNum)) return false; }
                    case "$lt" -> { if (!(actualNum < expectedNum)) return false; }
                    case "$lte" -> { if (!(actualNum <= expectedNum)) return false; }
                    case "$eq" -> { if (!(actualNum == expectedNum)) return false; }
                    case "$neq" -> { if (!(actualNum != expectedNum)) return false; }
                    default -> {
                        log.warn("未知操作符: {}", op);
                        return false;
                    }
                }
            } catch (NumberFormatException e) {
                log.warn("无法解析为数字: actual={}, expected={}", actualValue, value);
                return false;
            }
        }

        return true;
    }





    /**
     * 从 Map 恢复上下文 (支持强类型转换与安全检查)
     */
    @SuppressWarnings("unchecked")
    public static FlowContext fromMap(Map<String, Object> rawMap) {
        FlowContext context = new FlowContext();
        if (rawMap == null) return context;

        // 恢复实体
        Optional.ofNullable(rawMap.get("entities"))
                .filter(Map.class::isInstance)
                .ifPresent(m -> context.setEntities(new HashMap<>((Map<String, Object>) m)));

        // 恢复全局元数据
        Optional.ofNullable(rawMap.get("globalMetadata"))
                .filter(Map.class::isInstance)
                .ifPresent(m -> context.setGlobalMetadata(new HashMap<>((Map<String, Object>) m)));

        // 恢复步骤状态 (结构化转换)
        Optional.ofNullable(rawMap.get("stepStates"))
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .ifPresent(states -> states.forEach((k, v) -> {
                    if (v instanceof Map) {
                        context.stepStates.put(k, StepState.fromMap((Map<String, Object>) v));
                    }
                }));

        return context;
    }

    /**
     * 根据意图分析结果更新上下文
     */
    public void updateFromIntent(IntentAnalysisResult intentResult, String userInput) {
        // 1. 基础元数据 (使用常量类)
        this.globalMetadata.put(ContextKeys.Global.CURRENT_INTENT_ID, intentResult.primaryIntentId());
        this.globalMetadata.put(ContextKeys.Global.CURRENT_INTENT_NAME, intentResult.primaryIntentName());
        this.globalMetadata.put(ContextKeys.Global.EMOTION, intentResult.emotion());
        this.globalMetadata.put(ContextKeys.Global.CONFIDENCE, intentResult.confidence());
        this.globalMetadata.put(ContextKeys.Global.COMPLEXITY, intentResult.complexity());

        // 2. 合并提取到的实体
        this.mergeEntities(intentResult.extractedEntities());
    }

    /**
     * 合并实体
     */
    private void mergeEntities(Map<String, Object> newEntities) {
        if (newEntities == null || newEntities.isEmpty()) return;

        newEntities.forEach((key, value) -> {
            // 策略：如果新旧值都是 List，则合并列表；否则新值覆盖旧值（纠错）
            this.entities.merge(key, value, (oldVal, newVal) -> {
                if (oldVal instanceof List && newVal instanceof List) {
                    List<Object> combined = new ArrayList<>((List<?>) oldVal);
                    combined.addAll((List<?>) newVal);
                    return combined;
                }
                return newVal;
            });
        });
    }


    /**
     * 获取或初始化步骤状态
     */
    public StepState getStepState(String stepId) {
        return stepStates.computeIfAbsent(stepId, k -> new StepState());
    }

    public StepState getStepState(Long stepId) {
        return getStepState(String.valueOf(stepId));
    }



    /**
     * 转回 Map 供持久化
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("entities", entities);
        map.put("stepStates", stepStates);
        map.put("globalMetadata", globalMetadata);
        return map;
    }


    // -----------------------------------------------------------------------
    // 内部类：存储步骤的具体状态
    // -----------------------------------------------------------------------
    @Data
    public static class StepState {
        private Status status;      // SUCCESS, FAIL, PENDING



        public static StepState fromMap(Map<String, Object> map) {
            StepState state = new StepState();
            state.setStatus(Status.valueOf((String) map.get(ContextKeys.Step.STATUS)));
            return state;
        }


        public enum Status{
            SUCCESS,
            FAIL,
            PENDING
        }


    }
}