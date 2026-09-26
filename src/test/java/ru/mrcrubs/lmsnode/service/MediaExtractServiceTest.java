package ru.mrcrubs.lmsnode.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.mrcrubs.lmsnode.api.MediaExtractResponse;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaExtractServiceTest {
    private final MediaExtractService service = new MediaExtractService("yt-dlp", 60);

    @Test
    void parsesFlatPlaylistEntries() {
        String json = """
                {"_type":"playlist","title":"My list","extractor_key":"YoutubeTab","playlist_count":3,
                 "webpage_url":"https://www.youtube.com/playlist?list=PL1",
                 "entries":[
                   {"_type":"url","url":"https://www.youtube.com/watch?v=a","title":"First","duration":61.5,
                    "thumbnails":[{"url":"https://i.ytimg.com/a/small.jpg"},{"url":"https://i.ytimg.com/a/big.jpg"}]},
                   {"_type":"url","url":"b","title":"Bare id"},
                   {"_type":"url","url":"https://www.youtube.com/watch?v=c","title":"Third"}
                 ]}
                """;
        MediaExtractResponse result = service.parse(json, "https://www.youtube.com/playlist?list=PL1");

        assertEquals("playlist", result.kind());
        assertEquals("My list", result.title());
        assertEquals("YoutubeTab", result.extractor());
        assertEquals(3, result.entryCount());
        assertEquals(2, result.entries().size(), "entries without a URL are skipped");
        assertEquals("https://www.youtube.com/watch?v=a", result.entries().get(0).url());
        assertEquals(62L, result.entries().get(0).durationSeconds());
        assertEquals("https://i.ytimg.com/a/big.jpg", result.entries().get(0).thumbnail());
        assertFalse(result.truncated());
    }

    @Test
    void parsesSingleVideoWithPageUrl() {
        String json = """
                {"_type":"video","id":"x","title":"Clip","extractor":"html5","duration":3,
                 "url":"https://cdn.example.com/clip.mp4","webpage_url":"https://example.com/page",
                 "filesize_approx":1048576,"thumbnail":"https://example.com/t.jpg"}
                """;
        MediaExtractResponse result = service.parse(json, "https://example.com/page");

        assertEquals("video", result.kind());
        assertEquals("https://example.com/page", result.url(), "the page, not the media URL, goes to yt-dlp jobs");
        assertEquals(1_048_576L, result.sizeBytes());
        assertEquals(3L, result.durationSeconds());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void errorMessageUsesLastYtDlpError() {
        assertEquals("Unsupported URL: https://x", MediaExtractService.errorMessage(
                "WARNING: something\nERROR: [generic] first\nERROR: Unsupported URL: https://x\n", 1));
        assertEquals("yt-dlp exited with code 2", MediaExtractService.errorMessage("  ", 2));
    }

    @Test
    void rejectsGarbageOutput() {
        assertThrows(MediaExtractException.class, () -> service.parse("not json", "https://x"));
    }

    @Test
    void findsVideosOnARealPage() throws Exception {
        Assumptions.assumeTrue(onPath("yt-dlp"), "yt-dlp is not installed");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] page = """
                <html><head><title>Two videos</title></head><body>
                <video src="a.mp4"></video><video><source src="b.mp4" type="video/mp4"></video>
                </body></html>""".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            MediaExtractResponse result = service.extract(base + "/page.html");
            assertEquals("playlist", result.kind());
            assertEquals(2, result.entries().size());
            assertEquals(base + "/a.mp4", result.entries().get(0).url());
            assertEquals(base + "/b.mp4", result.entries().get(1).url());

            MediaExtractException error = assertThrows(MediaExtractException.class,
                    () -> service.extract(base.replace(String.valueOf(server.getAddress().getPort()), "1") + "/none"));
            assertFalse(error.getMessage().isBlank());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingBinaryIsReported() {
        MediaExtractService missing = new MediaExtractService("/nonexistent/yt-dlp", 5);
        MediaExtractException error = assertThrows(MediaExtractException.class, () -> missing.extract("https://x"));
        assertTrue(error.getMessage().contains("not available"));
    }

    private static boolean onPath(String binary) {
        return Arrays.stream(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .anyMatch(dir -> new File(dir, binary).canExecute());
    }
}
