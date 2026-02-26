# J-Parlant

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-blue.svg)](https://spring.io/projects/spring-ai)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0)

**J-Parlant** 是一个**纯 Java** 的 AI Agent 框架。一个依赖，开箱即用。

> 拒绝简单的 LLM 接口封装：J-Parlant 是深度整合业务逻辑、提供确定性输出的生产级 AI Agent 驱动引擎。

---

## 为什么选择 J-Parlant？

### 🎯 痛点对标：从“实验性 Demo”走向“生产级应用”

| 核心痛点 | J-Parlant 解决方案 |
| :--- | :--- |
| **行为不可控 (胡言乱语)** | **4 层合规审计 + 逻辑细分驱动**：通过前置/后置检查点与术语库强制约束，并将业务场景拆解为**微型确定性步骤**，确保 Agent 的每一步响应都严格遵循既定规则，从根源杜绝幻觉。 |
| **逻辑碎片化 (难以维护)** | **可视化流程编排**：将散落在 Prompt 里的碎片逻辑转化为结构化的可视化流程，业务路径清晰可审计、易于维护。 |
| **开发门槛高 (状态复杂)** | **低代码驱动核心**：内置上下文状态机与流式输出管理，开发者无需处理复杂的底座逻辑，只需关注业务流设计。 |
| **集成成本大 (复用性差)** | **Java/Spring 原生生态**：专为 Java 体系设计，通过 Starter 无缝集成，可直接调用既有 Service 逻辑，实现 AI 与业务的深度粘合。 |
| **并发性能差 (响应缓慢)** | **全链路响应式架构**：基于 WebFlux + R2DBC 的非阻塞设计，支撑企业级高并发对话请求，拒绝因 Agent 运行导致的系统阻塞。 |


## 30 秒快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.jparlant</groupId>
    <artifactId>jparlant-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 配置 AI 模型

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_API_URL}  # 可选，兼容任意 OpenAI 协议服务
```

### 3. 开始对话

```java
// 非流式
public Mono<ChatMessage> chat(String userId, String text) {
    return chatService.chat(new ChatRequest(userId, text));
}

// 流式（打字机效果）
public Flux<ChatMessage> stream(String userId, String text) {
    return chatService.chatStream(new ChatRequest(userId, text));
}
```

**就这么简单。** 框架自动处理意图识别、流程跳转、状态管理。

---

## 核心能力

### 1. 注解驱动的业务执行器

零侵入标记业务方法，自动被 AI 发现和调用：

```java
@FlowAction("订单服务")
public class OrderService {

    @FlowMethod(value = "创建订单", description = "根据商品和数量创建新订单")
    public OrderResult createOrder(
        @FlowProperty("商品ID") Long productId,
        @FlowProperty("数量") Integer quantity
    ) {
        // 你的业务逻辑
    }
}
```

### 2. 智能意图分析

**三级递进式识别**：当前意图聚焦 → 全量扫描 → 兜底策略

- 情绪识别（积极/消极/紧急）
- 复杂度评估（简单/中等/复杂）
- 智能跳步检测（用户抢答自动识别）

### 3. 递归流程引擎

```
用户输入 → 意图分析 → 步骤处理 → 自动流转 → 返回响应
```


### 4. 四层合规检查

漏斗式短路，快速失败：

| 层级 | 方式 | 开销 |
|:---:|:---|:---:|
| L1 | 关键词匹配 | 极低 |
| L2 | 正则表达式 | 低 |
| L3 | SpEL 表达式 | 中 |
| L4 | LLM 语义审查 | 高 |


### 5. 术语表引擎

专业领域防幻觉，确保 AI 输出遵循行业标准：

```yaml
术语: 浮动利率
定义: 利率随市场基准变动的贷款利率
同义词: [浮息, 市场化利率]
```

---

## 高级配置

### HTTP 连接池（高并发优化）

```yaml
jparlant:
  http:
    max-connections: 1000      # 连接池大小
    response-timeout: 60s      # AI 响应超时
    connect-timeout: 5s        # 建立连接超时
    max-life-time: 300s        # 连接最大生命周期（支持 DNS 轮询）
```

### 完整配置参考

| 配置项 | 默认值 | 说明 |
|:---|:---:|:---|
| `jparlant.http.pool-name` | jparlant-pool | 连接池标识 |
| `jparlant.http.max-connections` | 500 | 最大连接数 |
| `jparlant.http.connect-timeout` | 10s | TCP 连接超时 |
| `jparlant.http.response-timeout` | 30s | 响应超时 |
| `jparlant.http.max-idle-time` | 30s | 空闲连接超时 |
| `jparlant.http.max-life-time` | 300s | 连接最大生命周期 |
| `jparlant.http.pending-acquire-timeout` | 30s | 等待获取连接超时 |

---

## 传统 MVC 项目集成

J-Parlant 完美兼容 Spring MVC (Tomcat) 项目。

### 1. 添加 Web 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

### 2. 添加 Netty 依赖（保持高性能）

```xml
<dependency>
    <groupId>io.projectreactor.netty</groupId>
    <artifactId>reactor-netty</artifactId>
</dependency>
```

> **注意**：不添加此依赖，WebClient 会降级为 JDK HttpClient，连接池配置将失效。

### 3. 调用

```java
// 非流式
public Mono<ChatMessage> chat(String userId, String text) {
    return chatService.chat(new ChatRequest(userId, text));
}

// 流式（打字机效果）
public Flux<ChatMessage> stream(String userId, String text) {
    return chatService.chatStream(new ChatRequest(userId, text));
}
```

---

## 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                      J-Parlant Framework                     │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │ 意图分析引擎 │  │ 流程编排引擎 │  │ 合规审查引擎 │          │
│  │   3级识别   │  │  递归处理   │  │  4层检查   │          │
│  └─────────────┘  └─────────────┘  └─────────────┘          │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │  术语表引擎  │  │ 会话状态管理 │  │  多维校验器  │          │
│  │  防AI幻觉   │  │ Redis持久化 │  │正则/SpEL/远程│          │
│  └─────────────┘  └─────────────┘  └─────────────┘          │
├─────────────────────────────────────────────────────────────┤
│                    Spring AI + WebFlux + R2DBC               │
│                    全链路响应式 · 非阻塞 · 高并发              │
└─────────────────────────────────────────────────────────────┘
```

### 技术栈

| 组件 | 技术选型 | 说明 |
|:---|:---|:---|
| 核心环境 | Java 17+ |  |
| 响应式框架 | Project Reactor | 基于 Mono/Flux 的全链路异步编程模型 |
| AI 集成 | Spring AI 1.1.2 | 厂商中立，支持多模型 |
| Web 层 | WebFlux | 高并发非阻塞 HTTP 服务 |
| 数据库 | R2DBC | 响应式数据库访问 |
| 缓存 | Caffeine + Redis | 多级缓存架构 |
| 表达式 | SpEL | 灵活的业务规则 |

---

## 缓存刷新

运行时动态更新 Agent 配置，无需重启：

---

## 参与贡献

欢迎 Pull Request！重大变更请先开 Issue 讨论。

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add AmazingFeature'`)
4. 推送分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

---

## 许可证

[Apache License 2.0](LICENSE)

---

<p align="center">
  <b>J-Parlant</b> — 让 Java 开发者轻松构建企业级 AI Agent
</p>
