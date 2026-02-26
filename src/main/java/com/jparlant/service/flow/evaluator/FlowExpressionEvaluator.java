package com.jparlant.service.flow.evaluator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.TypeComparator;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.spel.support.StandardTypeComparator;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public class FlowExpressionEvaluator {
    private final ExpressionParser parser = new SpelExpressionParser();

    // 缓存解析过的表达式，提高性能
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();


    /**
     * 自定义比较器：当发现一方是 String 另一方是 Number 时，尝试转换后比较。
     * 解决了 SpEL 原生不支持 "20" > 18 的问题。
     */
    private final TypeComparator flexibleTypeComparator = new StandardTypeComparator() {
        @Override
        public int compare(Object left, Object right) {
            // 情况 A：左边是字符串，右边是数字
            if (left instanceof String str && right instanceof Number) {
                Double leftVal = tryParseDouble(str);
                if (leftVal != null) return super.compare(leftVal, right);
            }
            // 情况 B：左边是数字，右边是字符串
            if (left instanceof Number && right instanceof String str) {
                Double rightVal = tryParseDouble(str);
                if (rightVal != null) return super.compare(left, rightVal);
            }
            // 其他情况（如 String vs String, Number vs Number）走默认逻辑
            return super.compare(left, right);
        }

        private Double tryParseDouble(String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return null; // 无法解析则返回 null，后续走默认比较可能报错，这是正确的行为
            }
        }
    };



    /**
     * 评估表达式是否为真
     *
     * @param expressionString 表达式字符串，例如: "#age >= 18 && #gender == 'M'"
     * @param variables 变量池（通常是 flowContext.getEntities()）
     * @return 评估结果
     */
    public boolean evaluate(String expressionString, Map<String, Object> variables) {
        if (expressionString == null || expressionString.isBlank()) {
            return true;
        }

        // 确保变量池不为 null
        Map<String, Object> safeVariables = (variables == null) ? Collections.emptyMap() : variables;

        try {
            // 1. 获取解析后的表达式（从缓存获取或解析新表达式）
            Expression expression = expressionCache.computeIfAbsent(expressionString, parser::parseExpression);

            // 2. 创建安全上下文 (SimpleEvaluationContext)
            StandardEvaluationContext context = new StandardEvaluationContext(safeVariables);
            // 注入自定义比较器 (核心：解决 "20" > 18)
            context.setTypeComparator(flexibleTypeComparator);

            // 【核心安全配置】：禁用 T() 操作符访问 Java 类
            // 这一步让 StandardEvaluationContext 变得和 SimpleEvaluationContext 一样安全
            context.setTypeLocator(typeName -> {
                throw new SpelEvaluationException(SpelMessage.TYPE_NOT_FOUND, typeName);
            });

            // 禁止访问某些高危对象或方法 (如果需要更严苛，可以清空 MethodResolvers)
            // context.setMethodResolvers(Collections.emptyList());

            // 3. 注入变量
            safeVariables.forEach(context::setVariable);

            // 4. 执行评估并强制转为 Boolean
            Boolean result = expression.getValue(context, Boolean.class);

            return result != null && result;

        } catch (Exception e) {
            log.error("表达式解析或执行失败: [{}], 错误: {}", expressionString, e.getMessage());
            // 如果表达式写错了，默认返回 false 拦截，防止业务风险
            return false;
        }
    }


    /**
     * 判断是否是数字格式
     */
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        // 匹配 123, 123.45, -123 等格式
        return str.matches("-?\\d+(\\.\\d+)?");
    }

    /**
     * 辅助方法：直接从对象中评估（如果未来需要支持更复杂的 Context 对象）
     */
    public <T> T evaluateCustom(String expressionString, Object rootObject, Class<T> targetType) {
        try {
            Expression expression = expressionCache.computeIfAbsent(expressionString, parser::parseExpression);
            return expression.getValue(rootObject, targetType);
        } catch (Exception e) {
            log.error("自定义表达式评估失败: {}", expressionString, e);
            return null;
        }
    }
}
