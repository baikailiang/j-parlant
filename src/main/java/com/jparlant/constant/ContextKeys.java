package com.jparlant.constant;

/**
 * Key 常量定义
 */
public final class ContextKeys {

    // 私有构造函数，防止实例化
    private ContextKeys() {}

    /**
     * 全局元数据 Key (存储在 flow_context的globalMetadata 中)
     */
    public static final class Global {
        public static final String CURRENT_INTENT_ID = "current_intent_id";       // 当前识别到的意图 ID
        public static final String CURRENT_INTENT_NAME = "current_intent_name";   // 当前识别到的意图名称
        public static final String EMOTION = "emotion";                           // 用户情绪标签 (如: NEGATIVE, URGENT)
        public static final String CONFIDENCE = "confidence";                     // 意图识别置信度
        public static final String COMPLEXITY = "complexity";                     // 任务复杂度 (SIMPLE, COMPLEX)
    }

    /**
     * 步骤内部状态 Key (存储在 flow_context的stepStates 中)
     */
    public static final class Step {
        public static final String STATUS = "status";                   // 步骤状态 (SUCCESS, FAIL, PENDING)
    }

    /**
     * SessionState的variables的key
     */
    public static final class Session {
        public static final String FLOW_CONTEXT = "flow_context";
        public static final String CURRENT_FLOW_STEP = "current_flow_step";
        public static final String FLOW_COMPLETE = "flow_complete";
    }

}
