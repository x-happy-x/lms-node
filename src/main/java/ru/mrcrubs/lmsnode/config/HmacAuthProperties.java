package ru.mrcrubs.lmsnode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "node.auth")
public class HmacAuthProperties {
    private Map<String, String> clients = new HashMap<>();
    private long allowedSkewSeconds = 120;
    private long nonceTtlSeconds = 600;

    public Map<String, String> getClients() {
        return clients;
    }

    public void setClients(Map<String, String> clients) {
        this.clients = clients;
    }

    public long getAllowedSkewSeconds() {
        return allowedSkewSeconds;
    }

    public void setAllowedSkewSeconds(long allowedSkewSeconds) {
        this.allowedSkewSeconds = allowedSkewSeconds;
    }

    public long getNonceTtlSeconds() {
        return nonceTtlSeconds;
    }

    public void setNonceTtlSeconds(long nonceTtlSeconds) {
        this.nonceTtlSeconds = nonceTtlSeconds;
    }
}
