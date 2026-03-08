package ru.mrcrubs.lmsnode.downloader;

import ru.mrcrubs.lmsnode.model.JobType;

public interface Downloader {
    JobType id();

    DownloadResult download(DownloadRequest request, DownloadExecutionContext context, java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception;
}
