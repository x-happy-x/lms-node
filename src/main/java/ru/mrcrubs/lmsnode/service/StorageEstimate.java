package ru.mrcrubs.lmsnode.service;

public record StorageEstimate(
        Long sizeBytes,
        boolean known,
        String message
) {
}
