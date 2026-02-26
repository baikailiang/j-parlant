package com.jparlant.service.compliance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jparlant.entity.ComplianceRuleEntity;
import com.jparlant.model.ComplianceRule;
import com.jparlant.model.Context;
import com.jparlant.model.SessionState;
import com.jparlant.repository.ComplianceRuleRepository;
import com.jparlant.service.flow.evaluator.FlowExpressionEvaluator;
import com.jparlant.service.session.SessionStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 合规引擎 - 确保AI行为的确定性和合规性
 * 实现硬性约束条件，防止不合规的响应
 */
@Slf4j
public class ComplianceEngine {
    
    private final SessionStateManager sessionStateManager;
    private final ComplianceRuleRepository ruleRepository;
    private final FlowExpressionEvaluator flowExpressionEvaluator;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;



    /**
     * 规则缓存：加载时即按优先级从低到高（数字越小优先级越高）排序
     */
    private final Cache<Long, List<ComplianceRule>> agentRulesCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(Duration.ofDays(1))
            .build();

    /**
     * 正则模式缓存：避免重复编译正则提升性能
     */
    private final Cache<String, Pattern> regexPatternCache = Caffeine.newBuilder()
            .maximumSize(500).build();

    /**
     * 违规记录：设置内存护栏，防止 OOM
     */
    private final Cache<String, List<ComplianceViolation>> violationHistory = Caffeine.newBuilder()
            .maximumSize(1000) // 总共最多记录 1000 条违规，超过后自动剔除旧的
            .expireAfterWrite(Duration.ofDays(7)) // 记录仅保留 7 天
            .build();


