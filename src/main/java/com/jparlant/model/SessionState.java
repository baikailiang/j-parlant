package com.jparlant.model;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 会话状态模型
 * 支持深度的行为上下文和状态管理
 */
public record SessionState(
    String sessionId,
    String userId,
    Long agentId,
    SessionPhase phase,              // 会话阶段
    List<String> activeTags,         // 当前激活的标签
    Map<String, Object> variables,   // 会话变量
    Map<String, Object> memory,      // 长期记忆，存储跨越多个意图（Intent）甚至跨越多次会话依然有效的信息。它让 Agent 具有“认人”的能力，而不是每次都像陌生人一样对话，如：user_real_name: "张三"
    Map<String, Object> constraints, // 当前约束条件，如业务红线：max_loan_amount: 50000
    List<String> capabilities,       // 当前能力，当前会话中 Agent 被允许调用的功能或工具集、权限动态降级/升级
    LocalDateTime lastInteraction,   // 用户与 AI 最后一次交互发生的精确时间
    LocalDateTime createdAt
) {

    public enum SessionPhase {
        // 1. 生命周期
        READY,              // 准备就绪：会话已创建，等待用户第一句话

        // 2. 主对话流程
        PROLOGUE,           // 开场阶段：问候、身份确认、意图引导
        UNDERSTANDING,      // 需求挖掘：意图识别、槽位填充、信息搜集
        PROCESSING,         // 业务执行：正在计算、调用工具、生成方案、解决问题
        REVIEW,             // 确认复核：方案确认、用户反馈收集、二次验证

        // 3. 阻塞与干预
        PENDING,            // 异步等待：等待外部信号
        HANDOVER,           // 人工介入：正在转接人工或已由人工接管
        SUSPENDED,          // 会话挂起：因合规警告、欠费、频控等暂时冻结 AI 响应

        // 4. 终结态
        CLOSING,            // 正常结语：正在进行收尾话术
        ARCHIVED,           // 已归档：会话正常结束，不再接受新输入
        TERMINATED          // 强行终止：因严重违规、系统故障或用户直接退出而截断
    }
    
    public static SessionState initial(String sessionId, String userId, Long agentId) {
        return new SessionState(
            sessionId,
            userId,
            agentId,
            SessionPhase.READY,
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            LocalDateTime.now(),
            LocalDateTime.now()
        );
    }

    /**
     * 更新会话阶段
     */
    public SessionState withPhase(SessionPhase newPhase) {
        return new SessionState(
            sessionId, userId, agentId, newPhase, activeTags, variables, memory, constraints,
            capabilities, LocalDateTime.now(), createdAt
        );
    }
    
    /**
     * 更新会话变量
     */
    public SessionState withVariable(String key, Object value) {
        Map<String, Object> newVariables = new HashMap<>(Map.copyOf(variables));
        newVariables.put(key, value);
        return new SessionState(
            sessionId, userId, agentId, phase, activeTags, newVariables,
            memory, constraints,
            capabilities, LocalDateTime.now(), createdAt
        );
    }

    public SessionState withVariables(Map<String, Object> updateVariables) {
        if (updateVariables == null || updateVariables.isEmpty()) {
            return this;
        }
        // 1. 复制现有变量到新 Map
        Map<String, Object> newVariables = new HashMap<>(this.variables);
        // 2. 批量塞入新变量
        newVariables.putAll(updateVariables);
        // 3. 返回新 Record 实例
        return new SessionState(
                sessionId, userId, agentId, phase, activeTags,
                Collections.unmodifiableMap(newVariables),
                memory, constraints,
                capabilities,
                LocalDateTime.now(), // 更新最后交互时间
                createdAt
        );
    }

    
    /**
     * 添加标签
     */
    public SessionState withAddedTag(String tag) {
        List<String> newTags = new ArrayList<>(List.copyOf(activeTags));
        newTags.add(tag);
        return new SessionState(
            sessionId, userId, agentId, phase, newTags, variables,
            memory, constraints,
            capabilities, LocalDateTime.now(), createdAt
        );
    }

    
    /**
     * 添加约束
     */
    public SessionState withConstraint(String key, Object constraint) {
        Map<String, Object> newConstraints = new HashMap<>(Map.copyOf(constraints));
        newConstraints.put(key, constraint);
        return new SessionState(
            sessionId, userId, agentId, phase, activeTags, variables,
            memory, newConstraints,
            capabilities, LocalDateTime.now(), createdAt
        );
    }
    
    /**
     * 检查是否处于特定阶段
     */
    public boolean isInPhase(SessionPhase targetPhase) {
        return this.phase == targetPhase;
    }
    
    /**
     * 检查是否有特定标签
     */
    public boolean hasTag(String tag) {
        return activeTags.contains(tag);
    }
    
    /**
     * 检查是否有特定变量
     */
    public boolean hasVariable(String key) {
        return variables.containsKey(key);
    }
    
    /**
     * 获取变量值
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key, Class<T> type) {
        Object value = variables.get(key);
        if (value == null) return null;

        // 如果类型直接匹配，直接返回
        if (type.isInstance(value)) {
            return (T) value;
        }

        // 处理数字类型的特殊转换（Integer 转 Long 等）
        if (value instanceof Number number) {
            if (type == Long.class) {
                return (T) Long.valueOf(number.longValue());
            } else if (type == Integer.class) {
                return (T) Integer.valueOf(number.intValue());
            } else if (type == Double.class) {
                return (T) Double.valueOf(number.doubleValue());
            }
        }

        return null;
    }

}