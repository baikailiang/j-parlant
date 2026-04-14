package com.jparlant.model;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent业务流程定义模型
 * 定义Agent的业务流程，包括各个步骤、转换条件和动作
 */
public record AgentFlow(
    Long agentId,
    Long intentId,
    String name,
    String description,
    boolean enabled,
    FlowType type,
    List<FlowStep> steps,           // 流程步骤
    Map<String, Object> metadata    // 流程元数据
) {
    
    public enum FlowType {
        LINEAR,      // 线性流程（按顺序执行）
        CONDITIONAL, // 条件流程（根据条件分支）
        LOOP,        // 循环流程（可重复执行）
        INTERACTIVE  // 交互式流程（根据用户输入动态调整）
    }

    public record FlowTransition(
            Long transitionId,        // 数据库ID，可选
            Long intentId,            // 所属意图ID
            Long fromStepId,          // 起始步骤ID
            Long toStepId,            // 目标步骤ID
            String branchCode,        // 分支标识，例如 DEFAULT / SKIP / NEED_VERIFY
            int priority,             // 同一个 from_step 下的分支顺序
            Map<String, Object> conditionJson // 条件 JSON，可为空
    ) {}


    public record FlowStep(
            Long stepId,
            Long intentId,
            String name,
            String description,
            SessionState.SessionPhase belongToPhase,  // 该步骤所属的会话阶段
            StepType type,                            // 步骤类型
            String prompt,                            // 引导用户的提示语，仅INPUT和CONFIRM使用
            Map<String, Object> expectedInputs,       // 期望提取的内容
            Map<String, Object> validation,           // 定义“怎么校验”（如：正则、长度）
            List<Long> dependencies,                  // 前置依赖步骤
            boolean canSkip,                          // 是否允许跳过
            String skipToPrompt,                      // 跳跃时的引导提示
            ActionCall ocrAction,                         // 图片识别执行器
            List<ActionCall> coreActions,             // 核心执行逻辑
            List<FlowTransition> outTransitions,       // 该步骤的分支流转
            boolean isDirectReturn
    ) {

        public record ActionCall(
                String targetProcessor,            // 需要的处理器方法名称

                boolean requireReturnValue,       // 是否需要返回值

                /**
                 * 输入参数映射
                 */
                Map<String, String> inputMapping,

                /**
                 * 输出结果回填映射
                 */
                Map<String, Object> outputMapping
        ) { }

        public enum StepType {
            /**
             * 1. 交互型 (交互 + 校验 + 提取)
             * 作用：解决“听”的问题。
             * 它负责向用户索取数据、提取实体并进行合法性校验。
             */
            INPUT,

            /**
             * 2. 逻辑型 (纯逻辑运算/API调用)
             * 作用：解决“做”的问题。
             * 它是静默执行的，不直接与用户对话。如：调用后台接口、计算数值、修改数据库。
             */
            ACTION,

            TRANSITION,

            COMPLETED
        }
        
        /**
         * 检查步骤依赖是否满足
         */
        private boolean isDependenciesSatisfied(FlowContext flowContext) {
            if (dependencies == null || dependencies.isEmpty()) {
                return true;
            }
            
            for (Long dependency : dependencies) {
                FlowContext.StepState stepState = flowContext.getStepState(dependency);
                if (!FlowContext.StepState.Status.SUCCESS.equals(stepState.getStatus())) {
                    return false;
                }
            }
            return true;
        }
        
        /**
         * 获取未完成的依赖步骤
         */
        private List<Long> getUncompletedDependencies(FlowContext flowContext) {
            if (dependencies == null || dependencies.isEmpty()) {
                return List.of();
            }
            
            return dependencies.stream()
                .filter(dep -> {
                    FlowContext.StepState stepState = flowContext.getStepState(dep);
                    // 逻辑：如果状态对象不存在，或者状态不是 SUCCESS，则认为未完成
                    return stepState == null || !FlowContext.StepState.Status.SUCCESS.equals(stepState.getStatus());
                })
                .toList();
        }
    }
    
    /**
     * 根据当前状态获取下一个步骤
     */
    public FlowStep getNextStep(Long currentStepId, FlowContext flowContext) {
        // 1. 流程起点判定优化
        if (currentStepId == null) {
            Long initialStepId = getInitialStepForFlow(); // 使用拓扑分析寻找真正的起点
            return initialStepId != null ? findStep(initialStepId) : null;
        }

        FlowStep currentStep = findStep(currentStepId);
        if (currentStep == null) {
            return null;
        }

        // 2. 获取该步骤的所有流转分支
        List<FlowTransition> transitions = currentStep.outTransitions();
        if (transitions == null || transitions.isEmpty()) {
            // 如果是 COMPLETED 类型或者没有出度，说明流程正常结束
            return null;
        }

        // 3. 按优先级排序（Transition 的 priority 依然有效，用于控制条件判定顺序）
        List<FlowTransition> sortedTransitions = transitions.stream()
                .sorted(Comparator.comparingInt(FlowTransition::priority))
                .toList();

        // 4. 寻找第一个满足条件的分支
        for (FlowTransition t : sortedTransitions) {
            // 条件匹配逻辑：
            // a. 如果 conditionJson 为空，视为默认分支 (Default/Else)
            // b. 如果不为空，则评估条件表达式
            if (t.conditionJson() == null || t.conditionJson().isEmpty()
                    || flowContext.evaluateCondition(t.conditionJson())) {

                FlowStep nextStep = findStep(t.toStepId());
                if (nextStep != null) {
                    return nextStep;
                }
            }
        }

        // 5. 如果所有分支条件都不满足
        return null;
    }


    /**
     * 查找步骤
     */
    public FlowStep findStep(Long stepId) {
        return steps.stream()
            .filter(step -> step.stepId().equals(stepId))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * 查找步骤索引
     */
    private int findStepIndex(Long stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepId().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }

    
    /**
     * 检查步骤跳跃是否合法
     * @param currentStepId 当前步骤ID
     * @param targetStepId 目标步骤ID
     * @param flowContext 流程上下文
     * @return 跳跃检查结果
     */
    public StepJumpValidation validateStepJump(Long currentStepId, Long targetStepId, FlowContext flowContext) {
        FlowStep currentStep = findStep(currentStepId);
        FlowStep targetStep = findStep(targetStepId);
        
        if (targetStep == null) {
            return new StepJumpValidation(false, "步骤不存在", List.of(), null);
        }

        // 阶段围栏校验, 如果尝试跨阶段跳转（例如从 UNDERSTANDING 直接跳到 PROCESSING）
        /*if (null != currentStep && currentStep.belongToPhase() != targetStep.belongToPhase()) {
            // 只有特定的“可跳跃阶段”或满足特定条件才允许跨阶段跳跃
            // 这里我们可以定义：严禁跨越多个阶段，或者严禁跳过某些核心阶段
            if (!targetStep.canSkip()) {
                return new StepJumpValidation(false, "禁止跨阶段非法跳转，必须先完成当前阶段：" + currentStep.belongToPhase(), List.of(), null);
            }
        }*/

        // 1. 检查是否是“回溯”或“重试”
        // 如果 targetStepId 等于 currentStepId，或者是当前步骤的上游（从 target 可达 current）
        if (currentStepId != null) {
            if (currentStepId.equals(targetStepId)) {
                return new StepJumpValidation(true, "重新执行当前步骤", List.of(), targetStep);
            }

            if (isReachable(targetStepId, currentStepId)) {
                return new StepJumpValidation(true, "允许向后回溯到上游步骤", List.of(), targetStep);
            }
        }
        
        // 2. 检查依赖关系
        if (!targetStep.isDependenciesSatisfied(flowContext)) {
            List<Long> missingDeps = targetStep.getUncompletedDependencies(flowContext);
            return new StepJumpValidation(false, "存在未完成的依赖步骤", missingDeps, null);
        }
        
        // 3. 检查中间必须步骤
        List<Long> requiredIntermediateSteps = findRequiredStepsBetween(currentStep, targetStep);
        if (!requiredIntermediateSteps.isEmpty()) {
            return new StepJumpValidation(false, "存在必须完成的中间步骤", requiredIntermediateSteps, null);
        }
        
        return new StepJumpValidation(true, "允许跳跃", List.of(), targetStep);
    }


    /**
     * 判断从 startId 是否可以到达 endId
     */
    public boolean isReachable(Long startId, Long endId) {
        if (startId == null || endId == null) return false;
        if (startId.equals(endId)) return true;

        Queue<Long> queue = new LinkedList<>();
        Set<Long> visited = new HashSet<>();

        queue.add(startId);
        visited.add(startId);

        while (!queue.isEmpty()) {
            Long currentId = queue.poll();
            FlowStep currentStep = findStep(currentId);

            if (currentStep != null && currentStep.outTransitions() != null) {
                for (FlowTransition transition : currentStep.outTransitions()) {
                    Long nextId = transition.toStepId();
                    if (nextId.equals(endId)) {
                        return true;
                    }
                    if (!visited.contains(nextId)) {
                        visited.add(nextId);
                        queue.add(nextId);
                    }
                }
            }
        }
        return false;
    }


    /**
     * 查找两个步骤之间的必须完成步骤（基于拓扑图路径分析）
     */
    private List<Long> findRequiredStepsBetween(FlowStep currentStep, FlowStep targetStep) {
        if (currentStep == null || targetStep == null || currentStep.stepId().equals(targetStep.stepId())) {
            return List.of();
        }

        // 1. 获取从 currentStep 出发能到达 targetStep 的所有中间节点
        // 算法：寻找 A 到 B 之间所有路径的交集或并集中的必填项
        // 这里采用“路径节点集”：即所有位于 current -> target 路径上的步骤
        Set<Long> nodesOnPaths = findAllNodesOnPaths(currentStep.stepId(), targetStep.stepId());

        // 2. 移除当前步骤和目标步骤本身，只保留中间的
        nodesOnPaths.remove(currentStep.stepId());
        nodesOnPaths.remove(targetStep.stepId());

        // 3. 过滤出必须完成（不可跳过）的步骤
        return nodesOnPaths.stream()
                .map(this::findStep)
                .filter(step -> step != null && !step.canSkip())
                .map(FlowStep::stepId)
                .toList();
    }

    /**
     * 辅助方法：通过深度优先搜索（DFS）找到起点和终点之间所有路径上的节点
     */
    private Set<Long> findAllNodesOnPaths(Long startId, Long endId) {
        Set<Long> allPathNodes = new HashSet<>();
        List<Long> currentPath = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        dfsFindPaths(startId, endId, visited, currentPath, allPathNodes);
        return allPathNodes;
    }

    private void dfsFindPaths(Long currentId, Long targetId, Set<Long> visited,
                              List<Long> currentPath, Set<Long> allPathNodes) {
        visited.add(currentId);
        currentPath.add(currentId);

        if (currentId.equals(targetId)) {
            // 如果到达了目标，说明当前路径上所有的点都是“中间点”
            allPathNodes.addAll(currentPath);
        } else {
            FlowStep currentStep = findStep(currentId);
            if (currentStep != null && currentStep.outTransitions() != null) {
                for (FlowTransition transition : currentStep.outTransitions()) {
                    Long nextId = transition.toStepId();
                    // 防止循环引用导致的死循环
                    if (!visited.contains(nextId)) {
                        dfsFindPaths(nextId, targetId, visited, currentPath, allPathNodes);
                    }
                }
            }
        }

        // 回溯
        currentPath.remove(currentPath.size() - 1);
        visited.remove(currentId);
    }


    /**
     * 获取某个会话阶段的第一个步骤，返回步骤id
     * @param phase
     * @return
     */
    /**
     * 获取某个会话阶段的第一个步骤 ID
     * 逻辑：基于拓扑结构寻找该阶段的入口节点
     */
    public Long getInitialStepForPhase(SessionState.SessionPhase phase) {
        if (phase == null || steps == null || steps.isEmpty()) {
            return null;
        }

        // 1. 获取该阶段的所有步骤 ID 集合，用于后续过滤
        Set<Long> stepIdsInPhase = steps.stream()
                .filter(s -> s.belongToPhase() == phase)
                .map(FlowStep::stepId)
                .collect(Collectors.toSet());

        if (stepIdsInPhase.isEmpty()) {
            return null;
        }

        // 2. 找出在该阶段内部，作为“目标节点”的步骤（即有阶段内入度的步骤）
        // 逻辑：遍历所有属于该阶段的步骤，看它们的 outTransitions 指向了本阶段内的哪些步骤
        Set<Long> internalTargetStepIds = steps.stream()
                .filter(s -> s.belongToPhase() == phase)
                .flatMap(s -> s.outTransitions().stream())
                .map(FlowTransition::toStepId)
                .filter(stepIdsInPhase::contains)
                .collect(Collectors.toSet());

        // 3. 初始步骤定义：属于该阶段，但【没有】被本阶段内其他步骤指向的节点
        // 这种情况涵盖了：从外部 Phase 跳入的节点，或者流程的绝对起始节点
        Optional<FlowStep> initialStep = steps.stream()
                .filter(s -> s.belongToPhase() == phase)
                .filter(s -> !internalTargetStepIds.contains(s.stepId()))
                .findFirst();

        if (initialStep.isPresent()) {
            return initialStep.get().stepId();
        }

        // 4. 兜底逻辑：如果该阶段内部是一个闭环（环形流程），导致所有节点都有入度
        // 则按照 steps 列表的自然顺序返回该阶段遇到的第一个步骤
        return steps.stream()
                .filter(s -> s.belongToPhase() == phase)
                .findFirst()
                .map(FlowStep::stepId)
                .orElse(null);
    }


    /**
     * 获取流程中的第一步步骤id
     * @return
     */
    public Long getInitialStepForFlow() {
        if (steps == null || steps.isEmpty()) return null;

        // 获取所有被指向过的步骤 ID
        Set<Long> allTargetIds = steps.stream()
                .flatMap(s -> s.outTransitions().stream())
                .map(FlowTransition::toStepId)
                .collect(Collectors.toSet());

        // 找到没有任何入度的步骤（作为全流程起点）
        return steps.stream()
                .filter(s -> !allTargetIds.contains(s.stepId()))
                .findFirst()
                .map(FlowStep::stepId)
                .orElse(steps.get(0).stepId()); // 闭环则默认第一条
    }


    /**
     * 步骤跳跃验证结果
     */
    public record StepJumpValidation(
        boolean isValid,
        String reason,
        List<Long> requiredSteps,
        FlowStep targetStep
    ) {}

}