package ru.mrcrubs.lmsnode.downloader;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DirectHttpDownloaderTest {

    private final DirectHttpDownloader downloader = new DirectHttpDownloader();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolveOutputPathShouldUseContentDispositionFileName() {
        DownloadRequest request = new DownloadRequest(UUID.randomUUID(), null, "https://example.com/ignored", Path.of("/tmp/downloads"));
        HttpResponse<?> response = responseWithHeaders(Map.of("Content-Disposition", List.of("attachment; filename=video.mp4")));

        Path resolved = downloader.resolveOutputPath(request, response);

        assertEquals(Path.of("/tmp/downloads/video.mp4"), resolved);
    }

    @Test
    void resolveOutputPathShouldSanitizePathTraversalFromContentDisposition() {
        DownloadRequest request = new DownloadRequest(UUID.randomUUID(), null, "https://example.com/ignored", Path.of("/tmp/downloads"));
        HttpResponse<?> response = responseWithHeaders(Map.of("Content-Disposition", List.of("attachment; filename=../../evil.sh")));

        Path resolved = downloader.resolveOutputPath(request, response);

        assertEquals(Path.of("/tmp/downloads/evil.sh"), resolved);
    }

    @Test
    void resolveOutputPathShouldFallbackToUrlFileName() {
        UUID jobId = UUID.randomUUID();
        DownloadRequest request = new DownloadRequest(jobId, null, "https://example.com/files/archive.tar", Path.of("/tmp/downloads"));
        HttpResponse<?> response = responseWithHeaders(Map.of());

        Path resolved = downloader.resolveOutputPath(request, response);

        assertEquals(Path.of("/tmp/downloads/archive.tar"), resolved);
    }

    @Test
    void resolveOutputPathShouldFallbackToJobIdWhenUrlHasNoFileName() {
        UUID jobId = UUID.randomUUID();
        DownloadRequest request = new DownloadRequest(jobId, null, "https://example.com/files/", Path.of("/tmp/downloads"));
        HttpResponse<?> response = responseWithHeaders(Map.of());

        Path resolved = downloader.resolveOutputPath(request, response);

        assertEquals(Path.of("/tmp/downloads/" + jobId + ".bin"), resolved);
    }

    @Test
    void downloadShouldResumeFromPartialWhenServerSupportsRange(@TempDir Path tempDir) throws Exception {
        byte[] content = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
        RequestLog requestLog = new RequestLog();
        server = startServer(new ResumeHandler(content, true, "\"v1\"", requestLog));

        UUID jobId = UUID.randomUUID();
        DownloadRequest request = new DownloadRequest(jobId, null, url("/file.bin"), tempDir);
        Files.write(tempDir.resolve(jobId + ".part"), slice(content, 0, 10));
        writeMetadata(tempDir.resolve(jobId + ".part.meta"), request.url(), "file.bin", "\"v1\"", null, content.length);

        List<ProgressUpdate> progress = new ArrayList<>();
        DownloadResult result = downloader.download(request, new DownloadExecutionContext(), progress::add);

        assertEquals(tempDir.resolve("file.bin").toString(), result.outputPath());
        assertEquals(new String(content, StandardCharsets.UTF_8), Files.readString(tempDir.resolve("file.bin")));
        assertFalse(Files.exists(tempDir.resolve(jobId + ".part")));
        assertFalse(Files.exists(tempDir.resolve(jobId + ".part.meta")));
        assertEquals(1, requestLog.requestCount.get());
        assertEquals("bytes=10-", requestLog.ranges.getFirst());
        assertEquals("\"v1\"", requestLog.ifRanges.getFirst());
        assertTrue(progress.stream().anyMatch(update -> update.percent() != null && update.percent() == 100.0));
    }

    @Test
    void downloadShouldRestartWhenServerIgnoresRange(@TempDir Path tempDir) throws Exception {
        byte[] content = "fresh-content".getBytes(StandardCharsets.UTF_8);
        RequestLog requestLog = new RequestLog();
        server = startServer(new ResumeHandler(content, false, "\"v1\"", requestLog));

        UUID jobId = UUID.randomUUID();
        DownloadRequest request = new DownloadRequest(jobId, null, url("/file.bin"), tempDir);
        Files.write(tempDir.resolve(jobId + ".part"), "stale".getBytes(StandardCharsets.UTF_8));
        writeMetadata(tempDir.resolve(jobId + ".part.meta"), request.url(), "file.bin", "\"v1\"", null, 99L);

        downloader.download(request, new DownloadExecutionContext(), update -> {
        });

        assertEquals(new String(content, StandardCharsets.UTF_8), Files.readString(tempDir.resolve("file.bin")));
        assertEquals(2, requestLog.requestCount.get());
        assertEquals("bytes=5-", requestLog.ranges.getFirst());
        assertTrue(requestLog.ranges.get(1) == null || requestLog.ranges.get(1).isBlank());
        assertFalse(Files.exists(tempDir.resolve(jobId + ".part")));
        assertFalse(Files.exists(tempDir.resolve(jobId + ".part.meta")));
    }

    @Test
    void downloadShouldRestartWhenValidatorChanged(@TempDir Path tempDir) throws Exception {
        byte[] content = "new-version-content".getBytes(StandardCharsets.UTF_8);
        RequestLog requestLog = new RequestLog();
        server = startServer(new ResumeHandler(content, true, "\"v2\"", requestLog));

        UUID jobId = UUID.randomUUID();
        DownloadRequest request = new DownloadRequest(jobId, null, url("/file.bin"), tempDir);
        Files.write(tempDir.resolve(jobId + ".part"), "old-version".getBytes(StandardCharsets.UTF_8));
        writeMetadata(tempDir.resolve(jobId + ".part.meta"), request.url(), "file.bin", "\"v1\"", null, 100L);

        downloader.download(request, new DownloadExecutionContext(), update -> {
        });

        assertEquals(new String(content, StandardCharsets.UTF_8), Files.readString(tempDir.resolve("file.bin")));
        assertEquals(2, requestLog.requestCount.get());
        assertEquals("\"v1\"", requestLog.ifRanges.getFirst());
        assertTrue(requestLog.ifRanges.get(1) == null || requestLog.ifRanges.get(1).isBlank());
    }

    private HttpResponse<?> responseWithHeaders(Map<String, List<String>> headers) {
        HttpResponse<?> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (x, y) -> true));
        return response;
    }

    private HttpServer startServer(ResumeHandler handler) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress(0), 0);
        created.createContext("/file.bin", handler);
        created.start();
        return created;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private byte[] slice(byte[] bytes, int start, int end) {
        byte[] slice = new byte[end - start];
        System.arraycopy(bytes, start, slice, 0, end - start);
        return slice;
    }

    private void writeMetadata(Path path,
                               String url,
                               String fileName,
                               String etag,
                               String lastModified,
                               long totalBytes) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("url=" + url.replace(":", "\\:"));
        lines.add("fileName=" + fileName);
        if (etag != null) {
            lines.add("etag=" + etag);
        }
        if (lastModified != null) {
            lines.add("lastModified=" + lastModified.replace(":", "\\:"));
        }
        lines.add("totalBytes=" + totalBytes);
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static final class RequestLog {
        private final AtomicInteger requestCount = new AtomicInteger();
        private final CopyOnWriteArrayList<String> ranges = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> ifRanges = new CopyOnWriteArrayList<>();
    }

    private static final class ResumeHandler implements com.sun.net.httpserver.HttpHandler {
        private final byte[] body;
        private final boolean supportsRange;
        private final String etag;
        private final RequestLog requestLog;

        private ResumeHandler(byte[] body, boolean supportsRange, String etag, RequestLog requestLog) {
            this.body = body;
            this.supportsRange = supportsRange;
            this.etag = etag;
            this.requestLog = requestLog;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            requestLog.requestCount.incrementAndGet();
            Headers requestHeaders = exchange.getRequestHeaders();
            String range = requestHeaders.getFirst("Range");
            String ifRange = requestHeaders.getFirst("If-Range");
            requestLog.ranges.add(range);
            requestLog.ifRanges.add(ifRange);

            Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.add("Content-Disposition", "attachment; filename=file.bin");
            responseHeaders.add("ETag", etag);
            if (supportsRange) {
                responseHeaders.add("Accept-Ranges", "bytes");
            }

            if (range != null && supportsRange && (ifRange == null || ifRange.equals(etag))) {
                long start = Long.parseLong(range.substring("bytes=".length(), range.length() - 1));
                byte[] slice = new byte[(int) (body.length - start)];
                System.arraycopy(body, (int) start, slice, 0, slice.length);
                responseHeaders.add("Content-Range", "bytes " + start + "-" + (body.length - 1) + "/" + body.length);
                exchange.sendResponseHeaders(206, slice.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(slice);
                }
                return;
            }

            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        }
    }
}
