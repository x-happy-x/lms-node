package ru.mrcrubs.lmsnode.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import ru.mrcrubs.lmsnode.downloader.TorrentDownloader;
import ru.mrcrubs.lmsnode.model.JobType;

public record CreateJobRequest(
        @NotNull JobType type,
        @NotBlank
        @Pattern(regexp = "(?i)(https?://|magnet:\\?).+", message = "url must start with http://, https:// or magnet:?")
        String url,
        String storagePath,
        Boolean startImmediately
) {
    public boolean shouldStartImmediately() {
        return startImmediately == null || startImmediately;
    }

    @AssertTrue(message = "magnet links are supported only by TORRENT jobs")
    public boolean isUrlSupportedByType() {
        return type == JobType.TORRENT || !TorrentDownloader.isMagnet(url);
    }
}
