package ru.mrcrubs.lmsnode.api;

import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobStatus;
import ru.mrcrubs.lmsnode.model.JobType;
import ru.mrcrubs.lmsnode.model.YtDlpOptions;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID jobId,
        JobType type,
        String url,
        String storagePath,
        JobStatus status,
        Double percent,
        Long totalBytes,
        Long speedBytes,
        Long etaSeconds,
        String message,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String outputPath,
        Long outputSizeBytes,
        Long maxSpeedBytes,
        YtDlpOptions ytdlpOptions,
        String networkRouteId,
        String networkRouteLabel
) {
    public static JobResponse from(DownloadJob job) {
        return new JobResponse(
                job.getJobId(),
                job.getType(),
                job.getUrl(),
                job.getStoragePath(),
                job.getStatus(),
                job.getPercent(),
                job.getTotalBytes(),
                job.getSpeedBytes(),
                job.getEtaSeconds(),
                job.getMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getOutputPath(),
                job.getOutputSizeBytes(),
                job.getMaxSpeedBytes(),
                job.getYtdlpOptions(), job.getNetworkRouteId(), job.getNetworkRouteLabel()
        );
    }
}
