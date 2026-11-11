package ru.mrcrubs.lmsnode.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import ru.mrcrubs.lmsnode.downloader.TorrentDownloader;
import ru.mrcrubs.lmsnode.model.JobType;
import ru.mrcrubs.lmsnode.model.YtDlpOptions;

public record CreateJobRequest(
        @NotNull JobType type,
        @NotBlank
        @Pattern(regexp = "(?i)(https?://|magnet:\\?).+", message = "url must start with http://, https:// or magnet:?")
        String url,
        String storagePath,
        Boolean startImmediately,
        @PositiveOrZero Long maxSpeedBytes,
        @Valid YtDlpOptions ytdlpOptions,
        String networkRouteId
) {
    public CreateJobRequest(JobType type, String url, String storagePath, Boolean startImmediately, Long maxSpeedBytes, YtDlpOptions options) {
        this(type,url,storagePath,startImmediately,maxSpeedBytes,options,null);
    }
    public boolean shouldStartImmediately() {
        return startImmediately == null || startImmediately;
    }

    @AssertTrue(message = "magnet links are supported only by TORRENT jobs")
    public boolean isUrlSupportedByType() {
        return type == JobType.TORRENT || !TorrentDownloader.isMagnet(url);
    }

    @AssertTrue(message = "ytdlpOptions require YTDLP job type")
    public boolean isYtdlpOptionsSupportedByType() {
        return ytdlpOptions == null || type == JobType.YTDLP;
    }
}
