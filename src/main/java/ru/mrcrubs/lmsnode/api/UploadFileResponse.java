package ru.mrcrubs.lmsnode.api;

public record UploadFileResponse(
        String outputPath,
        long sizeBytes
) {
}
