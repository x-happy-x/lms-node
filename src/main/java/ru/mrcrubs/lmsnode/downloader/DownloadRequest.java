package ru.mrcrubs.lmsnode.downloader;

import ru.mrcrubs.lmsnode.model.JobType;
import ru.mrcrubs.lmsnode.model.YtDlpOptions;

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
        Long maxSpeedBytes,
        YtDlpOptions ytdlpOptions,
        ru.mrcrubs.lmsnode.service.NetworkRoutes.Route networkRoute
) {
    public DownloadRequest(UUID jobId, JobType type, String url, Path downloadDir, Long maxSpeedBytes, YtDlpOptions options) {
        this(jobId,type,url,downloadDir,maxSpeedBytes,options,null);
    }
    public DownloadRequest(UUID jobId, JobType type, String url, Path downloadDir, Long maxSpeedBytes) {
        this(jobId, type, url, downloadDir, maxSpeedBytes, null);
    }

    public DownloadRequest(UUID jobId, JobType type, String url, Path downloadDir) {
        this(jobId, type, url, downloadDir, null);
    }

    public boolean hasSpeedLimit() {
        return maxSpeedBytes != null && maxSpeedBytes > 0;
    }
}
