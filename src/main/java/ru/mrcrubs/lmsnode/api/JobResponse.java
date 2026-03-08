package ru.mrcrubs.lmsnode.api;

import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobStatus;
import ru.mrcrubs.lmsnode.model.JobType;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID jobId,
        JobType type,
        String url,
        JobStatus status,
        Double percent,
        Long speedBytes,
        Long etaSeconds,
        String message,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String outputPath
) {
    public static JobResponse from(DownloadJob job) {
        return new JobResponse(
                job.getJobId(),
                job.getType(),
                job.getUrl(),
                job.getStatus(),
                job.getPercent(),
                job.getSpeedBytes(),
                job.getEtaSeconds(),
                job.getMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getOutputPath()
        );
    }
}
