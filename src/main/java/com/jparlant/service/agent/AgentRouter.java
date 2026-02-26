package com.jparlant.service.agent;

import cn.hutool.json.JSONUtil;
import com.jparlant.model.*;
import com.jparlant.service.exception.IntentNotMatchedException;
import com.jparlant.service.history.ChatHistoryService;
import com.jparlant.service.session.SessionStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent核心调度器
 */
@RequiredArgsConstructor
@Slf4j
public class AgentRouter {


    private final AgentDefinitionManager definitionProvider;
    private final ChatClient chatClient;
    private final SessionStateManager sessionStateManager;
    private final ChatHistoryService chatHistoryService;



    /**
     * 预编译正则表达式，避免每次解析时重新编译，提高性能
     */
    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("\\d+");


    /**
     * 解析用户请求，管理 Session 生命周期，返回 Agent 和 Context
     * @param request 原始聊天请求
     * @return Agent 和 Context 的元组
     */
    public Mono<Tuple2<Agent, Context>> resolveAgentAndContext(ChatRequest request) {
        String userId = request.userId();
        // 1. 生成确定的 sessionId
        String sessionId = sessionStateManager.generateSessionId(userId);

        // todo 可以预加载一些激活标签和元数据
        List<String> tags = List.of();
        Map<String, Object> metadata = new HashMap<>();

        // 2. 预先构建一个基础 Context（agentId 暂空）
        Context baseContext = Context.create(sessionId, userId, null, tags, metadata);

        // 3. 尝试获取现有状态
        return sessionStateManager.getSessionState(baseContext)
                .defaultIfEmpty(SessionStateManager.initSessionState(sessionId, userId)) // 提供初始空状态，避免 switchIfEmpty
                .flatMap(state -> {
                    Long currentAgentId = state.agentId();

                    // 3. 识别目标 Agent
                    return identifyTargetAgentId(request.message(), state, request)
                            .flatMap(targetId -> {
                                // 情况 A: 没识别到新意图 且 之前也没有意图 -> 报错
                                if (isInvalidId(targetId) && isInvalidId(currentAgentId)) {
                                    // 抛出自定义异常
                                    return Mono.error(new IntentNotMatchedException("我暂时无法识别您的意图"));
                                }

                                // 情况 B: 意图没变，或识别结果为空（沿用当前） -> 直接加载当前 Agent
                                if (isInvalidId(targetId) || targetId.equals(currentAgentId)) {
                                    // 判断当前阶段是否为终结态
                                    if (isTerminalPhase(state.phase())) {
                                        log.info("检测到当前意图 [{}] 已处于终结阶段 ({})，用户发起新输入，正在重置状态...",
                                                currentAgentId, state.phase());

                                        // 强制走切换逻辑（即使目标 ID 没变），handleAgentSwitch 会执行清理和重新初始化
                                        return handleAgentSwitch(sessionId, userId, currentAgentId, null, tags, metadata);
                                    }

                                    return loadAgentAndContext(sessionId, userId, currentAgentId, tags, metadata);
                                }

                                // 情况 C: 意图变了 (新会话或意图切换)
                                return handleAgentSwitch(sessionId, userId, targetId, currentAgentId, tags, metadata);
                            });
                })
                .onErrorResume(e -> {
                    log.error("解析 Agent 和 Context 失败: {}", e.getMessage());
                    return Mono.error(e);
                });
    }

    /**
     * 判断是否为终结阶段
     */
    private boolean isTerminalPhase(SessionState.SessionPhase phase) {
        if (phase == null) return false;
        return phase == SessionState.SessionPhase.CLOSING ||
                phase == SessionState.SessionPhase.ARCHIVED ||
                phase == SessionState.SessionPhase.TERMINATED;
    }

