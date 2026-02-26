package com.jparlant.annation;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface FlowAction {
    String value() default ""; // 业务别名，如 "用户服务"
}
