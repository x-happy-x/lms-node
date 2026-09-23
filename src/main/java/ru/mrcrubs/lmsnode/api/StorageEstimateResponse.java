package ru.mrcrubs.lmsnode.api;

public record StorageEstimateResponse(
        Long sizeBytes,
        boolean known,
        String message
) {
}
