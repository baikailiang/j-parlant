package com.jparlant.annation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FlowMethod {
    String value() default "";        // 显示名称
    String description() default "";  // 详细描述
}
