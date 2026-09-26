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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DirectHttpDownloader implements Downloader {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    private static final Duration DEFAULT_STALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);
    private static final String META_URL = "url";
    private static final String META_FILE_NAME = "fileName";
    private static final String META_ETAG = "etag";
    private static final String META_LAST_MODIFIED = "lastModified";
    private static final String META_TOTAL_BYTES = "totalBytes";

    private final int maxAttempts;
    private final Duration stallTimeout;
    private final Duration retryBaseDelay;

    public DirectHttpDownloader() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_STALL_TIMEOUT, DEFAULT_RETRY_BASE_DELAY);
    }

    DirectHttpDownloader(int maxAttempts, Duration stallTimeout, Duration retryBaseDelay) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.stallTimeout = stallTimeout;
        this.retryBaseDelay = retryBaseDelay;
    }

    @Override
    public JobType id() {
        return JobType.DIRECT;
    }

    /**
     * Downloads into {@code <jobId>.part} next to a small metadata file, so a paused,
     * failed or interrupted job continues with a Range request from where it stopped.
     * Network errors and stalled connections are retried within the same run with
     * exponential backoff; each retry resumes from the bytes already on disk.
     */
    @Override
    public DownloadResult download(DownloadRequest request,
                                   DownloadExecutionContext context,
                                   java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        boolean everResumed = false;
        for (int attempt = 1; ; attempt++) {
            try {
                AttemptResult result = attempt(client, request, context, progressConsumer);
                everResumed |= result.resumed();
                return new DownloadResult(result.outputFile().toString(), everResumed ? "Completed (resumed)" : "Completed");
            } catch (RetryableDownloadException ex) {
                everResumed |= ex.resumed;
                if (context.isCanceled()) {
                    throw new CancellationException("Download canceled");
                }
                if (attempt >= maxAttempts) {
                    throw new IllegalStateException(ex.getMessage() + " (after " + attempt + " attempts)", ex.getCause());
                }
                long delayMillis = Math.min(retryBaseDelay.toMillis() << (attempt - 1), MAX_RETRY_DELAY.toMillis());
                progressConsumer.accept(new ProgressUpdate(null, null, 0L, null,
                        ex.getMessage() + "; reconnecting in " + Math.max(1, delayMillis / 1000) + "s (attempt "
                                + (attempt + 1) + "/" + maxAttempts + ")"));
                sleepUnlessCanceled(context, delayMillis);
            }
        }
    }

    private AttemptResult attempt(HttpClient client,
                                  DownloadRequest request,
                                  DownloadExecutionContext context,
                                  java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
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
        try {
            if (existingBytes > 0) {
                response = client.send(buildGetRequest(request.url(), "bytes=" + existingBytes + "-", metadata),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 206 && isValidResumeResponse(response, existingBytes, metadata)) {
                    resumed = true;
                } else if (response.statusCode() == 416 && isAlreadyComplete(response, existingBytes, metadata)) {
                    closeQuietly(response.body());
                    Path outputFile = request.downloadDir().resolve(metadata.fileName());
                    movePartialToFinal(partialFile, outputFile);
                    Files.deleteIfExists(metadataFile);
                    progressConsumer.accept(new ProgressUpdate(100.0, existingBytes, null, 0L, "HTTP download finished"));
                    return new AttemptResult(outputFile, true);
                } else if (isRetryableStatus(response.statusCode())) {
                    closeQuietly(response.body());
                    throw new RetryableDownloadException("HTTP error: " + response.statusCode(), null, false);
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
        } catch (IOException ex) {
            throw new RetryableDownloadException("Connection failed: " + describe(ex), ex, false);
        }

        if (response.statusCode() >= 400) {
            closeQuietly(response.body());
            if (isRetryableStatus(response.statusCode())) {
                throw new RetryableDownloadException("HTTP error: " + response.statusCode(), null, resumed);
            }
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

        if (resumed) {
            progressConsumer.accept(new ProgressUpdate(
                    expectedTotalBytes > 0 ? (downloadedBytes * 100.0) / expectedTotalBytes : null,
                    expectedTotalBytes > 0 ? expectedTotalBytes : null,
                    null, null, "Resuming HTTP download at byte " + existingBytes));
        }

        AtomicLong lastReadAt = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean(false);
        Thread watchdog = startStallWatchdog(lastReadAt, stalled, finalResponse.body());
        try (InputStream inputStream = response.body();
             OutputStream outputStream = Files.newOutputStream(
                     partialFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING
             )) {
            activeInput.set(inputStream);
            activeOutput.set(outputStream);

            Throttle throttle = new Throttle(context);
            int read;
            while ((read = inputStream.read(buffer, 0, throttle.chunkSize(buffer.length))) != -1) {
                lastReadAt.set(System.nanoTime());
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
                throttle.onBytes(read);
                // A throttle pause is not a stalled connection.
                lastReadAt.set(System.nanoTime());
            }
        } catch (IOException ex) {
            if (context.isCanceled()) {
                throw new CancellationException("Download canceled");
            }
            String reason = stalled.get()
                    ? "No data received for " + stallTimeout.toSeconds() + "s"
                    : "Connection lost: " + describe(ex);
            throw new RetryableDownloadException(reason, ex, resumed);
        } finally {
            watchdog.interrupt();
            activeInput.set(null);
            activeOutput.set(null);
        }

        if (context.isCanceled()) {
            throw new CancellationException("Download canceled");
        }
        if (expectedTotalBytes > 0 && downloadedBytes < expectedTotalBytes) {
            // The server closed the stream early; keep the partial file and continue with a Range request.
            throw new RetryableDownloadException("Connection closed at " + downloadedBytes + " of "
                    + expectedTotalBytes + " bytes", null, resumed);
        }

        movePartialToFinal(partialFile, outputFile);
        Files.deleteIfExists(metadataFile);
        progressConsumer.accept(new ProgressUpdate(100.0, Files.size(outputFile), null, 0L, "HTTP download finished"));
        return new AttemptResult(outputFile, resumed);
    }

    private Thread startStallWatchdog(AtomicLong lastReadAt, AtomicBoolean stalled, InputStream body) {
        long stallNanos = stallTimeout.toNanos();
        return Thread.ofVirtual().name("direct-download-watchdog").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(Math.max(50, Math.min(1000, stallTimeout.toMillis() / 4)));
                    if (System.nanoTime() - lastReadAt.get() > stallNanos) {
                        stalled.set(true);
                        closeQuietly(body);
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
                // Download finished or failed; nothing to watch.
            }
        });
    }

    private void sleepUnlessCanceled(DownloadExecutionContext context, long delayMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + delayMillis;
        while (System.currentTimeMillis() < deadline) {
            if (context.isCanceled()) {
                throw new CancellationException("Download canceled");
            }
            Thread.sleep(Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
        }
        if (context.isCanceled()) {
            throw new CancellationException("Download canceled");
        }
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private boolean isAlreadyComplete(HttpResponse<?> response, long existingBytes, PartialDownloadMetadata metadata) {
        if (metadata.totalBytes() == null || metadata.totalBytes() != existingBytes) {
            return false;
        }
        String contentRange = response.headers().firstValue("Content-Range").orElse(null);
        if (contentRange == null) {
            return true;
        }
        int slash = contentRange.lastIndexOf('/');
        try {
            return slash >= 0 && Long.parseLong(contentRange.substring(slash + 1).trim()) == existingBytes;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private String describe(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private boolean isResumeCandidate(DownloadRequest request, long existingBytes, PartialDownloadMetadata metadata) {
        // Without ETag/Last-Modified the known total size is the only check that the file did not change.
        return existingBytes > 0
                && metadata != null
                && request.url().equals(metadata.url())
                && metadata.fileName() != null
                && !metadata.fileName().isBlank()
                && (metadata.hasValidator() || metadata.totalBytes() != null)
                && (metadata.totalBytes() == null || existingBytes <= metadata.totalBytes());
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

    record AttemptResult(Path outputFile, boolean resumed) {
    }

    static final class RetryableDownloadException extends Exception {
        private final boolean resumed;

        RetryableDownloadException(String message, Throwable cause, boolean resumed) {
            super(message, cause);
            this.resumed = resumed;
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
