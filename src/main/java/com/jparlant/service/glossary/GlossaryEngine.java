package com.jparlant.service.glossary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jparlant.entity.GlossaryEntity;
import com.jparlant.model.Context;
import com.jparlant.model.GlossaryModel;
import com.jparlant.repository.GlossaryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 词汇表引擎 - 智能检索和应用领域术语定义
 * 防止AI产生幻觉，确保术语使用的准确性
 */
@Slf4j
public class GlossaryEngine {
    
    private final GlossaryRepository glossaryRepository;
    private final ObjectMapper objectMapper;

    // 缓存：Key 为 agentId，Value 为封装好的匹配引擎
    private final Cache<Long, GlossaryMatcher> matcherCache;

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    public GlossaryEngine(GlossaryRepository glossaryRepository, ObjectMapper objectMapper) {
        this.glossaryRepository = glossaryRepository;
        this.objectMapper = objectMapper;
        this.matcherCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(Duration.ofHours(4))
                .build();
    }

    /**
     * 获取或加载匹配引擎（非阻塞）
     */
    private Mono<GlossaryMatcher> getMatcher(Long agentId) {
        GlossaryMatcher cached = matcherCache.getIfPresent(agentId);
        if (cached != null) return Mono.just(cached);

        return glossaryRepository.findByAgentId(agentId)
                .map(this::toModel)
                .collectList()
                .map(GlossaryMatcher::new)
                .doOnNext(matcher -> matcherCache.put(agentId, matcher))
                .doOnSubscribe(s -> log.info("开始加载 Agent {} 的术语库...", agentId));
    }

    // --- 辅助解析方法 ---

    /**
     * 实体转换
     */
    private GlossaryModel toModel(GlossaryEntity entity) {
        return new GlossaryModel(
                entity.id(),
                entity.name(),
                entity.definition(),
                entity.category(),
                parseJson(entity.synonyms(), LIST_TYPE, List.of()),
                parseJson(entity.relatedNames(), LIST_TYPE, List.of()),
                parseJson(entity.examples(), MAP_TYPE, Map.of()),
                entity.agentId(),
                entity.priority()
        );
    }

