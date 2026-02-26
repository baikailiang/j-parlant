package com.jparlant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * J-Parlant 框架配置属性
 */
@ConfigurationProperties(prefix = "jparlant")
@Data
public class JParlantProperties {

    /**
     * HTTP 客户端配置（用于 WebClient）
     */
    private HttpConfig http = new HttpConfig();

    @Data
    public static class HttpConfig {
        /**
         * 连接池名称
         */
        private String poolName = "jparlant-pool";

        /**
         * 连接池中允许存在的最大连接数
         */
        private Integer maxConnections = 500;

        /**
         * 连接的最大空闲时间
         */
        private Duration maxIdleTime = Duration.ofSeconds(30);

        /**
         * 连接的最长生命周期
         */
        private Duration maxLifeTime = Duration.ofSeconds(300);

        /**
         * 获取连接的等待超时时间
         */
        private Duration pendingAcquireTimeout = Duration.ofSeconds(30);

        /**
         * 后台定时清理周期
         */
        private Duration evictInBackground = Duration.ofSeconds(30);

        /**
         * 响应超时时间
         */
        private Duration responseTimeout = Duration.ofSeconds(30);

        /**
         * 连接超时时间
         */
        private Duration connectTimeout = Duration.ofSeconds(10);
    }

}