    private static final String REGEX_KEY = "regex_patterns";

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};


    public ComplianceEngine(SessionStateManager sessionStateManager, ComplianceRuleRepository ruleRepository,
                            ChatClient chatClient, ObjectMapper objectMapper,
                            FlowExpressionEvaluator flowExpressionEvaluator) {
        this.sessionStateManager = sessionStateManager;
        this.ruleRepository = ruleRepository;
        this.flowExpressionEvaluator = flowExpressionEvaluator;
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
    }


    /**
     * 外部入口：输入合规检查
     */
    public Mono<ComplianceCheckResult> checkInputCompliance(String userInput, Context context) {
        return sessionStateManager.getSessionState(context)
                .flatMap(state -> evaluateComplianceRules(userInput, context, state, ComplianceRule.ComplianceScope.INPUT))
                .defaultIfEmpty(ComplianceCheckResult.pass());
    }

    /**
     * 外部入口：响应合规检查
     */
    public Mono<ComplianceCheckResult> checkResponseCompliance(String response, Context context) {
        return sessionStateManager.getSessionState(context)
                .flatMap(state -> evaluateComplianceRules(response, context, state, ComplianceRule.ComplianceScope.RESPONSE))
                .defaultIfEmpty(ComplianceCheckResult.pass());
    }

    /**
     * 获取规则：非阻塞加载
     */
    private Mono<List<ComplianceRule>> getRules(Long agentId) {
        List<ComplianceRule> cached = agentRulesCache.getIfPresent(agentId);
        if (cached != null) return Mono.just(cached);

        return ruleRepository.findByAgentIdAndEnabledTrueOrderByPriorityAsc(agentId)
                .map(this::toDomain)
                .collectList()
                .doOnNext(list -> agentRulesCache.put(agentId, list));
    }


    /**
     * 核心评估引擎：漏斗式+短路匹配
     */
    private Mono<ComplianceCheckResult> evaluateComplianceRules(String content, Context context, SessionState sessionState, ComplianceRule.ComplianceScope scope) {
        if (!StringUtils.hasText(content)) return Mono.just(ComplianceCheckResult.pass());

        return getRules(context.agentId())
                .flatMapMany(Flux::fromIterable)
                .filter(rule -> isScopeMatch(rule, scope))
                // 使用 concatMap 确保严格按优先级顺序执行异步检查
                .concatMap(rule -> evaluateSingleRule(rule, content, sessionState)
                        .map(isViolated -> isViolated ? ComplianceCheckResult.blocked(rule) : ComplianceCheckResult.pass()))
                // 只要发现第一个违规就短路停止
                .filter(result -> !result.isCompliant())
                .next()
                .doOnNext(res -> {
                    if (!res.isCompliant()) {
                        recordViolation(res.triggeredRule(), content, context, sessionState.phase());
                    }
                })
                .defaultIfEmpty(ComplianceCheckResult.pass());
    }

    /**
     * 单条规则分层评估
     */
    private Mono<Boolean> evaluateSingleRule(ComplianceRule rule, String content, SessionState sessionState) {
        // 1. 关键词 (O(1) 级开销)
        if (checkKeywords(rule, content)) return Mono.just(true);

        // 2. 正则 (O(N) 级开销)
        if (checkRegex(rule, content)) return Mono.just(true);

        // 3. SpEL (表达式解析)
        if (StringUtils.hasText(rule.condition())) {
            if (checkSpel(rule, content, sessionState)) return Mono.just(true);
        }

        // 4. LLM 语义检查 (最高开销，涉及网络 IO)
        if (StringUtils.hasText(rule.guideline())) {
            return checkLlmCompliance(rule, content);
        }

        return Mono.just(false);
    }

    private boolean checkKeywords(ComplianceRule rule, String content) {
        if (CollectionUtils.isEmpty(rule.keywords())) return false;
        String lowerContent = content.toLowerCase();
        return rule.keywords().stream().anyMatch(kw -> lowerContent.contains(kw.toLowerCase()));
    }

    @SuppressWarnings("unchecked")
    private boolean checkRegex(ComplianceRule rule, String content) {
        Object patterns = rule.parameters().get(REGEX_KEY);
        if (!(patterns instanceof List)) return false;

        List<String> regexes = (List<String>) patterns;
        for (String regex : regexes) {
            Pattern p = regexPatternCache.get(regex, Pattern::compile);
            if (p != null && p.matcher(content).find()) return true;
        }
        return false;
    }

    private boolean checkSpel(ComplianceRule rule, String content, SessionState sessionState) {
        try {
            if (!StringUtils.hasText(rule.condition())) {
                return false;
            }

            // 构造变量池
            Map<String, Object> variables = new java.util.HashMap<>();
            if (sessionState.variables() != null) {
                variables.putAll(sessionState.variables());
            }
            // 额外注册 content
            variables.put("content", content);
            // 复用 FlowExpressionEvaluator
            return flowExpressionEvaluator.evaluate(rule.condition(), variables);

        } catch (Exception e) {
            log.error("SpEL评估失败: rule={}, err={}", rule.name(), e.getMessage());
            return false;
        }
    }


    private Mono<Boolean> checkLlmCompliance(ComplianceRule rule, String content) {
        return Mono.fromCallable(() -> {
                    log.info("触发 LLM 深度合规审查: rule={}", rule.name());
                    String systemPrompt = """
                你是一个严谨的合规审查专家。判定标准：【%s】。
                如果内容违反标准回复: TRUE，否则回复: FALSE。只允许输出 TRUE 或 FALSE。
                """.formatted(rule.guideline());

                    String result = chatClient.prompt()
                            .system(systemPrompt)
                            .user("检查内容：" + content)
                            .call()
                            .content();

                    return result != null && result.trim().toUpperCase().contains("TRUE");
                })
                .subscribeOn(Schedulers.boundedElastic()) // 必须在 I/O 线程池运行阻塞的 LLM 调用
                .timeout(Duration.ofSeconds(5)) // 设置超时，防止 LLM 卡死整个链路
                .onErrorReturn(false); // 超时或报错降级为通过（或根据业务改为拦截）
    }

    private boolean isScopeMatch(ComplianceRule rule, ComplianceRule.ComplianceScope currentScope) {
        if (rule.scope() == null || rule.scope() == ComplianceRule.ComplianceScope.ALL) return true;
        return rule.scope() == currentScope;
    }

    private void recordViolation(ComplianceRule rule, String content, Context context, SessionState.SessionPhase phase) {
        ComplianceViolation violation = new ComplianceViolation(
                rule.id(), rule.name(), content,
                context.sessionId(), context.userId(), context.agentId(),
                phase, LocalDateTime.now()
        );
        violationHistory.asMap().compute(context.sessionId(), (sid, list) -> {
            List<ComplianceViolation> vList = (list == null) ? Collections.synchronizedList(new ArrayList<>()) : list;
            vList.add(violation);
            return vList;
        });
        log.warn("DETECTED VIOLATION: Rule={}, User={}, Content={}", rule.name(), context.userId(), content);
    }

    public ComplianceRule toDomain(ComplianceRuleEntity entity) {
        return new ComplianceRule(
                entity.getId(),
                entity.getAgentId(),
                entity.getName(),
                entity.getDescription(),
                safeParseEnum(ComplianceRule.ComplianceScope.class, entity.getScope(), ComplianceRule.ComplianceScope.ALL),
                safeParseJson(entity.getKeywords(), LIST_TYPE, List.of()),
                safeParseJson(entity.getParameters(), MAP_TYPE, Map.of()),
                entity.getConditionExpr(),
                entity.getBlockedResponse(),
                safeParseJson(entity.getCategories(), LIST_TYPE, List.of()),
                entity.isEnabled(),
                entity.getPriority(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getGuidelinePrompt()
        );
    }

    private <E extends Enum<E>> E safeParseEnum(Class<E> enumClass, String value, E defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Enum.valueOf(enumClass, value.toUpperCase());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private <T> T safeParseJson(String json, TypeReference<T> typeRef, T defaultValue) {
        if (!StringUtils.hasText(json)) return defaultValue;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public Mono<List<ComplianceViolation>> getViolationHistory(String sessionId) {
        return Mono.fromCallable(() -> {
            List<ComplianceViolation> list = violationHistory.getIfPresent(sessionId);
            return list == null ? Collections.<ComplianceViolation>emptyList() : new ArrayList<>(list);
        });
    }

    /**
     * 1. 刷新特定 Agent 的规则缓存
     */
    public void refreshRules(Long agentId) {
        log.info("Invalidating compliance rules cache for agent: {}", agentId);
        agentRulesCache.invalidate(agentId);
    }

    /**
     * 2. 刷新全局所有 Agent 的规则缓存
     * 用于合规规则库大版本更新
     */
    public void refreshAllRules() {
        log.info("Invalidating ALL compliance rules and regex patterns.");
        agentRulesCache.invalidateAll();
        regexPatternCache.invalidateAll(); // 同时清理正则编译缓存，释放内存
    }

    /**
     * 3. 清理特定会话的违规历史
     * 用于处理测试数据或用户重置会话
     */
    public void clearViolationHistory(String sessionId) {
        log.info("Clearing violation history for session: {}", sessionId);
        violationHistory.invalidate(sessionId);
    }

    /**
     * 4. 彻底重置合规引擎
     * 极端情况下（如内存泄漏排查或引擎配置大改）使用
     */
    public void resetEngine() {
        agentRulesCache.invalidateAll();
        regexPatternCache.invalidateAll();
        violationHistory.invalidateAll();
        log.warn("Compliance engine has been fully reset.");
    }




    // --- Inner Classes & Records ---

    public record ComplianceCheckResult(boolean isCompliant, ComplianceRule triggeredRule) {
        public static ComplianceCheckResult pass() { return new ComplianceCheckResult(true, null); }
        public static ComplianceCheckResult blocked(ComplianceRule rule) { return new ComplianceCheckResult(false, rule); }
    }

    public record ComplianceViolation(Long ruleId, String ruleName, String content, String sessionId,
                                      String userId, Long agentId, SessionState.SessionPhase phase,
                                      LocalDateTime timestamp) {}
}