# J-Parlant

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-blue.svg)](https://spring.io/projects/spring-ai)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0)

> **原生 Java AI Agent 框架 —— 引入一个依赖，即刻交付生产级 AI 能力。**

**J-Parlant** 是专为 Java 开发者打造的工业级 AI Agent 引擎，通过全链路非阻塞异步编程模型，赋予 AI Agent 支撑海量并发的性能，将不确定的生成式输出转化为高度受控的业务逻辑。

---

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

### 🛠️ 注解驱动的业务执行器

**零侵入标记业务方法**，通过简单的注解标记，J-Parlant 能够自动扫描并发现你的业务方法，使其成为在 Admin 后台可配置、可调用的执行器。

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

### WebClient 连接池配置（高并发优化）

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

---

## 参与贡献

欢迎 Pull Request！重大变更请先开 Issue 讨论。

---

## 许可证

[Apache License 2.0](LICENSE)

---

<p align="center">
  <b>J-Parlant</b> — 让 Java 开发者轻松构建企业级 AI Agent
</p>
