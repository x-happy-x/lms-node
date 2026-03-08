package ru.mrcrubs.lmsnode.auth;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class NonceStore {
    private final ConcurrentHashMap<String, Long> nonces = new ConcurrentHashMap<>();

    public boolean isReplay(String key, long nowEpochSeconds, long ttlSeconds) {
        cleanup(nowEpochSeconds);
        long expiresAt = nowEpochSeconds + ttlSeconds;

        Long existing = nonces.putIfAbsent(key, expiresAt);
        if (existing == null) {
            return false;
        }
        if (existing < nowEpochSeconds) {
            nonces.replace(key, existing, expiresAt);
            return false;
        }
        return true;
    }

    private void cleanup(long nowEpochSeconds) {
        nonces.entrySet().removeIf(entry -> entry.getValue() < nowEpochSeconds);
    }
}
