package ru.mrcrubs.lmsnode.downloader;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DirectHttpDownloaderTest {

    private final DirectHttpDownloader downloader = new DirectHttpDownloader();

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

    private HttpResponse<?> responseWithHeaders(Map<String, List<String>> headers) {
        HttpResponse<?> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (x, y) -> true));
        return response;
    }
}
