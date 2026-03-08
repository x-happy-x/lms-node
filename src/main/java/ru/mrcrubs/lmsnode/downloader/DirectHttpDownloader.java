package ru.mrcrubs.lmsnode.downloader;

import org.springframework.stereotype.Component;
import ru.mrcrubs.lmsnode.model.JobType;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;

@Component
public class DirectHttpDownloader implements Downloader {
    private static final int BUFFER_SIZE = 8192;

    @Override
    public JobType id() {
        return JobType.DIRECT;
    }

    @Override
    public DownloadResult download(DownloadRequest request,
                                   DownloadExecutionContext context,
                                   java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP error: " + response.statusCode());
        }

        Path outputFile = resolveOutputPath(request, response);
        Files.createDirectories(outputFile.getParent());

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        byte[] buffer = new byte[BUFFER_SIZE];
        long downloadedBytes = 0L;
        long speedWindowBytes = 0L;
        Instant speedWindowStart = Instant.now();

        try (InputStream inputStream = response.body();
             OutputStream outputStream = Files.newOutputStream(outputFile)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                if (context.isCanceled()) {
                    throw new CancellationException("Download canceled");
                }
                outputStream.write(buffer, 0, read);
                downloadedBytes += read;
                speedWindowBytes += read;

                Instant now = Instant.now();
                long elapsedMillis = Duration.between(speedWindowStart, now).toMillis();
                if (elapsedMillis >= 1000) {
                    long speed = elapsedMillis > 0 ? (speedWindowBytes * 1000L) / elapsedMillis : 0;
                    Double percent = contentLength > 0 ? (downloadedBytes * 100.0) / contentLength : null;
                    Long eta = (contentLength > 0 && speed > 0) ? Math.max(0, (contentLength - downloadedBytes) / speed) : null;
                    progressConsumer.accept(new ProgressUpdate(percent, speed, eta, "Downloading via HTTP"));
                    speedWindowBytes = 0L;
                    speedWindowStart = now;
                }
            }
        }

        progressConsumer.accept(new ProgressUpdate(100.0, null, 0L, "HTTP download finished"));
        return new DownloadResult(outputFile.toString(), "Completed");
    }

    Path resolveOutputPath(DownloadRequest request, HttpResponse<?> response) {
        Optional<String> contentDisposition = response.headers().firstValue("Content-Disposition");
        if (contentDisposition.isPresent()) {
            String header = contentDisposition.get();
            String marker = "filename=";
            int idx = header.toLowerCase().indexOf(marker);
            if (idx >= 0) {
                String raw = header.substring(idx + marker.length()).trim().replace("\"", "");
                if (!raw.isBlank()) {
                    return request.downloadDir().resolve(sanitizeFileName(raw, request.jobId()));
                }
            }
        }

        String path = URI.create(request.url()).getPath();
        String fileName = (path == null || path.isBlank() || path.endsWith("/"))
                ? request.jobId() + ".bin"
                : path.substring(path.lastIndexOf('/') + 1);
        return request.downloadDir().resolve(sanitizeFileName(fileName, request.jobId()));
    }

    private String sanitizeFileName(String candidate, java.util.UUID jobId) {
        String normalized = candidate.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.trim();
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)) {
            return jobId + ".bin";
        }
        return normalized;
    }
}
