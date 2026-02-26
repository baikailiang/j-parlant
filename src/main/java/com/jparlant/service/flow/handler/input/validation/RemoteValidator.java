package com.jparlant.service.flow.handler.input.validation;

import com.jparlant.model.ValidationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**  todo 目前暂时未使用上这个规则，后续优化
 * 校验接口定义规范
 * 1.必须是 Spring Bean
 * 校验类必须被 @Service 或 @Component 注解标记，且 Bean 的名称需与前端配置的 beanName 一致。
 * 2. 方法签名固定
 * 参数数量：必须有且仅有一个参数。
 * 参数类型：必须是 Object 类型（或者是能接收实体值的类型，建议用 Object 然后内部强转，或者直接匹配实体值的类型如 String）。
 * 返回值：必须返回 Boolean 或 boolean。
 *
 * 代码示例：
 *     @Service("couponService") // 这个名称对应配置中的 beanName
 *     public class CouponService {
 *         // 校验优惠券是否有效
 *         public Boolean isCouponValid(Object value) {
 *             if (value == null) return false;
 *             String couponCode = String.valueOf(value);
 *             // 实际业务逻辑：查数据库或调用第三方接口
 *             // return couponMapper.exists(couponCode);
 *             return "8888".equals(couponCode); // 模拟逻辑
 *         }
 *     }
 *
 */
@RequiredArgsConstructor
@Slf4j
public class RemoteValidator implements FieldValidator {

    private final ApplicationContext applicationContext;




    @SuppressWarnings("unchecked")
    @Override
    public List<String> validate(ValidationContext context) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> cfg = context.getConfig();
        if (!cfg.containsKey("remoteRules")) return errors;

        List<Map<String, Object>> rules = (List<Map<String, Object>>) cfg.get("remoteRules");
        for (Map<String, Object> rule : rules) {
            String beanName = (String) rule.get("beanName");
            String methodName = (String) rule.get("methodName");
            String field = (String) rule.get("field"); // 校验哪个字段
            String errorMsg = (String) rule.get("error");

            Object value = context.getEntities().get(field);
            if (value == null) continue;

            try {
                // 从 Spring 上下文获取 Bean 并反射调用
                Object bean = applicationContext.getBean(beanName);
                // 约定：校验方法接收一个 Object 参数，返回 Boolean
                java.lang.reflect.Method method = bean.getClass().getMethod(methodName, Object.class);
                Boolean isValid = (Boolean) method.invoke(bean, value);

                if (Boolean.FALSE.equals(isValid)) {
                    errors.add(errorMsg);
                }
            } catch (Exception e) {
                log.error("Remote validation failed for bean: {}, method: {}", beanName, methodName, e);
                // 注意：外部校验失败可以根据业务决定是报错还是放行
            }
        }
        return errors;
    }
}
