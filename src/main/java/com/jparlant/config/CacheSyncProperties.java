package com.jparlant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jparlant.cache.sync")
@Data
public class CacheSyncProperties {
    private boolean enabled = true;
    private String channel = "jparlant-cache-refresh-topic";
}
