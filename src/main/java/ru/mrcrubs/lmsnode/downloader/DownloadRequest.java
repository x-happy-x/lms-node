package ru.mrcrubs.lmsnode.downloader;

import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Path;
import java.util.UUID;

public record DownloadRequest(
        UUID jobId,
        JobType type,
        String url,
        Path downloadDir
) {
}
