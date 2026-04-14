package com.jparlant.service.flow.handler.action;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jparlant.constant.ContextKeys;
import com.jparlant.model.AgentFlow;
import com.jparlant.model.FlowContext;
import com.jparlant.model.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产级动作分发器
 * 支持复杂对象组装、集合转换、JsonPath深度提取
 */
@Slf4j
@Component
public class ActionDispatcher {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final Configuration jsonPathConfig;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    // 缓存方法元数据
    private final Map<String, MethodMeta> methodCache = new ConcurrentHashMap<>();

    private static final java.util.regex.Pattern ARRAY_PATTERN = java.util.regex.Pattern.compile("^(.+)\\[(\\d+)\\]$");

    public ActionDispatcher(ApplicationContext applicationContext, ObjectMapper objectMapper) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.jsonPathConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider(objectMapper))
                .mappingProvider(new JacksonMappingProvider(objectMapper))
                .build();
    }

    private record MethodMeta(Method method, String[] parameterNames, Type[] genericParameterTypes) {}



    /**
     * 接收内存中的 sessionState，返回更新后的内存对象
     */
    public Mono<SessionState> execute(AgentFlow.FlowStep.ActionCall action, SessionState sessionState) {
        return Mono.defer(() -> {
            FlowContext flowContext = getFlowContext(sessionState);
            Object bean = getBean(action.targetProcessor());
            MethodMeta meta = getMethodMeta(action, bean);

            // 1. 解析参数
            Object[] args = resolveArgsViaJsonPath(meta, action.inputMapping(), flowContext);

            // 2. 反射执行
            Object result = ReflectionUtils.invokeMethod(meta.method(), bean, args);

            // 3. 处理结果并更新内存中的 flowContext
            return handleResult(result, action.outputMapping(), flowContext)
                    .then(Mono.fromCallable(() -> sessionState.withVariable(ContextKeys.Session.FLOW_CONTEXT, flowContext.toMap())));
        });
    }


    /**
     * 支持外部直接传入参数，常用于 OCR 等直接处理文件的场景
     */
    public Mono<SessionState> executeWithArgs(AgentFlow.FlowStep.ActionCall action, SessionState sessionState, Object[] args) {
        return Mono.defer(() -> {
            FlowContext flowContext = getFlowContext(sessionState);
            Object bean = getBean(action.targetProcessor());
            MethodMeta meta = getMethodMeta(action, bean);

            // 直接执行，不调用 resolveArgsViaJsonPath
            Object result = ReflectionUtils.invokeMethod(meta.method(), bean, args);

            // 处理结果并更新 SessionState
            return handleResult(result, action.outputMapping(), flowContext)
                    .then(Mono.fromCallable(() ->
                            sessionState.withVariable(ContextKeys.Session.FLOW_CONTEXT, flowContext.toMap())
                    ));
        });
    }


    /**
     * 统一处理执行结果（支持同步对象和 Reactor Mono）
     *
     * @param result         反射调用返回的原始对象
     * @param outputMapping  输出映射配置，例如 {"$.planList": "$.repayment_schedule"}
     * @param flowContext    业务流程上下文
     * @return Mono<Void>    处理完成的信号
     */
    private Mono<Void> handleResult(Object result, Map<String, Object> outputMapping, FlowContext flowContext) {
        if (result == null) {
            log.debug("Action 执行结果为空，跳过输出映射");
            return Mono.empty();
        }

        // 情况 A：处理异步结果 (Mono)
        if (result instanceof Mono<?> monoResult) {
            return monoResult
                    .flatMap(actualData -> {
                        log.debug("异步 Action 执行完毕，开始数据回填");
                        applyDeepOutputs(outputMapping, actualData, flowContext);
                        return Mono.empty();
                    })
                    .then(); // 显式转为 Mono<Void>
        }

        // 情况 B：处理同步结果 (普通 POJO/Map/List)
        return Mono.fromRunnable(() -> {
            log.debug("同步 Action 执行完毕，开始数据回填");
            applyDeepOutputs(outputMapping, result, flowContext);
        });
    }


    /**
     * 基于 JsonPath 的参数解析
     */
    private Object[] resolveArgsViaJsonPath(MethodMeta meta, Map<String, String> inputMapping, FlowContext flowContext) {
        String[] paramNames = meta.parameterNames();
        Type[] targetTypes = meta.genericParameterTypes();
        Object[] args = new Object[paramNames.length];

        // 获取 entities 引用
        Map<String, Object> entities = flowContext.getEntities();

        for (int i = 0; i < paramNames.length; i++) {
            String pName = paramNames[i];
            String sourcePath = inputMapping.get(pName);

            // 如果没有配置映射，或者 entities 本身为 null，则该参数设为 null
            if (sourcePath == null || entities == null) {
                args[i] = null;
                continue;
            }

            try {
                // 1. 处理路径前缀：确保路径相对于 entities 根节点
                // 如果配置写的是 $.entities.loan，自动修正为 $.loan，因为我们要直接搜 entities Map
                String effectivePath = sourcePath;
                if (sourcePath.startsWith("$.entities.")) {
                    effectivePath = "$." + sourcePath.substring(11);
                }

                // 2. 使用 JsonPath 从 entities 中提取数据
                Object rawVal = JsonPath.using(jsonPathConfig).parse(entities).read(effectivePath);

                if (rawVal != null) {
                    // 3. 利用 Jackson 将提取出的对象（Map/List/BasicType）转换为方法参数定义的强类型
                    // 这能完美支持 List<Entity>、嵌套 POJO、枚举以及基础数据类型
                    JavaType type = objectMapper.getTypeFactory().constructType(targetTypes[i]);
                    args[i] = objectMapper.convertValue(rawVal, type);
                } else {
                    args[i] = null;
                }
            } catch (PathNotFoundException e) {
                log.error("参数 {} 对应的路径 {} 在 entities 中不存在", pName, sourcePath);
                args[i] = null;
            } catch (Exception e) {
                log.error("参数 {} 解析转换失败: path={}, error={}", pName, sourcePath, e.getMessage());
                args[i] = null;
            }
        }
        return args;
    }


    /**
     * 支持深度写入到 FlowContext (例如将结果写入 $.risk_assessment.score)
     */
    @SuppressWarnings("unchecked")
    private void applyDeepOutputs(Map<String, Object> outputMapping, Object result, FlowContext flowContext) {
        if (outputMapping == null || result == null) return;

        // 先将原始结果（可能是 POJO）转为 Map，这样 JsonPath 就能像读取 JSON 一样读取它了
        // 如果是 String, Number, Boolean, Map, Collection，直接使用，不再 convertValue
        Object document;
        if (result instanceof Map || result instanceof Collection ||
                result instanceof String || result instanceof Number || result instanceof Boolean) {
            document = result;
        } else {
            try {
                // 只有真正的 POJO 对象才尝试转为 Map
                document = objectMapper.convertValue(result, Map.class);
            } catch (Exception e) {
                // 如果转换失败，降级使用原始对象
                log.warn("无法将结果转换为Map结构，将使用原始值处理: {}", result);
                document = result;
            }
        }

        Object finalDocument = document;
        outputMapping.forEach((sourcePath, config) -> {
            try {
                Object rawValue;
                String targetPath;

                if (config instanceof String tPath) {
                    // 使用转换后的 document
                    rawValue = safeReadJsonPath(finalDocument, sourcePath);
                    targetPath = tPath;
                } else if (config instanceof Map mapConfig) {
                    targetPath = (String) mapConfig.get("target");
                    // 使用转换后的 document
                    Object sourceData = safeReadJsonPath(finalDocument, sourcePath);

                    if ("ARRAY".equals(mapConfig.get("type")) && sourceData instanceof Collection<?> sourceList) {
                        Map<String, String> elementMapping = (Map<String, String>) mapConfig.get("elementMapping");
                        rawValue = convertCollection(sourceList, elementMapping);
                    } else {
                        rawValue = sourceData;
                    }
                } else {
                    return;
                }

                Object serializedValue = objectMapper.convertValue(rawValue, Object.class);
                setNestedValue(flowContext.getEntities(), targetPath, serializedValue);
            } catch (Exception e) {
                log.error("结果回填失败: source={}, error={}", sourcePath, e.getMessage());
            }
        });
    }


    /**
     * 安全地读取 JsonPath
     */
    private Object safeReadJsonPath(Object document, String sourcePath) {
        if (".".equals(sourcePath)) {
            return document;
        }

        // 如果 document 是 String 等基础类型，但 sourcePath 又不是 "."
        // 说明配置要求从一个普通字符串里用 JsonPath 提取属性（如从 "error" 字符串里提 $.name）
        // 这在逻辑上是不可能的，直接返回 null 并记录警告
        if (document instanceof String || document instanceof Number || document instanceof Boolean) {
            log.warn("数据源是基础类型，无法执行 JsonPath 提取: path={}, data={}", sourcePath, document);
            return null;
        }

        try {
            return JsonPath.using(jsonPathConfig).parse(document).read(sourcePath);
        } catch (Exception e) {
            // 捕获 JsonPath 找不到路径等异常，防止整个流程崩溃
            return null;
        }
    }


    /**
     * 集合元素属性对齐转换
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertCollection(Collection<?> sourceList, Map<String, String> elementMapping) {
        List<Map<String, Object>> resultList = new ArrayList<>();
        for (Object item : sourceList) {
            // 确保 item 是 Map 结构
            Map<String, Object> itemDoc = objectMapper.convertValue(item, Map.class);
            Map<String, Object> targetItem = new HashMap<>();

            if (elementMapping == null || elementMapping.isEmpty()) {
                resultList.add(itemDoc);
                continue;
            }

            elementMapping.forEach((srcSubPath, targetSubPath) -> {
                try {
                    // 在 itemDoc 上执行读取
                    Object val = JsonPath.using(jsonPathConfig).parse(itemDoc).read(srcSubPath);
                    String key = targetSubPath.startsWith("$.") ? targetSubPath.substring(2) : targetSubPath;
                    targetItem.put(key, val);
                } catch (Exception e) {
                    log.info("集合项子属性转换跳过: {}", srcSubPath);
                }
            });
            resultList.add(targetItem);
        }
        return resultList;
    }

    /**
     * 深度写入嵌套数据，支持对象(.)和数组([n])
     * 示例路径：$.entities.items[0].status 或 $.loan_request.amount
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> rootMap, String path, Object value) {
        // 1. 清理路径前缀
        String cleanPath = path;
        if (path.startsWith("$.entities.")) {
            cleanPath = path.substring(11);
        } else if (path.startsWith("$.")) {
            cleanPath = path.substring(2);
        }

        String[] segments = cleanPath.split("\\.");
        Object current = rootMap;

        for (int i = 0; i < segments.length - 1; i++) {
            current = navigateNext(current, segments[i]);
        }

        // 2. 最后一段写入最终值
        writeFinalValue(current, segments[segments.length - 1], value);
    }

    /**
     * 导航到下一层级（创建 Map 或 List）
     */
    @SuppressWarnings("unchecked")
    private Object navigateNext(Object current, String segment) {
        java.util.regex.Matcher matcher = ARRAY_PATTERN.matcher(segment);

        if (matcher.matches()) {
            // A. 处理数组路径，如 "items[0]"
            String fieldName = matcher.group(1);
            int index = Integer.parseInt(matcher.group(2));

            // 确保父 Map 中有这个 List
            Map<String, Object> parentMap = (Map<String, Object>) current;
            List<Object> list = (List<Object>) parentMap.computeIfAbsent(fieldName, k -> new ArrayList<>());

            // 确保 List 长度足够，并填充 null 或新 Map
            ensureListSize(list, index);

            if (list.get(index) == null) {
                list.set(index, new HashMap<String, Object>());
            }
            return list.get(index);
        } else {
            // B. 处理普通对象路径，如 "loan_request"
            Map<String, Object> parentMap = (Map<String, Object>) current;
            return parentMap.computeIfAbsent(segment, k -> new HashMap<String, Object>());
        }
    }

    /**
     * 在最后一段执行写入
     */
    @SuppressWarnings("unchecked")
    private void writeFinalValue(Object current, String lastSegment, Object value) {
        java.util.regex.Matcher matcher = ARRAY_PATTERN.matcher(lastSegment);

        if (matcher.matches()) {
            // 最后一级是数组，如 ...items[0] = value
            String fieldName = matcher.group(1);
            int index = Integer.parseInt(matcher.group(2));

            Map<String, Object> parentMap = (Map<String, Object>) current;
            List<Object> list = (List<Object>) parentMap.computeIfAbsent(fieldName, k -> new ArrayList<>());

            ensureListSize(list, index);
            list.set(index, value);
        } else {
            // 最后一级是普通字段，如 ...status = value
            Map<String, Object> parentMap = (Map<String, Object>) current;
            parentMap.put(lastSegment, value);
        }
    }

    /**
     * 辅助方法：确保 ArrayList 达到指定下标长度
     */
    private void ensureListSize(List<Object> list, int index) {
        while (list.size() <= index) {
            list.add(null);
        }
    }



    private Object getBean(String targetProcessor) {
        String beanName = targetProcessor.split("\\.")[0];
        // 生产环境建议：此处应校验 beanName 是否带有 @FlowAction 注解，防止越权调用
        return applicationContext.getBean(beanName);
    }

    private MethodMeta getMethodMeta(AgentFlow.FlowStep.ActionCall action, Object bean) {
        String cacheKey = action.targetProcessor() + ":" + action.inputMapping().keySet();
        return methodCache.computeIfAbsent(cacheKey, k -> {
            String methodName = action.targetProcessor().split("\\.")[1];
            Method matched = Arrays.stream(bean.getClass().getMethods())
                    .filter(m -> m.getName().equals(methodName))
                    // 生产环境：可以增加参数个数或注解匹配以支持重载
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("未找到方法: " + methodName));

            String[] paramNames = parameterNameDiscoverer.getParameterNames(matched);
            if (paramNames == null) {
                // 降级处理：获取反射定义的参数名
                paramNames = Arrays.stream(matched.getParameters()).map(java.lang.reflect.Parameter::getName).toArray(String[]::new);
            }
            return new MethodMeta(matched, paramNames, matched.getGenericParameterTypes());
        });
    }

    @SuppressWarnings("unchecked")
    private FlowContext getFlowContext(SessionState sessionState) {
        Map<String, Object> contextMap = (Map<String, Object>) sessionState.variables().get(ContextKeys.Session.FLOW_CONTEXT);
        return FlowContext.fromMap(contextMap);
    }

}