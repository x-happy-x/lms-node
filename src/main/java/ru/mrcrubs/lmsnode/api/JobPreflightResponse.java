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
        List<StorageTargetResponse> storageTargets,
        boolean ytdlpOptionsSupported,
        boolean advancedOptionsSupported,
        boolean impersonationOptionsSupported
) {
    public JobPreflightResponse(String url, Long sizeBytes, boolean sizeKnown, JobType recommendedType,
                               List<JobType> supportedTypes, List<DownloadOptionResponse> options,
                               String defaultStoragePath, List<StorageTargetResponse> storageTargets) {
        this(url, sizeBytes, sizeKnown, recommendedType, supportedTypes, options, defaultStoragePath, storageTargets, true, true, true);
    }
    public JobPreflightResponse(String url, Long sizeBytes, boolean sizeKnown, JobType recommendedType,
                               List<JobType> supportedTypes, List<DownloadOptionResponse> options,
                               String defaultStoragePath, List<StorageTargetResponse> storageTargets, boolean supported) {
        this(url,sizeBytes,sizeKnown,recommendedType,supportedTypes,options,defaultStoragePath,storageTargets,supported,true,true);
    }
}
