package ru.mrcrubs.lmsnode.downloader;

import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Path;
import java.util.UUID;

/**
 * @param maxSpeedBytes speed limit in bytes per second at start (null = unlimited); DIRECT
 *                      downloads follow later changes through {@link DownloadExecutionContext}
 */
public record DownloadRequest(
        UUID jobId,
        JobType type,
        String url,
        Path downloadDir,
        Long maxSpeedBytes
) {
    public DownloadRequest(UUID jobId, JobType type, String url, Path downloadDir) {
        this(jobId, type, url, downloadDir, null);
    }

    public boolean hasSpeedLimit() {
        return maxSpeedBytes != null && maxSpeedBytes > 0;
    }
}
