package ru.mrcrubs.lmsnode.model;

import java.time.Instant;
import java.util.UUID;

public class DownloadJob {
    private final UUID jobId;
    private final JobType type;
    private final String url;
    private final String storagePath;
    private volatile JobStatus status;
    private volatile Double percent;
    private volatile Long speedBytes;
    private volatile Long etaSeconds;
    private volatile Long totalBytes;
    private volatile String message;
    private final Instant createdAt;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String outputPath;
    private volatile Long outputSizeBytes;

    public DownloadJob(UUID jobId, JobType type, String url, String storagePath, Instant createdAt) {
        this.jobId = jobId;
        this.type = type;
        this.url = url;
        this.storagePath = storagePath;
        this.createdAt = createdAt;
        this.status = JobStatus.QUEUED;
    }

    public DownloadJob(UUID jobId, JobType type, String url, Instant createdAt) {
        this(jobId, type, url, null, createdAt);
    }

    /**
     * Rebuilds a job from persisted state. Progress/timestamps are restored through setters.
     */
    public static DownloadJob restore(UUID jobId, JobType type, String url, String storagePath,
                                      JobStatus status, Instant createdAt) {
        DownloadJob job = new DownloadJob(jobId, type, url, storagePath, createdAt);
        job.status = status == null ? JobStatus.QUEUED : status;
        return job;
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

    public String getStoragePath() {
        return storagePath;
    }

    public JobStatus getStatus() {
        return status;
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

    public Long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(Long totalBytes) {
        this.totalBytes = totalBytes;
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

    public Long getOutputSizeBytes() {
        return outputSizeBytes;
    }

    public void setOutputSizeBytes(Long outputSizeBytes) {
        this.outputSizeBytes = outputSizeBytes;
    }

    public boolean isTerminal() {
        return status == JobStatus.DONE || status == JobStatus.ERROR || status == JobStatus.CANCELED;
    }

    public boolean start(Instant at, String initialMessage) {
        if (status != JobStatus.QUEUED) {
            return false;
        }
        status = JobStatus.RUNNING;
        startedAt = at;
        message = initialMessage;
        return true;
    }

    public boolean cancel(Instant at, String cancelMessage) {
        if (isTerminal()) {
            return false;
        }
        status = JobStatus.CANCELED;
        finishedAt = at;
        message = cancelMessage;
        return true;
    }

    public boolean pause(Instant at, String pauseMessage) {
        if (status != JobStatus.QUEUED && status != JobStatus.RUNNING) {
            return false;
        }
        status = JobStatus.PAUSED;
        message = pauseMessage;
        speedBytes = null;
        etaSeconds = null;
        // Keep startedAt when pausing a running job.
        if (finishedAt != null) {
            finishedAt = null;
        }
        return true;
    }

    public boolean resume(Instant at, String resumeMessage) {
        if (status != JobStatus.PAUSED) {
            return false;
        }
        status = JobStatus.QUEUED;
        message = resumeMessage;
        if (finishedAt != null) {
            finishedAt = null;
        }
        return true;
    }

    public boolean retry(Instant at, String retryMessage) {
        if (status != JobStatus.ERROR && status != JobStatus.CANCELED) {
            return false;
        }
        status = JobStatus.QUEUED;
        message = retryMessage;
        finishedAt = null;
        return true;
    }

    /**
     * Puts a job that was active when the node stopped back into the queue.
     */
    public boolean requeueAfterRestart(String requeueMessage) {
        if (status != JobStatus.RUNNING && status != JobStatus.QUEUED) {
            return false;
        }
        status = JobStatus.QUEUED;
        message = requeueMessage;
        speedBytes = null;
        etaSeconds = null;
        return true;
    }

    /**
     * Completes the job. A job paused (or paused and resumed) while its download was
     * already finishing is completed too: the file is there, restarting would be wasteful.
     */
    public boolean complete(Instant at, String doneMessage, String finalOutputPath) {
        if (status != JobStatus.RUNNING && status != JobStatus.PAUSED && status != JobStatus.QUEUED) {
            return false;
        }
        status = JobStatus.DONE;
        finishedAt = at;
        percent = 100.0;
        speedBytes = null;
        etaSeconds = 0L;
        message = doneMessage;
        outputPath = finalOutputPath;
        return true;
    }

    public boolean fail(Instant at, String errorMessage) {
        if (status != JobStatus.RUNNING) {
            return false;
        }
        status = JobStatus.ERROR;
        finishedAt = at;
        message = errorMessage;
        speedBytes = null;
        etaSeconds = null;
        return true;
    }
}
