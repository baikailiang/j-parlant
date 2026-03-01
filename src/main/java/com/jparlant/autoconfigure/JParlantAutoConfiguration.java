package com.jparlant.autoconfigure;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jparlant.cache.ReactiveCacheMessageSubscriber;
import com.jparlant.config.CacheSyncProperties;
import com.jparlant.config.JParlantProperties;
import com.jparlant.controller.FlowMetadataController;
import com.jparlant.repository.*;
import com.jparlant.service.agent.AgentDefinitionManager;
import com.jparlant.service.agent.AgentRouter;
import com.jparlant.service.agent.AgentService;
import com.jparlant.service.chat.JParlantChatService;
import com.jparlant.service.chat.impl.JParlantChatServiceImpl;
import com.jparlant.service.compliance.ComplianceEngine;
import com.jparlant.service.flow.AgentFlowEngine;
import com.jparlant.service.flow.AgentFlowManager;
import com.jparlant.service.flow.AgentFlowWrapper;
import com.jparlant.service.flow.evaluator.FlowExpressionEvaluator;
import com.jparlant.service.flow.handler.action.*;
import com.jparlant.service.flow.handler.input.InputStepHandler;
import com.jparlant.service.flow.handler.input.validation.BasicValidator;
import com.jparlant.service.flow.handler.input.validation.FieldValidator;
import com.jparlant.service.flow.handler.input.validation.SpelBusinessValidator;
import com.jparlant.service.glossary.GlossaryEngine;
import com.jparlant.service.history.ChatHistoryService;
import com.jparlant.service.intent.IntentAnalysisService;
import com.jparlant.service.monitoring.MetricsService;
import com.jparlant.service.session.SessionStateManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.util.List;

/**
 * J-Parlant 自动配置类
 * 自动装配所有必要的Bean，支持一键引入使用
 */
@AutoConfiguration
@EnableR2dbcRepositories(basePackages = "com.jparlant.repository")
@ConditionalOnClass({ChatClient.class, WebClient.class, ReactiveRedisConnectionFactory.class})
@EnableConfigurationProperties({JParlantProperties.class, CacheSyncProperties.class})
@Slf4j
public class JParlantAutoConfiguration {


    @Bean(name = "jparlantRedisTemplate")
    @ConditionalOnMissingBean(name = "jparlantRedisTemplate")
    public ReactiveRedisTemplate<String, String> jparlantRedisTemplate(ReactiveRedisConnectionFactory factory) {
        log.info("初始化 jparlantInternalRedisTemplate...");
        // 1. 定义序列化方式：Key 和 Value 都使用 String 序列化器
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // 2. 构建序列化上下文
        RedisSerializationContext<String, String> context = RedisSerializationContext
                .<String, String>newSerializationContext(stringSerializer) // Key 序列化
                .value(stringSerializer)     // Value 序列化 (现在是纯 String)
                .hashKey(stringSerializer)   // Hash Key
                .hashValue(stringSerializer) // Hash Value
                .build();

        // 3. 返回 String, String 类型的模板
        return new ReactiveRedisTemplate<>(factory, context);
    }


    @Bean
    @ConditionalOnMissingBean
    public WebClient.Builder webClientBuilder(JParlantProperties properties) {
        JParlantProperties.HttpConfig config = properties.getHttp();
        log.info("初始化 J-Parlant 高性能 WebClient.Builder, poolName: {}", config.getPoolName());

        // 1. 配置连接池
        ConnectionProvider connectionProvider = ConnectionProvider.builder(config.getPoolName())
                .maxConnections(config.getMaxConnections())
                .maxIdleTime(config.getMaxIdleTime())
                .maxLifeTime(config.getMaxLifeTime())
                .pendingAcquireTimeout(config.getPendingAcquireTimeout())
                .evictInBackground(config.getEvictInBackground())
                .build();

        // 2. 配置 HttpClient
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectTimeout().toMillis())
                .responseTimeout(config.getResponseTimeout());

