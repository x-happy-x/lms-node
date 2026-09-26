package ru.mrcrubs.lmsnode.api;

import java.util.List;

/**
 * What yt-dlp found at a URL: one video ({@code kind = "video"}) or a playlist / page with
 * several videos ({@code kind = "playlist"}, see {@link #entries()}).
 *
 * @param url         the URL to hand to a YTDLP job for the whole thing (the page itself)
 * @param entryCount  total entries when yt-dlp reports it; may exceed {@code entries.size()}
 * @param truncated   true when the list was cut at the node's limit
 */
public record MediaExtractResponse(
        String url,
        String kind,
        String title,
        String extractor,
        Long durationSeconds,
        String thumbnail,
        Long sizeBytes,
        List<Entry> entries,
        Integer entryCount,
        boolean truncated
) {
    /** One playlist item; {@code url} can be sent as its own YTDLP job. */
    public record Entry(String url, String title, Long durationSeconds, String thumbnail) {
    }
}
