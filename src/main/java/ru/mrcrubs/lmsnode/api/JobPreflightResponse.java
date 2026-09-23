package ru.mrcrubs.lmsnode.api;

import ru.mrcrubs.lmsnode.model.JobType;

import java.util.List;

public record JobPreflightResponse(
        String url,
        Long sizeBytes,
        boolean sizeKnown,
        JobType recommendedType,
        List<JobType> supportedTypes,
        List<DownloadOptionResponse> options,
        String defaultStoragePath,
        List<StorageTargetResponse> storageTargets
) {
}
