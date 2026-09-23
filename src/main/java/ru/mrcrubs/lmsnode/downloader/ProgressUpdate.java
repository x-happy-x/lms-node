package ru.mrcrubs.lmsnode.downloader;

public record ProgressUpdate(
        Double percent,
        Long totalBytes,
        Long speedBytes,
        Long etaSeconds,
        String message
) {
}
