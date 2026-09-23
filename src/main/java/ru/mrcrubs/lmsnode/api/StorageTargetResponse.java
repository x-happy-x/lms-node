package ru.mrcrubs.lmsnode.api;

public record StorageTargetResponse(
        String path,
        long freeBytes,
        long totalBytes,
        boolean writable,
        Boolean canFit
) {
}
