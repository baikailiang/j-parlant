package com.jparlant.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 生产级监控服务
 */
@Slf4j
public class MetricsService {

    private final MeterRegistry meterRegistry;

    // 自定义业务指标缓存
    private final ConcurrentMap<String, Counter> businessCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> businessTimers = new ConcurrentHashMap<>();

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 内部辅助方法：统一处理 Long 类型 ID 转 Tag 字符串
     */
    private String formatId(Long id) {
        return id != null ? id.toString() : "unknown";
    }

    /**
     * 记录聊天请求
     */
    public void recordChatRequest(Long agentId, String intent) {
        meterRegistry.counter("jparlant.chat.requests.total",
                "agent_id", formatId(agentId),
                "intent", intent != null ? intent : "unknown"
        ).increment();

        log.debug("记录聊天请求: agentId={}, intent={}", agentId, intent);
    }

    /**
     * 记录聊天响应时间开始
     */
    public Timer.Sample startChatTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * 停止计时并记录
     */
    public void recordChatDuration(Timer.Sample sample, Long agentId, String result) {
        sample.stop(Timer.builder("jparlant.chat.response.duration")
                .tag("agent_id", formatId(agentId))
                .tag("result", result != null ? result : "unknown")
                .register(meterRegistry));
    }

    /**
     * 记录指导原则检索
     */
    public void recordGuidelineRetrieval(Long agentId, int count, String strategy) {
        meterRegistry.counter("jparlant.guideline.retrieval.total",
                "agent_id", formatId(agentId),
                "strategy", strategy != null ? strategy : "unknown",
                "count_range", categorizeCount(count)
        ).increment();
    }

    /**
     * 记录工具执行
     */
    public void recordToolExecution(String toolName, boolean success, Duration duration) {
        meterRegistry.counter("jparlant.tool.execution.total",
                "tool_name", toolName != null ? toolName : "unknown",
                "success", String.valueOf(success)
        ).increment();

        meterRegistry.timer("jparlant.tool.execution.duration",
                "tool_name", toolName != null ? toolName : "unknown",
                "success", String.valueOf(success)
        ).record(duration);

        log.debug("记录工具执行: tool={}, success={}, duration={}ms",
                toolName, success, duration.toMillis());
    }

    /**
     * 记录合规违规
     */
    public void recordComplianceViolation(Long ruleId, String level, Long agentId) {
        meterRegistry.counter("jparlant.compliance.violation.total",
                "rule_id", formatId(ruleId),
                "level", level != null ? level : "unknown",
                "agent_id", formatId(agentId)
        ).increment();

        log.warn("记录合规违规: rule={}, level={}, agent={}", ruleId, level, agentId);
    }

    /**
     * 记录热更新操作
     */
    public void recordHotUpdate(String type, Long agentId, boolean success) {
        meterRegistry.counter("jparlant.hotupdate.operations.total",
                "type", type != null ? type : "unknown",
                "agent_id", formatId(agentId),
                "success", String.valueOf(success)
        ).increment();
    }

    /**
     * 记录自定义业务指标 (通用方法)
     */
    public void recordBusinessMetric(String metricName, String... tags) {
        String key = metricName + ":" + String.join(",", tags);

        businessCounters.computeIfAbsent(key, k -> {
            Counter.Builder builder = Counter.builder("jparlant.business." + metricName);
            for (int i = 0; i < tags.length; i += 2) {
                if (i + 1 < tags.length) {
                    builder.tag(tags[i], tags[i + 1]);
                }
            }
            return builder.register(meterRegistry);
        }).increment();
    }

    /**
     * 获取系统健康状态 (Reactive)
     */
    public Mono<SystemHealthStatus> getSystemHealth() {
        return Mono.fromCallable(() -> {
            double chatRequestRate = getChatRequestRate();
            double complianceViolationRate = getComplianceViolationRate();
            double toolSuccessRate = getToolSuccessRate(); // 示例中简化

            HealthLevel healthLevel = determineHealthLevel(
                    chatRequestRate, complianceViolationRate, toolSuccessRate);

            return new SystemHealthStatus(
                    healthLevel,
                    chatRequestRate,
                    complianceViolationRate,
                    toolSuccessRate,
                    System.currentTimeMillis()
            );
        });
    }

    /**
     * 获取性能统计 (Reactive)
     */
    public Mono<PerformanceStats> getPerformanceStats() {
        return Mono.fromCallable(() -> {
            Timer chatTimer = meterRegistry.find("jparlant.chat.response.duration").timer();
            Timer toolTimer = meterRegistry.find("jparlant.tool.execution.duration").timer();

            return new PerformanceStats(
                    chatTimer != null ? chatTimer.mean(TimeUnit.MILLISECONDS) : 0.0,
                    chatTimer != null ? chatTimer.max(TimeUnit.MILLISECONDS) : 0.0,
                    toolTimer != null ? toolTimer.mean(TimeUnit.MILLISECONDS) : 0.0,
                    System.currentTimeMillis()
            );
        });
    }

    // --- 私有辅助逻辑 ---

    private String categorizeCount(int count) {
        if (count == 0) return "zero";
        if (count <= 3) return "low";
        if (count <= 10) return "medium";
        return "high";
    }

    private double getChatRequestRate() {
        Counter counter = meterRegistry.find("jparlant.chat.requests.total").counter();
        return counter != null ? counter.count() : 0.0;
    }

    private double getComplianceViolationRate() {
        Counter counter = meterRegistry.find("jparlant.compliance.violation.total").counter();
        double violations = counter != null ? counter.count() : 0.0;
        double total = getChatRequestRate();
        return total > 0 ? violations / total : 0.0;
    }

    private double getToolSuccessRate() {
        return 0.95; // 生产环境应从成功/失败 counter 计算
    }

    private HealthLevel determineHealthLevel(double requestRate, double violationRate, double successRate) {
        if (violationRate > 0.1 || successRate < 0.8) return HealthLevel.CRITICAL;
        if (violationRate > 0.05 || successRate < 0.9) return HealthLevel.WARNING;
        return HealthLevel.HEALTHY;
    }

    public enum HealthLevel { HEALTHY, WARNING, CRITICAL }

    public record SystemHealthStatus(HealthLevel level, double chatRequestRate,
                                     double complianceViolationRate, double toolSuccessRate, long timestamp) {}

    public record PerformanceStats(double avgChatResponseTimeMs, double maxChatResponseTimeMs,
                                   double avgToolExecutionTimeMs, long timestamp) {}
}