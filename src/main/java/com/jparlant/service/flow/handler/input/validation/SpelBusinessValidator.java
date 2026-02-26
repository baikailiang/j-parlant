package com.jparlant.service.flow.handler.input.validation;

import com.jparlant.model.ValidationContext;
import com.jparlant.service.flow.evaluator.FlowExpressionEvaluator;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SpEL 业务逻辑校验
 *
 * SPEL表达式的字段链路如下：
 * 定义期：你在 FlowStep 里定义了 expectedInputs: {"age": "年龄", "gender": "性别"}。
 * 提取期：LLM 识别到用户说“我今年20岁”，于是提取出实体 {"age": 20}。
 * 存储期：FlowContext 将这个实体存入 entities 映射表中。
 * 注入期：FlowExpressionEvaluator 执行 variables.forEach(context::setVariable)，将 entities 里的内容全部注入到 SpEL 环境。
 * 结论： 你在 expectedInputs 字典里写了什么 Key，你在 businessRules 的 rule 里就能用什么 #Key。
 */
@RequiredArgsConstructor
public class SpelBusinessValidator implements FieldValidator {

    private final FlowExpressionEvaluator evaluator;

    @SuppressWarnings("unchecked")
    @Override
    public List<String> validate(ValidationContext context) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> cfg = context.getConfig();
        List<Map<String, Object>> rules = (List<Map<String, Object>>) cfg.get("rules");

        if (rules == null) return errors;

        for (Map<String, Object> rule : rules) {
            if ("SPEL".equals(rule.get("ruleType"))) {
                String expression = (String) rule.get("spelExpression");
                String errorMsg = (String) rule.get("error");
                try {
                    // SpEL 校验通常需要整个实体环境
                    boolean match = evaluator.evaluate(expression, context.getEntities());
                    if (!match) errors.add(errorMsg);
                } catch (Exception e) {
                    errors.add("业务逻辑校验解析异常: " + errorMsg);
                }
            }
        }
        return errors;
    }
}
