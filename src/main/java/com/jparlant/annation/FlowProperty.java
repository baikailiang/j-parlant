package com.jparlant.annation;

import java.lang.annotation.*;

/**
 * 业务属性注解：用于描述参数或 POJO 字段
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FlowProperty {
    String value() default ""; // 描述信息，如 "用户ID"、"订单金额"
}
