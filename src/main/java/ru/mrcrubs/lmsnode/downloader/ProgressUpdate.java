package ru.mrcrubs.lmsnode.downloader;

public record ProgressUpdate(
        Double percent,
        Long speedBytes,
        Long etaSeconds,
        String message
) {
}
