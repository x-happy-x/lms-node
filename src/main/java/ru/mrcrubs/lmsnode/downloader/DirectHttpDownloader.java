package ru.mrcrubs.lmsnode.downloader;

import org.springframework.stereotype.Component;
import ru.mrcrubs.lmsnode.model.JobType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DirectHttpDownloader implements Downloader {
    private static final int BUFFER_SIZE = 8192;
    private static final String META_URL = "url";
    private static final String META_FILE_NAME = "fileName";
    private static final String META_ETAG = "etag";
    private static final String META_LAST_MODIFIED = "lastModified";
    private static final String META_TOTAL_BYTES = "totalBytes";

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

        Path partialFile = partialPath(request);
        Path metadataFile = metadataPath(request);
        long existingBytes = Files.exists(partialFile) ? Files.size(partialFile) : 0L;
        PartialDownloadMetadata metadata = loadMetadata(metadataFile);
        if (!isResumeCandidate(request, existingBytes, metadata)) {
            resetPartialState(partialFile, metadataFile);
            existingBytes = 0L;
            metadata = null;
        }

        HttpResponse<InputStream> response;
        boolean resumed = false;
        if (existingBytes > 0) {
            response = client.send(buildGetRequest(request.url(), "bytes=" + existingBytes + "-", metadata),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 206 && isValidResumeResponse(response, existingBytes, metadata)) {
                resumed = true;
            } else {
                closeQuietly(response.body());
                resetPartialState(partialFile, metadataFile);
                existingBytes = 0L;
                metadata = null;
                response = client.send(buildGetRequest(request.url(), null, null), HttpResponse.BodyHandlers.ofInputStream());
            }
        } else {
            response = client.send(buildGetRequest(request.url(), null, null), HttpResponse.BodyHandlers.ofInputStream());
        }

        if (response.statusCode() >= 400) {
            closeQuietly(response.body());
            throw new IllegalStateException("HTTP error: " + response.statusCode());
        }

        Path outputFile = resumed
                ? request.downloadDir().resolve(metadata.fileName())
                : resolveOutputPath(request, response);
        Files.createDirectories(outputFile.getParent());

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        long expectedTotalBytes = determineExpectedTotalBytes(response, existingBytes, resumed, metadata, contentLength);
        PartialDownloadMetadata currentMetadata = new PartialDownloadMetadata(
                request.url(),
                outputFile.getFileName().toString(),
                response.headers().firstValue("ETag").orElse(metadata == null ? null : metadata.etag()),
                response.headers().firstValue("Last-Modified").orElse(metadata == null ? null : metadata.lastModified()),
                expectedTotalBytes > 0 ? expectedTotalBytes : null
        );
        saveMetadata(metadataFile, currentMetadata);

        byte[] buffer = new byte[BUFFER_SIZE];
        long downloadedBytes = existingBytes;
        long speedWindowBytes = 0L;
        Instant speedWindowStart = Instant.now();
        AtomicReference<InputStream> activeInput = new AtomicReference<>();
        AtomicReference<OutputStream> activeOutput = new AtomicReference<>();
        HttpResponse<InputStream> finalResponse = response;

        context.registerCancelAction(() -> {
            closeQuietly(activeInput.get());
            closeQuietly(activeOutput.get());
            closeQuietly(finalResponse.body());
        });

        try (InputStream inputStream = response.body();
             OutputStream outputStream = Files.newOutputStream(
                     partialFile,
                     StandardOpenOption.CREATE,
                     resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING
             )) {
            activeInput.set(inputStream);
            activeOutput.set(outputStream);

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
                    Double percent = expectedTotalBytes > 0 ? (downloadedBytes * 100.0) / expectedTotalBytes : null;
                    Long eta = (expectedTotalBytes > 0 && speed > 0)
                            ? Math.max(0, (expectedTotalBytes - downloadedBytes) / speed)
                            : null;
                    String message = expectedTotalBytes > 0 ? "Downloading via HTTP" : "Downloading via HTTP (unknown size)";
                    progressConsumer.accept(new ProgressUpdate(percent, expectedTotalBytes > 0 ? expectedTotalBytes : null, speed, eta, message));
                    speedWindowBytes = 0L;
                    speedWindowStart = now;
                }
            }
        } catch (IOException ex) {
            if (context.isCanceled()) {
                throw new CancellationException("Download canceled");
            }
            throw ex;
        } finally {
            activeInput.set(null);
            activeOutput.set(null);
        }

        movePartialToFinal(partialFile, outputFile);
        Files.deleteIfExists(metadataFile);
        progressConsumer.accept(new ProgressUpdate(100.0, Files.size(outputFile), null, 0L, "HTTP download finished"));
        return new DownloadResult(outputFile.toString(), resumed ? "Completed (resumed)" : "Completed");
    }

    private boolean isResumeCandidate(DownloadRequest request, long existingBytes, PartialDownloadMetadata metadata) {
        return existingBytes > 0
                && metadata != null
                && request.url().equals(metadata.url())
                && metadata.fileName() != null
                && !metadata.fileName().isBlank()
                && metadata.hasValidator();
    }

    private HttpRequest buildGetRequest(String rawUrl, String rangeHeader, PartialDownloadMetadata metadata) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(rawUrl))
                .timeout(Duration.ofMinutes(30))
                .GET();
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            requestBuilder.header("Range", rangeHeader);
            String ifRange = metadata == null ? null : metadata.ifRangeValue();
            if (ifRange != null && !ifRange.isBlank()) {
                requestBuilder.header("If-Range", ifRange);
            }
        }
        return requestBuilder.build();
    }

    private boolean isValidResumeResponse(HttpResponse<?> response, long existingBytes, PartialDownloadMetadata metadata) {
        String contentRange = response.headers().firstValue("Content-Range").orElse(null);
        if (contentRange == null || !contentRange.startsWith("bytes ")) {
            return false;
        }
        int dash = contentRange.indexOf('-', 6);
        int slash = contentRange.indexOf('/', dash + 1);
        if (dash < 0 || slash < 0) {
            return false;
        }
        try {
            long rangeStart = Long.parseLong(contentRange.substring(6, dash));
            long totalBytes = Long.parseLong(contentRange.substring(slash + 1));
            if (rangeStart != existingBytes) {
                return false;
            }
            return metadata.totalBytes() == null || metadata.totalBytes() == totalBytes;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private long determineExpectedTotalBytes(HttpResponse<?> response,
                                             long existingBytes,
                                             boolean resumed,
                                             PartialDownloadMetadata metadata,
                                             long contentLength) {
        String contentRange = response.headers().firstValue("Content-Range").orElse(null);
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < contentRange.length()) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1));
                } catch (NumberFormatException ignored) {
                    // Fall through to other signals.
                }
            }
        }
        if (contentLength > 0) {
            return resumed ? existingBytes + contentLength : contentLength;
        }
        return metadata != null && metadata.totalBytes() != null ? metadata.totalBytes() : -1L;
    }

    private Path partialPath(DownloadRequest request) {
        return request.downloadDir().resolve(request.jobId() + ".part");
    }

    private Path metadataPath(DownloadRequest request) {
        return request.downloadDir().resolve(request.jobId() + ".part.meta");
    }

    private void movePartialToFinal(Path partialFile, Path outputFile) throws IOException {
        try {
            Files.move(partialFile, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(partialFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
        }
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

    private PartialDownloadMetadata loadMetadata(Path metadataPath) {
        if (!Files.exists(metadataPath)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(metadataPath)) {
            properties.load(inputStream);
            return new PartialDownloadMetadata(
                    properties.getProperty(META_URL),
                    properties.getProperty(META_FILE_NAME),
                    blankToNull(properties.getProperty(META_ETAG)),
                    blankToNull(properties.getProperty(META_LAST_MODIFIED)),
                    parseLong(properties.getProperty(META_TOTAL_BYTES))
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private void saveMetadata(Path metadataPath, PartialDownloadMetadata metadata) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(META_URL, metadata.url());
        properties.setProperty(META_FILE_NAME, metadata.fileName());
        if (metadata.etag() != null) {
            properties.setProperty(META_ETAG, metadata.etag());
        }
        if (metadata.lastModified() != null) {
            properties.setProperty(META_LAST_MODIFIED, metadata.lastModified());
        }
        if (metadata.totalBytes() != null && metadata.totalBytes() > 0) {
            properties.setProperty(META_TOTAL_BYTES, String.valueOf(metadata.totalBytes()));
        }
        try (OutputStream outputStream = Files.newOutputStream(
                metadataPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            properties.store(new java.io.OutputStreamWriter(outputStream, StandardCharsets.UTF_8), "direct-download-meta");
        }
    }

    private void resetPartialState(Path partialFile, Path metadataFile) {
        closeQuietly(null);
        try {
            Files.deleteIfExists(partialFile);
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
        try {
            Files.deleteIfExists(metadataFile);
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort close for cancellation and cleanup.
        }
    }

    record PartialDownloadMetadata(String url, String fileName, String etag, String lastModified, Long totalBytes) {
        boolean hasValidator() {
            return (etag != null && !etag.isBlank()) || (lastModified != null && !lastModified.isBlank());
        }

        String ifRangeValue() {
            if (etag != null && !etag.isBlank()) {
                return etag;
            }
            return lastModified;
        }
    }
}
