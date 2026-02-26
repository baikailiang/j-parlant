package com.jparlant.service.flow.handler.action;

import com.jparlant.annation.FlowAction;
import com.jparlant.annation.FlowMethod;
import com.jparlant.annation.FlowProperty;
import com.jparlant.model.BeanWithMethods;
import com.jparlant.model.MethodMetadata;
import com.jparlant.model.PropertySchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class FlowMetadataService {

    private final ApplicationContext applicationContext;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public FlowMetadataService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private List<BeanWithMethods> cache;

    public List<BeanWithMethods> getAllActionsFromMetadata() {
        if (cache != null) return cache;

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(FlowAction.class);

        List<BeanWithMethods> result = beans.entrySet().stream().map(entry -> {
            String beanName = entry.getKey(); // Spring Bean ID (e.g. loanCalculatorService)
            Object beanInstance = entry.getValue();
            Class<?> beanClass = beanInstance.getClass();

            // 如果是 CGLIB 代理类，获取原始类
            if (beanClass.getName().contains("$$")) {
                beanClass = beanClass.getSuperclass();
            }

            FlowAction beanAnno = beanClass.getAnnotation(FlowAction.class);
            String beanDisplayName = (beanAnno != null && !beanAnno.value().isEmpty())
                    ? beanAnno.value() : beanName;

            List<MethodMetadata> methods = Arrays.stream(beanClass.getMethods())
                    .filter(m -> m.isAnnotationPresent(FlowMethod.class))
                    .map(this::parseMethodToDTO)
                    .collect(Collectors.toList());

            return new BeanWithMethods(beanName, beanDisplayName, methods);
        }).collect(Collectors.toList());

        this.cache = result;
        return result;
    }

    private MethodMetadata parseMethodToDTO(Method method) {
        FlowMethod methodAnno = method.getAnnotation(FlowMethod.class);
        String methodDisplayName = (methodAnno != null && !methodAnno.value().isEmpty())
                ? methodAnno.value() : method.getName();
        String description = (methodAnno != null && !methodAnno.description().isEmpty())
                ? methodAnno.description() : method.getName();
        return new MethodMetadata(
                method.getName(),
                methodDisplayName,
                description,
                resolveParameters(method),
                resolveReturns(method) // 这里的逻辑已修改
        );
    }

    private List<PropertySchema> resolveParameters(Method method) {
        String[] names = parameterNameDiscoverer.getParameterNames(method);
        Parameter[] reflectionParams = method.getParameters();
        Type[] types = method.getGenericParameterTypes();

        List<PropertySchema> schemas = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            String pName = (names != null && names.length > i) ? names[i] : "arg" + i;
            FlowProperty propAnno = reflectionParams[i].getAnnotation(FlowProperty.class);
            String description = (propAnno != null) ? propAnno.value() : "";

            schemas.add(doResolveTypeSchema(pName, types[i], description, new HashSet<>()));
        }
        return schemas;
    }

    /**
     * 关键修改：平铺返回结果
     */
    private List<PropertySchema> resolveReturns(Method method) {
        List<PropertySchema> returns = new ArrayList<>();
        Class<?> returnType = method.getReturnType();
        Type genericReturnType = method.getGenericReturnType();

        if (returnType == void.class || returnType == Void.class) {
            return returns;
        }

        // 如果是简单类型或集合类型，保持原样（包装在一个 result 节点或按需处理）
        if (isSimpleType(returnType) || Collection.class.isAssignableFrom(returnType) || returnType.isArray()) {
            returns.add(doResolveTypeSchema("result", genericReturnType, "返回结果", new HashSet<>()));
        } else {
            // 如果是 POJO 对象，展开其字段（实现 JSON 2 的平铺效果）
            Field[] fields = returnType.getDeclaredFields();
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    FlowProperty fieldAnno = field.getAnnotation(FlowProperty.class);
                    if (fieldAnno != null) {
                        returns.add(doResolveTypeSchema(field.getName(), field.getGenericType(), fieldAnno.value(), new HashSet<>()));
                    }
                }
            }
        }
        return returns;
    }

    private PropertySchema doResolveTypeSchema(String name, Type type, String description, Set<Type> visited) {
        Class<?> clazz = getRawClass(type);

        // 1. 类型映射：映射为 JSON 2 标准的大写字符串
        String mappedType = mapToStandardType(clazz);

        // 2. 复杂度判定
        boolean isComplex = !isSimpleType(clazz);
        List<PropertySchema> children = new ArrayList<>();

        if (isComplex && visited.contains(type)) {
            return new PropertySchema(name, mappedType, description + " [Circular]", true, Collections.emptyList());
        }

        // 如果是集合，递归解析泛型内容（JSON 2 中 List<Item> 的 children 包含 Item 的属性）
        if (isComplex && (Collection.class.isAssignableFrom(clazz) || clazz.isArray())) {
            visited.add(type);
            if (type instanceof ParameterizedType pt) {
                Type actualType = pt.getActualTypeArguments()[0];
                // 集合的 children 存放的是元素的属性结构
                PropertySchema itemSchema = doResolveTypeSchema("item", actualType, "", new HashSet<>(visited));
                if (itemSchema.complex()) {
                    children.addAll(itemSchema.children());
                }
            }
        }
        // 如果是普通 POJO 对象
        else if (isComplex) {
            visited.add(type);
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    FlowProperty fieldAnno = field.getAnnotation(FlowProperty.class);
                    if (fieldAnno != null) {
                        children.add(doResolveTypeSchema(field.getName(), field.getGenericType(), fieldAnno.value(), new HashSet<>(visited)));
                    }
                }
            }
        }

        return new PropertySchema(
                name,
                mappedType,
                description,
                isComplex,
                children
        );
    }

    /**
     * 适配 JSON 2 的类型命名规范
     */
    private String mapToStandardType(Class<?> clazz) {
        if (Number.class.isAssignableFrom(clazz) || clazz.isPrimitive() && clazz != boolean.class) {
            if (clazz == boolean.class) return "BOOLEAN"; // 排除 boolean
            return "NUMBER";
        }
        if (clazz == String.class) return "STRING";
        if (clazz == Boolean.class || clazz == boolean.class) return "BOOLEAN";
        if (Collection.class.isAssignableFrom(clazz) || clazz.isArray()) return "ARRAY";
        return clazz.getSimpleName().toUpperCase(); // 其他类型返回大写类名
    }

    private boolean isSimpleType(Class<?> clazz) {
        return clazz.isPrimitive() ||
                clazz.equals(String.class) ||
                Number.class.isAssignableFrom(clazz) ||
                clazz.equals(Boolean.class) ||
                clazz.equals(Date.class) ||
                clazz.isEnum();
    }

    private Class<?> getRawClass(Type type) {
        if (type instanceof Class<?>) return (Class<?>) type;
        if (type instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
        return Object.class;
    }
}