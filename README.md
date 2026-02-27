# J-Parlant

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-blue.svg)](https://spring.io/projects/spring-ai)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0)

> **原生 Java AI Agent 框架 —— 引入一个依赖，即刻交付生产级 AI 能力。**

**J-Parlant** 专为复杂业务场景设计的**深度逻辑集成引擎**。我们致力于将 AI 的随机性转化为软件的**确定性执行**，构建工业级的 AI Agent 运行时环境。

---


## 快速开始

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

import com.jparlant.service.chat.JParlantChatService;
import org.springframework.beans.factory.annotation.Autowired;

@Autowired
private JParlantChatService chatService;


// 非流式
public Mono<ChatMessage> chat(String userId, String text) {
    return chatService.chat(new ChatRequest(userId, text));
}

// 流式（打字机效果）
public Flux<ChatMessage> stream(String userId, String text) {
    return chatService.chatStream(new ChatRequest(userId, text));
}
```

**就这么简单。** 

---

## 🛠️ 注解驱动的业务执行器

零侵入标记业务方法，通过简单的注解标记，J-Parlant 能够自动扫描并发现你的业务方法，使其成为 Agent 在 Admin 后台可配置、可调用的执行器。

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




---

## 高级配置

### HTTP 连接池（高并发优化）

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

J-Parlant 完美兼容 Spring MVC 项目。

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

> **注意**：不添加此依赖，WebClient 会降级为 JDK HttpClient，连接池配置将失效，且易引发oom。

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


### 技术栈

### 🛠️ 技术栈选型

| 组件 | 技术选型 | 核心说明 |
| :--- | :--- | :--- |
| **核心环境** | Java 17+ |  |
| **响应式引擎** | Project Reactor | 基于 Mono/Flux 的全链路非阻塞异步编程模型 |
| **AI 运行环境** | Spring AI | 厂商中立架构，深度适配多模型 |
| **Web 架构** | Spring WebFlux | 高并发场景下的极致吞吐量保障 |
| **响应式数据库** | R2DBC | 数据库连接层全异步化，消除 IO 阻塞 |
| **响应式 Redis** | Spring Data Redis (Reactive) | 基于 Lettuce 的非阻塞分布式存储与状态管理 |
| **本地缓存** | Caffeine | 毫秒级响应的高性能本地内存缓存 |

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
