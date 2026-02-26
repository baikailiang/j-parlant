package com.jparlant.service.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jparlant.entity.AgentIntentEntity;
import com.jparlant.entity.IntentStepEntity;
import com.jparlant.entity.IntentStepTransitionEntity;
import com.jparlant.model.AgentFlow;
import com.jparlant.model.SessionState;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class AgentFlowWrapper {

    private final ObjectMapper objectMapper;

    /**
     * 将 Entity 转换为最新的 AgentFlow
     */
    public AgentFlow toDomain(AgentIntentEntity intentEntity,
                              List<IntentStepEntity> stepEntities,
                              List<IntentStepTransitionEntity> stepTransitionEntities) {

        // 1. 建立基础步骤 Map (ID -> Step)
        Map<Long, AgentFlow.FlowStep> rawStepMap = stepEntities.stream()
                .map(this::toFlowStep)
                .collect(Collectors.toMap(AgentFlow.FlowStep::stepId, s -> s));

        // 2. 建立流转映射并按优先级排序 (FromStepId -> List<Transition>)
        Map<Long, List<AgentFlow.FlowTransition>> transitionsMap = stepTransitionEntities.stream()
                .collect(Collectors.groupingBy(
                        IntentStepTransitionEntity::getFromStepId,
                        Collectors.mapping(this::toFlowTransition, Collectors.toList())
                ));

        // 对每个步骤的分支按 priority 排序
        transitionsMap.values().forEach(list ->
                list.sort(Comparator.comparingInt(AgentFlow.FlowTransition::priority))
        );

        // 3. 执行逻辑排序 (核心步骤)
        List<AgentFlow.FlowStep> sortedSteps = sortStepsByLogic(rawStepMap, transitionsMap, stepTransitionEntities);

        // 4. 构建 AgentFlow
        return new AgentFlow(
                intentEntity.getAgentId(),
                intentEntity.getId(),
                intentEntity.getName(),
                intentEntity.getDescription(),
                intentEntity.getEnabled(),
                AgentFlow.FlowType.valueOf(intentEntity.getFlowType()),
                sortedSteps,
                parseMap(intentEntity.getMetadataJson())
        );
    }


    /**
     * 根据逻辑流转关系对步骤进行排序
     */
    private List<AgentFlow.FlowStep> sortStepsByLogic(
            Map<Long, AgentFlow.FlowStep> stepMap,
            Map<Long, List<AgentFlow.FlowTransition>> transitionsMap,
            List<IntentStepTransitionEntity> allTransitions) {

        List<AgentFlow.FlowStep> result = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>(); // 记录已访问 ID

        // 1. 寻找起点 (入度为 0 的节点)
        Set<Long> targetIds = allTransitions.stream()
                .map(IntentStepTransitionEntity::getToStepId)
                .collect(Collectors.toSet());

        List<Long> rootIds = stepMap.keySet().stream()
                .filter(id -> !targetIds.contains(id))
                .sorted() // 保证起点的确定性
                .toList();

        // 2. 从起点开始 DFS 遍历
        for (Long rootId : rootIds) {
            dfsTraverse(rootId, stepMap, transitionsMap, visited, result);
        }

        // 3. 处理循环或孤立节点（防止漏掉不在路径上的点）
        if (visited.size() < stepMap.size()) {
            for (Long id : stepMap.keySet()) {
                if (!visited.contains(id)) {
                    dfsTraverse(id, stepMap, transitionsMap, visited, result);
                }
            }
        }

        return result;
    }


    private void dfsTraverse(Long currentId,
                             Map<Long, AgentFlow.FlowStep> stepMap,
                             Map<Long, List<AgentFlow.FlowTransition>> transitionsMap,
                             Set<Long> visited,
                             List<AgentFlow.FlowStep> result) {
        if (visited.contains(currentId)) return;

        visited.add(currentId);
        AgentFlow.FlowStep step = stepMap.get(currentId);
        if (step == null) return;

        // 在这里把完整的 transitions 塞进 Step 对象
        List<AgentFlow.FlowTransition> outTransitions = transitionsMap.getOrDefault(currentId, List.of());

        // 构造带有完整 Transition 的对象
        AgentFlow.FlowStep stepWithTransitions = new AgentFlow.FlowStep(
                step.stepId(), step.intentId(), step.name(), step.description(),
                step.belongToPhase(), step.type(), step.prompt(), step.expectedInputs(),
                step.validation(), step.dependencies(), step.canSkip(), step.skipToPrompt(),
                step.ocrAction(), step.coreActions(), outTransitions
        );

        result.add(stepWithTransitions);

        // 递归访问子节点（由于 transitions 已经排好序，所以会按 priority 访问）
        for (AgentFlow.FlowTransition transition : outTransitions) {
            dfsTraverse(transition.toStepId(), stepMap, transitionsMap, visited, result);
        }
    }


    /**
     * 将 IntentStepEntity 转换为最新的 FlowStep（outTransitions 先为空，稍后填充）
     */
    private AgentFlow.FlowStep toFlowStep(IntentStepEntity entity) {
        return new AgentFlow.FlowStep(
                entity.getId(),
                entity.getIntentId(),
                entity.getName(),
                entity.getDescription(),
                SessionState.SessionPhase.valueOf(entity.getBelongToPhase()),
                AgentFlow.FlowStep.StepType.valueOf(entity.getStepType()),
                entity.getPrompt(),
                parseMap(entity.getExpectedInputsJson()),
                parseMap(entity.getValidationJson()),
                parseIntegerList(entity.getDependencies()),
                entity.getCanSkip(),
                entity.getSkipToPrompt(),
                parseActionCall(entity.getOcrAction()),
                parseActionCalls(entity.getCoreActionsJson()),
                List.of()
        );
    }

    /**
     * 将 IntentStepTransitionEntity 转换为 FlowTransition
     */
    private AgentFlow.FlowTransition toFlowTransition(IntentStepTransitionEntity entity) {
        return new AgentFlow.FlowTransition(
                entity.getId(),
                entity.getIntentId(),
                entity.getFromStepId(),
                entity.getToStepId(),
                entity.getBranchCode(),
                entity.getPriority() != null ? entity.getPriority() : 0,
                parseMap(entity.getConditionJson())
        );
    }

    // --- JSON 解析辅助工具 ---

    /**
     * 单个 ActionCall 解析辅助工具
     */
    private AgentFlow.FlowStep.ActionCall parseActionCall(String json) {
        try {
            if (json == null || json.isBlank()) return null;
            return objectMapper.readValue(json, AgentFlow.FlowStep.ActionCall.class);
        } catch (Exception e) {
            System.err.println("解析 OCR ActionCall 失败: " + json + ", 原因: " + e.getMessage());
            return null;
        }
    }

    private List<AgentFlow.FlowStep.ActionCall> parseActionCalls(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json, new TypeReference<List<AgentFlow.FlowStep.ActionCall>>() {});
        } catch (Exception e) {
            System.err.println("解析 ActionCall 失败: " + json + ", 原因: " + e.getMessage());
            return List.of();
        }
    }

    private List<Long> parseIntegerList(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}