        // 3. 构建 Builder
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }


    /**
     * 提供默认的 ChatClient
     */
    @Bean(name = "jparlantChatClient")
    @ConditionalOnMissingBean(name = "jparlantChatClient")
    public ChatClient chatClient(ChatClient.Builder builder) {
        log.info("初始化 J-Parlant 默认 ChatClient");
        return builder.build();
    }


    @Bean
    @ConditionalOnProperty(prefix = "jparlant.cache.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public ReactiveCacheMessageSubscriber reactiveCacheMessageSubscriber(
            @Qualifier("jparlantRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
            JParlantChatService jParlantChatService,
            ObjectMapper objectMapper,
            CacheSyncProperties properties) {

        return new ReactiveCacheMessageSubscriber(redisTemplate, jParlantChatService, objectMapper, properties);
    }


    /**
     * J-Parlant 聊天服务 - 对外API
     */
    @Bean
    @ConditionalOnMissingBean
    public JParlantChatService jParlantChatService(AgentService agentService,
                                                   AgentDefinitionManager agentDefinitionManager,
                                                   AgentFlowManager agentFlowManager,
                                                   ComplianceEngine complianceEngine,
                                                   GlossaryEngine glossaryEngine,
                                                   FlowMetadataService flowMetadataService) {
        log.info("初始化 J-Parlant Chat Service");
        return new JParlantChatServiceImpl(agentService, agentDefinitionManager, agentFlowManager, complianceEngine, glossaryEngine, flowMetadataService);
    }


    /**
     * Agent服务 - 核心编排服务
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentService agentService(
            @Qualifier("jparlantChatClient") ChatClient chatClient,
            AgentFlowEngine flowEngine,
            IntentAnalysisService intentAnalysisService,
            GlossaryEngine glossaryEngine,
            ComplianceEngine complianceEngine,
            SessionStateManager sessionStateManager,
            MetricsService metricsService,
            AgentRouter agentRouter,
            ChatHistoryService chatHistoryService) {
        log.info("初始化 AgentService...");
        return new AgentService(
                chatClient,
                flowEngine,
                intentAnalysisService,
                glossaryEngine,
                complianceEngine,
                sessionStateManager,
                metricsService,
                agentRouter,
                chatHistoryService
        );
    }


    @Bean
    @ConditionalOnMissingBean
    public ChatHistoryService chatHistoryService(
            ObjectMapper objectMapper,
            @Qualifier("jparlantRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
            SessionStateManager sessionStateManager) {
        return new ChatHistoryService(objectMapper, redisTemplate, sessionStateManager);
    }


    /**
     * Agent路由器
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRouter agentRouter(
            @Qualifier("jparlantChatClient") ChatClient chatClient,
            AgentDefinitionManager agentDefinitionManager,
            SessionStateManager sessionStateManager,
            ChatHistoryService chatHistoryService) {
        log.info("初始化 AgentRouter...");
        return new AgentRouter(agentDefinitionManager, chatClient, sessionStateManager, chatHistoryService);
    }

    /**
     * Agent定义管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentDefinitionManager agentDefinitionManager(AgentRepository agentRepository) {
        log.info("初始化 AgentDefinitionManager...");
        return new AgentDefinitionManager(agentRepository);
    }

    /**
     * 流程引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentFlowEngine agentFlowEngine(
            SessionStateManager sessionStateManager,
            ActionDispatcher actionDispatcher) {
        return new AgentFlowEngine(sessionStateManager, actionDispatcher);
    }

    /**
     * 流程管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentFlowManager agentFlowManager(
            AgentIntentRepository agentIntentRepository,
            IntentStepRepository intentStepRepository,
            AgentFlowWrapper agentFlowWrapper,
            IntentStepTransitionRepository intentStepTransitionRepository) {
        return new AgentFlowManager(agentIntentRepository, intentStepRepository, agentFlowWrapper, intentStepTransitionRepository);
    }

    /**
     * 流程包装器
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentFlowWrapper agentFlowWrapper(ObjectMapper objectMapper) {
        return new AgentFlowWrapper(objectMapper);
    }

    /**
     * 意图分析服务
     */
    @Bean
    @ConditionalOnMissingBean
    public IntentAnalysisService intentAnalysisService(@Qualifier("jparlantChatClient") ChatClient chatClient, AgentFlowManager agentFlowManager, ObjectMapper objectMapper) {
        // 允许 JSON 字符串中包含未转义的控制字符（如真正的换行符、制表符）
        objectMapper.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        // 允许单引号
        objectMapper.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature());
        // 允许字段名不带引号
        objectMapper.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
        // 允许 Java 风格注释 (//)
        objectMapper.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature());

        return new IntentAnalysisService(chatClient, agentFlowManager, objectMapper);
    }

    /**
     * 词汇表引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public GlossaryEngine glossaryEngine(GlossaryRepository glossaryRepository, ObjectMapper objectMapper) {
        return new GlossaryEngine(glossaryRepository, objectMapper);
    }

    /**
     * 合规引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public ComplianceEngine complianceEngine(
            SessionStateManager sessionStateManager,
            ComplianceRuleRepository ruleRepository,
            @Qualifier("jparlantChatClient") ChatClient chatClient,
            ObjectMapper objectMapper,
            FlowExpressionEvaluator flowExpressionEvaluator) {
        return new ComplianceEngine(sessionStateManager, ruleRepository, chatClient, objectMapper, flowExpressionEvaluator);
    }

    /**
     * 会话状态管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public SessionStateManager sessionStateManager(@Qualifier("jparlantRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        return new SessionStateManager(redisTemplate, objectMapper);
    }

    /**
     * 指标服务
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricsService metricsService(MeterRegistry meterRegistry) {
        return new MetricsService(meterRegistry);
    }


    /**
     * SpEL 表达式评估器
     */
    @Bean
    @ConditionalOnMissingBean
    public FlowExpressionEvaluator flowExpressionEvaluator() {
        return new FlowExpressionEvaluator();
    }

    /**
     * 基础校验器（必填、正则等）
     */
    @Bean
    @ConditionalOnMissingBean
    public BasicValidator basicValidator() {
        return new BasicValidator();
    }

    /**
     * 业务逻辑校验器（依赖 Evaluator）
     */
    @Bean
    @ConditionalOnMissingBean
    public SpelBusinessValidator spelBusinessValidator(FlowExpressionEvaluator evaluator) {
        return new SpelBusinessValidator(evaluator);
    }


    @Bean
    @ConditionalOnMissingBean
    public ActionDispatcher actionDispatcher(
            ApplicationContext applicationContext,
            ObjectMapper objectMapper) {
        return new ActionDispatcher(applicationContext, objectMapper);
    }



    @Bean
    @ConditionalOnMissingBean
    public ActionStepHandler actionStepHandler(ActionDispatcher actionDispatcher, SessionStateManager sessionStateManager) {
        return new ActionStepHandler(actionDispatcher, sessionStateManager);
    }



    @Bean
    @ConditionalOnMissingBean
    public InputStepHandler inputStepHandler(
            SessionStateManager sessionStateManager,
            List<FieldValidator> validators,  // Spring 会自动收集所有 FieldValidator Bean
            ActionDispatcher actionDispatcher) {
        return new InputStepHandler(sessionStateManager, validators, actionDispatcher);
    }


    @Bean
    @ConditionalOnMissingBean
    public FlowMetadataService flowMetadataService(ApplicationContext applicationContext) {
        return new FlowMetadataService(applicationContext);
    }



    @Bean
    public FlowMetadataController flowMetadataController(FlowMetadataService flowMetadataService) {
        return new FlowMetadataController(flowMetadataService);
    }


    @Bean
    public LoanCalculatorService loanCalculatorService() {
        return new LoanCalculatorService();
    }

    @Bean
    public RiskControlService riskControlService() {
        return new RiskControlService();
    }

}