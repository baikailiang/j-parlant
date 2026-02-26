package com.jparlant.enums;

import lombok.Getter;

@Getter
public enum AgentStatus {
    /** 激活状态 */
    ACTIVE(1, "激活"),
    /** 未激活状态 */
    INACTIVE(0, "未激活");

    // Getter 方法
    private final Integer code;
    private final String desc;

    // 构造方法
    AgentStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举对象
     */
    public static AgentStatus fromCode(Integer code) {
        for (AgentStatus status : AgentStatus.values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}