    private <T> T parseJson(String json, TypeReference<T> type, T defaultValue) {
        if (!StringUtils.hasText(json)) return defaultValue;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("术语表 JSON 解析失败: {}", json, e);
            return defaultValue;
        }
    }

    /**
     * 根据用户输入和上下文检索相关术语
     */
    public Flux<GlossaryModel> retrieveRelevantTerms(String userInput, Context context) {
        if (!StringUtils.hasText(userInput)) return Flux.empty();

        return getMatcher(context.agentId())
                .flatMapMany(matcher -> {
                    // 1. 执行正则匹配提取 ID
                    Set<Long> matchedIds = matcher.findMatches(userInput);
                    if (matchedIds.isEmpty()) return Flux.empty();

                    // 2. 联想匹配 (Related Terms)
                    Set<Long> finalIds = new HashSet<>(matchedIds);
                    for (Long id : matchedIds) {
                        GlossaryModel model = matcher.getById(id);
                        if (model != null && model.relatedNames() != null) {
                            for (String relatedName : model.relatedNames()) {
                                Long relatedId = matcher.getIdByTermName(relatedName);
                                if (relatedId != null) finalIds.add(relatedId);
                            }
                        }
                    }

                    // 3. 排序并限制数量 (防止上下文过长)
                    return Flux.fromIterable(finalIds)
                            .map(matcher::getById)
                            .filter(Objects::nonNull)
                            .sort(Comparator.comparingInt(GlossaryModel::priority))
                            .take(10);
                });
    }


    /**
     * 构建生产级 Prompt
     */
    public Mono<String> buildGlossaryPrompt(String userInput, Context context) {
        return retrieveRelevantTerms(userInput, context)
                .collectList()
                .map(list -> {
                    if (list.isEmpty()) return "";

                    StringBuilder sb = new StringBuilder("\n## 领域术语库 (Glossary Reference)\n");
                    sb.append("请在后续回答中严格遵循以下专业定义和用法限制：\n\n");

                    // 按分类分组显示，提高 AI 的逻辑理解速度
                    Map<String, List<GlossaryModel>> grouped = list.stream()
                            .collect(Collectors.groupingBy(GlossaryModel::category));

                    grouped.forEach((category, terms) -> {
                        sb.append(String.format("### [%s] 相关术语\n", category.toUpperCase()));
                        for (GlossaryModel g : terms) {
                            sb.append(String.format("- **%s**： %s\n", g.name(), g.definition()));

                            // 注入示例用法（examples 字段）
                            if (g.examples() != null && !g.examples().isEmpty()) {
                                g.examples().forEach((scenario, text) ->
                                        sb.append(String.format("  - *示例(%s)*: %s\n", scenario, text)));
                            }

                            // 提示相关关联（relatedTerms 字段）
                            if (g.relatedNames() != null && !g.relatedNames().isEmpty()) {
                                sb.append(String.format("  - *关联延伸*: %s\n", String.join(", ", g.relatedNames())));
                            }
                        }
                        sb.append("\n");
                    });
                    return sb.toString();
                });
    }


    /**
     * 1. 精准刷新：清理特定 Agent 的术语匹配引擎缓存
     * 当该 Agent 的术语表发生变更（增删改）时调用
     */
    public void refreshGlossary(Long agentId) {
        if (agentId == null) return;
        log.info("Invalidating glossary cache for agentId={}", agentId);
        matcherCache.invalidate(agentId);
    }

    /**
     * 2. 全局刷新：清理所有 Agent 的术语库缓存
     * 用于全局术语库升级或系统维护
     */
    public void refreshAll() {
        log.warn("Triggered global glossary cache invalidation.");
        matcherCache.invalidateAll();
    }


    /**
     * 内部匹配类：封装正则引擎与检索表
     */
    private static class GlossaryMatcher {
        private final Map<Long, GlossaryModel> idToModel = new HashMap<>();
        private final Map<String, Long> termToId = new HashMap<>(); // 支持原词和同义词到 ID 的映射
        private final Pattern compiledPattern;

        public GlossaryMatcher(List<GlossaryModel> models) {
            List<String> patternTokens = new ArrayList<>();

            for (GlossaryModel m : models) {
                idToModel.put(m.id(), m);
                termToId.put(m.name().toLowerCase(), m.id());
                patternTokens.add(Pattern.quote(m.name().toLowerCase()));

                // 处理同义词：提高匹配概率的关键
                if (m.synonyms() != null) {
                    for (String syn : m.synonyms()) {
                        termToId.put(syn.toLowerCase(), m.id());
                        patternTokens.add(Pattern.quote(syn.toLowerCase()));
                    }
                }
            }

            if (patternTokens.isEmpty()) {
                this.compiledPattern = Pattern.compile("NOTHING_MATCHED_RANDOM_REGEX");
            } else {
                // 优化：针对中文不加 \b，针对英文加 \b 边界符
                // 这里采用简单策略：根据术语是否包含英文字母决定是否加 \b
                List<String> processedTokens = new ArrayList<>();
                for (String token : patternTokens) {
                    // 检查术语是否包含英文字母
                    if (token.matches(".*[a-zA-Z].*")) {
                        // 包含英文，加单词边界 \b
                        processedTokens.add("\\b" + token + "\\b");
                    } else {
                        // 纯中文或特殊字符，不加 \b
                        processedTokens.add(token);
                    }
                }
                String regex = "(" + String.join("|", processedTokens) + ")";
                this.compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
            }
        }

        public Set<Long> findMatches(String input) {
            Set<Long> foundIds = new HashSet<>();
            if (input == null) return foundIds;
            Matcher matcher = compiledPattern.matcher(input.toLowerCase());
            while (matcher.find()) {
                String hit = matcher.group();
                Long id = termToId.get(hit);
                if (id != null) foundIds.add(id);
            }
            return foundIds;
        }

        public GlossaryModel getById(Long id) {
            return idToModel.get(id);
        }

        public Long getIdByTermName(String name) {
            return termToId.get(name.toLowerCase());
        }

    }

}