    /**
     * 封装意图切换或初始化的核心逻辑
     */
    private Mono<Tuple2<Agent, Context>> handleAgentSwitch(String sessionId, String userId, Long targetId, Long currentId,
                                                           List<String> tags, Map<String, Object> metadata) {
        if (currentId != null) {
            log.info("检测到意图切换 [{} -> {}]，重置会话", currentId, targetId);
        } else {
            log.info("新会话识别成功: AgentId={}", targetId);
        }

        // 逻辑：清除旧状态 -> 获取新 Agent -> 初始化新状态 -> 返回结果
        return sessionStateManager.clearSessionState(sessionId)
                .then(chatHistoryService.clearHistory(sessionId))
                .then(definitionProvider.getAgentById(targetId))
                .flatMap(newAgent -> {
                    Context newCtx = buildFinalContext(sessionId, userId, newAgent.id(), tags, metadata);
                    return sessionStateManager.initializeAndSaveSession(newCtx)
                            .thenReturn(Tuples.of(newAgent, newCtx));
                });
    }


    /**
     * 加载现有 Agent 并构建 Context
     */
    private Mono<Tuple2<Agent, Context>> loadAgentAndContext(String sessionId, String userId, Long agentId,
                                                             List<String> tags, Map<String, Object> metadata) {
        return definitionProvider.getAgentById(agentId)
                .map(agent -> Tuples.of(agent, buildFinalContext(sessionId, userId, agent.id(), tags, metadata)));
    }

    private boolean isInvalidId(Long id) {
        return id == null || id <= 0L;
    }


    /**
     * 关键词 + LLM 语义路由
     */
    private Mono<Long> identifyTargetAgentId(String userInput, SessionState sessionState, ChatRequest request) {
        // 如果没有文字但是有媒体文件，且当前有 Agent，直接返回当前 ID
        if (request.hasMedia() && !isInvalidId(sessionState.agentId())) {
            return Mono.just(sessionState.agentId());
        }

        return definitionProvider.getAllActiveAgents()
                .collectList()
                .flatMap(agents -> {
                    // 1. 快速关键词路径
                    for (Agent agent : agents) {
                        if (StringUtils.hasText(agent.keywords())) {
                            for (String kw : agent.keywords().split(",")) {
                                if (userInput.contains(kw.trim())) return Mono.just(agent.id());
                            }
                        }
                    }
                    // 2. LLM 语义路径
                    return callLlmForRouting(userInput, agents, sessionState);
                });
    }


