package ru.mrcrubs.lmsnode.model;

import java.time.Instant;
import java.util.UUID;

public class DownloadJob {
    private final UUID jobId;
    private final JobType type;
    private final String url;
    private volatile JobStatus status;
    private volatile Double percent;
    private volatile Long speedBytes;
    private volatile Long etaSeconds;
    private volatile String message;
    private final Instant createdAt;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String outputPath;

    public DownloadJob(UUID jobId, JobType type, String url, Instant createdAt) {
        this.jobId = jobId;
        this.type = type;
        this.url = url;
        this.createdAt = createdAt;
        this.status = JobStatus.QUEUED;
    }

    public UUID getJobId() {
        return jobId;
    }

    public JobType getType() {
        return type;
    }

    public String getUrl() {
        return url;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Double getPercent() {
        return percent;
    }

    public void setPercent(Double percent) {
        this.percent = percent;
    }

    public Long getSpeedBytes() {
        return speedBytes;
    }

    public void setSpeedBytes(Long speedBytes) {
        this.speedBytes = speedBytes;
    }

    public Long getEtaSeconds() {
        return etaSeconds;
    }

    public void setEtaSeconds(Long etaSeconds) {
        this.etaSeconds = etaSeconds;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }
}
