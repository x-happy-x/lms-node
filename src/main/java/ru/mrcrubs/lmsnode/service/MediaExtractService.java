package ru.mrcrubs.lmsnode.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.mrcrubs.lmsnode.api.MediaExtractResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Asks yt-dlp what a page contains without downloading: {@code -J --flat-playlist} lists
 * playlists, channels and pages with several embedded videos as entries with their own URLs,
 * and single videos with title, duration and size. Works for any site yt-dlp supports plus its
 * generic extractor (HTML5 video, embeds) on other pages.
 */
@Service
public class MediaExtractService {
    static final int MAX_ENTRIES = 300;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String binary;
    private final long timeoutSeconds;

    public MediaExtractService(@Value("${node.tools.ytdlp:yt-dlp}") String binary,
                               @Value("${node.extract.timeout-seconds:90}") long timeoutSeconds) {
        this.binary = binary;
        this.timeoutSeconds = timeoutSeconds;
    }

    public MediaExtractResponse extract(String url) {
        List<String> command = buildCommand(url);
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new MediaExtractException("yt-dlp is not available on node: " + ex.getMessage(), ex);
        }
        try {
            // stdout (one JSON document) and stderr are drained in parallel so neither pipe fills up.
            var stdout = new Drain(process.getInputStream());
            var stderr = new Drain(process.getErrorStream());
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new MediaExtractException("yt-dlp did not answer in " + timeoutSeconds + " s");
            }
            String out = stdout.text();
            if (process.exitValue() != 0 || out.isBlank()) {
                throw new MediaExtractException(errorMessage(stderr.text(), process.exitValue()));
            }
            return parse(out, url);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new MediaExtractException("interrupted", ex);
        }
    }

    List<String> buildCommand(String url) {
        return List.of(binary,
                "-J", "--flat-playlist",
                "--no-warnings", "--no-progress",
                "--playlist-end", String.valueOf(MAX_ENTRIES),
                "--socket-timeout", "20",
                "--", url);
    }

    static String errorMessage(String stderr, int exitCode) {
        String lastError = null;
        for (String line : stderr.split("\\R")) {
            if (line.startsWith("ERROR:")) {
                lastError = line.substring("ERROR:".length()).strip();
            }
        }
        if (lastError != null) {
            return lastError;
        }
        String trimmed = stderr.strip();
        return trimmed.isEmpty() ? "yt-dlp exited with code " + exitCode : trimmed;
    }

    MediaExtractResponse parse(String json, String requestedUrl) {
        Map<String, Object> info;
        try {
            info = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new MediaExtractException("unexpected yt-dlp output", ex);
        }
        String pageUrl = firstHttp(string(info, "webpage_url"), string(info, "original_url"), requestedUrl);
        String extractor = string(info, "extractor_key") != null ? string(info, "extractor_key") : string(info, "extractor");
        Object rawEntries = info.get("entries");
        boolean playlist = "playlist".equals(string(info, "_type")) || rawEntries instanceof List<?>;

        if (!playlist) {
            Long size = number(info, "filesize") != null ? number(info, "filesize") : number(info, "filesize_approx");
            return new MediaExtractResponse(pageUrl, "video", string(info, "title"), extractor,
                    number(info, "duration"), thumbnail(info), size, List.of(), null, false);
        }

        List<MediaExtractResponse.Entry> entries = new ArrayList<>();
        if (rawEntries instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) raw;
                String entryUrl = firstHttp(string(entry, "url"), string(entry, "webpage_url"), string(entry, "original_url"));
                if (entryUrl == null) {
                    continue; // bare ids from extractors that do not give URLs
                }
                entries.add(new MediaExtractResponse.Entry(entryUrl, string(entry, "title"),
                        number(entry, "duration"), thumbnail(entry)));
            }
        }
        Long total = number(info, "playlist_count");
        Integer entryCount = total != null ? Math.toIntExact(total) : entries.size();
        boolean truncated = entries.size() >= MAX_ENTRIES && (total == null || total > entries.size());
        return new MediaExtractResponse(pageUrl, "playlist", string(info, "title"), extractor,
                null, thumbnail(info), null, entries, entryCount, truncated);
    }

    private static String firstHttp(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && (candidate.startsWith("http://") || candidate.startsWith("https://"))) {
                return candidate;
            }
        }
        return null;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number n ? Math.round(n.doubleValue()) : null;
    }

    private static String thumbnail(Map<String, Object> map) {
        String direct = string(map, "thumbnail");
        if (direct != null) {
            return direct;
        }
        // Flat entries carry a list; the last one is the largest.
        if (map.get("thumbnails") instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i) instanceof Map<?, ?> thumb && thumb.get("url") instanceof String url) {
                    return url;
                }
            }
        }
        return null;
    }

    /** Reads a stream to the end on a virtual thread. */
    private static final class Drain {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;

        Drain(InputStream stream) {
            thread = Thread.ofVirtual().start(() -> {
                try (stream) {
                    stream.transferTo(buffer);
                } catch (IOException ignored) {
                    // process ended
                }
            });
        }

        String text() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            synchronized (buffer) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