    /**
     * 利用 LLM 进行语义分类路由
     */
    private Mono<Long> callLlmForRouting(String userInput, List<Agent> agents, SessionState sessionState) {
        // 构造候选 Agent 列表
        String agentOptions = agents.stream()
                .map(a -> String.format("- ID: %d, 名称: %s, 职责描述: %s", a.id(), a.name(), a.description()))
                .collect(Collectors.joining("\n"));

        return chatHistoryService.getConversationHistory(sessionState.sessionId(), 5)
                .collectList()
                .flatMap(historyList -> {

                    // 从历史中提取最后一条 AI 回复内容
                    String extractedLastAiResponse = historyList.stream()
                            .filter(ChatMessage::isAI) // 过滤出 AI 的消息
                            .map(ChatMessage::content)
                            .reduce((first, second) -> second) // 获取最后一条
                            .orElse("（尚无 AI 历史回复）");

                    // 4. 格式化对话快照（快照只展示最近 3 条，保持 Prompt 清爽）
                    int snapshotSize = 3;
                    List<ChatMessage> snapshotList = historyList.size() > snapshotSize
                            ? historyList.subList(historyList.size() - snapshotSize, historyList.size())
                            : historyList;

                    String historySnapshot = snapshotList.isEmpty() ? "（无对话记录）" :
                            snapshotList.stream()
                                    .filter(m -> m.isUser() || m.isAI())
                                    .map(m -> (m.isUser() ? "用户: " : "AI: ") + m.content())
                                    .collect(Collectors.joining("\n"));

                    // 构造上下文信息 (筛选对路由最有帮助的字段)
                    String contextInfo = String.format("""
                        - 当前负责 Agent ID: %s
                        - 业务数据采集现状: %s
                        - 最近对话快照:
                        ---
                        %s
                        ---
                        - AI 上次回复: %s
                        """,
                            sessionState.agentId() != null ? sessionState.agentId() : 0,
                            sessionState.variables().isEmpty() ? "暂无有效信息" : JSONUtil.toJsonPrettyStr(sessionState.variables()),
                            historySnapshot,
                            extractedLastAiResponse);

                    // 构造专业级路由 Prompt
                    String routingPrompt = """
                        # Role
                        你是一个高智能客服分流中心。你的任务是根据历史语境和用户最新输入，从候选 Agent 列表中选出最合适的一个，返回该Agent的ID。
            
                        # Context (当前会话状态)
                        %s
                        
                        # Candidate Agents (候选业务列表)
                        %s
                        
                        # Current User Input (用户最新输入)
                        >>> "%s" <<<
                        
                        # Task
                        1. **直接关联判断**：观察 [最近对话快照] 和 [AI 上次回复]。如果 AI 正在索要某个信息（如姓名、金额），而用户在 [最新输入] 中给出了对应的回答，**必须** 维持当前 Agent ID，不要切换。
                        2. **意图切换**：如果用户 [最新输入] 表达了明确的新需求，且与 [当前负责 Agent ID] 职责无关，则从列表中匹配最相关的 ID。**若有多个 Agent 职责重叠或同时匹配，必须遵循“专属性原则”，选择职责描述更精确、更具体的唯一 ID，严禁返回多个 ID 或 ID 列表。**
                        3. **默认兜底**：若用户输入无意义或者无匹配 Agent，返回 0。
            
                        # Constraint
                        - 必须只返回一个数字（Agent ID）。
                        - 严禁返回任何解释、标点或文字。
                        """.formatted(contextInfo, agentOptions, userInput);

                    return Mono.fromCallable(() -> {
                                log.debug("发起路由决策请求，用户输入: {}", userInput);
                                String resp = chatClient.prompt()
                                        .user(routingPrompt)
                                        .call()
                                        .content();

                                return parseAgentId(resp);
                            })
                            .subscribeOn(Schedulers.boundedElastic()) // 确保 IO 在弹性线程池执行
                            .onErrorResume(e -> {
                                log.error("路由匹配异常", e);
                                return Mono.just(0L);
                            });
                })
                .onErrorResume(e -> {
                    log.error("路由匹配异常: sessionId={}", sessionState.sessionId(), e);
                    return Mono.just(0L);
                });

    }

    /**
     * 鲁棒的 ID 解析逻辑
     */
    private Long parseAgentId(String resp) {
        if (!StringUtils.hasText(resp)) {
            return 0L;
        }

        // LLM 有时会返回 "ID: 101" 或 "```json 101 ```"
        // 直接在原始文本中寻找第一个连续的数字序列
        Matcher matcher = AGENT_ID_PATTERN.matcher(resp);

        if (matcher.find()) {
            String idStr = matcher.group();
            try {
                Long id = Long.valueOf(idStr);
                log.info("路由匹配决策成功：提取到的 AgentId = {}", id);
                return id;
            } catch (NumberFormatException e) {
                // 虽然匹配的是数字，但为了防止超出 Long 范围等极端情况
                log.error("解析 Agent ID 失败，数字格式异常: {}", idStr);
            }
        } else {
            log.warn("路由匹配失败：LLM 响应中未包含有效的数字 ID。原始响应: {}", resp);
        }

        return 0L;
    }


    private Context buildFinalContext(String sessionId, String userId, Long agentId, List<String> tags, Map<String, Object> metadata) {
        return Context.create(sessionId, userId, agentId, tags, metadata);
    }


}
