package ru.mrcrubs.lmsnode.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.mrcrubs.lmsnode.config.NodeProperties;
import ru.mrcrubs.lmsnode.downloader.*;
import ru.mrcrubs.lmsnode.infra.JobRepository;
import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobStatus;
import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.OptionalLong;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final EnumSet<JobStatus> ACTIVE_STATUSES = EnumSet.of(JobStatus.QUEUED, JobStatus.PAUSED, JobStatus.RUNNING);
    private static final long PROGRESS_THROTTLE_MILLIS = 1000L;
    private static final int MESSAGE_MAX_LENGTH = 512;
    private static final int STORAGE_CHILDREN_LIMIT = 20;

    private final JobRepository jobRepository;
    private final Map<UUID, DownloadExecutionContext> executions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastProgressUpdateMillis = new ConcurrentHashMap<>();
    /**
     * Jobs resumed/retried while their previous run was still stopping; started once it exits.
     */
    private final Set<UUID> pendingRestarts = ConcurrentHashMap.newKeySet();
    private final Map<JobType, Downloader> downloaders;
    private final ExecutorService executor;
    private final Path baseDownloadDir;
    private final boolean resumeOnStartup;
    private volatile boolean shuttingDown;

    public JobService(List<Downloader> downloaderList, NodeProperties nodeProperties, JobRepository jobRepository) {
        this.jobRepository = jobRepository;
        this.downloaders = downloaderList.stream().collect(Collectors.toUnmodifiableMap(Downloader::id, Function.identity()));
        this.executor = Executors.newFixedThreadPool(Math.max(1, nodeProperties.getMaxParallel()));
        this.baseDownloadDir = Path.of(nodeProperties.getDownloadDir()).toAbsolutePath().normalize();
        this.resumeOnStartup = nodeProperties.isResumeOnStartup();
    }

    /**
     * Jobs loaded from persisted state that were queued or running when the node stopped
     * are queued again; downloaders continue from their partial files.
     */
    @PostConstruct
    public void restoreInterruptedJobs() {
        List<DownloadJob> interrupted = jobRepository.findAll().stream()
                .filter(job -> job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.QUEUED)
                .sorted(Comparator.comparing(DownloadJob::getCreatedAt))
                .toList();
        for (DownloadJob job : interrupted) {
            synchronized (job) {
                if (resumeOnStartup) {
                    job.requeueAfterRestart("Resumed after node restart");
                } else {
                    job.pause(Instant.now(), "Paused by node restart");
                }
            }
            jobRepository.save(job);
            if (resumeOnStartup) {
                executor.submit(() -> runJob(job));
            }
            log.info("Job restored after restart: id={}, status={}", job.getJobId(), job.getStatus());
        }
    }

    public UUID create(JobType type, String url, String storagePath, boolean startImmediately) {
        String normalizedStoragePath = normalizeStoragePath(storagePath);
        resolveDownloadDir(normalizedStoragePath);
        UUID jobId = UUID.randomUUID();
        DownloadJob job = new DownloadJob(jobId, type, url, normalizedStoragePath, Instant.now());
        if (!startImmediately) {
            job.pause(Instant.now(), "Added without start");
        }
        jobRepository.save(job);
        log.info("Job created: id={}, type={}, url={}, storagePath={}, startImmediately={}", jobId, type, url, job.getStoragePath(), startImmediately);

        if (startImmediately) {
            executor.submit(() -> runJob(job));
        }
        return jobId;
    }

    public Path getBaseDownloadDir() {
        return baseDownloadDir;
    }

    public UUID create(JobType type, String url, String storagePath) {
        return create(type, url, storagePath, true);
    }

    public UUID create(JobType type, String url) {
        return create(type, url, null, true);
    }

    public List<DownloadJob> list(Boolean activeOnly) {
        List<DownloadJob> all = new ArrayList<>(jobRepository.findAll());
        all.sort(Comparator.comparing(DownloadJob::getCreatedAt).reversed());

        if (Boolean.TRUE.equals(activeOnly)) {
            return all.stream().filter(job -> ACTIVE_STATUSES.contains(job.getStatus())).toList();
        }
        return all;
    }

    public DownloadJob get(UUID jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    }

    public DownloadJob cancel(UUID jobId) {
        DownloadJob job = get(jobId);
        boolean canceled;
        synchronized (job) {
            canceled = job.cancel(Instant.now(), "Canceled by request");
        }

        pendingRestarts.remove(jobId);
        DownloadExecutionContext context = executions.get(jobId);
        if (context != null) {
            context.cancel();
        }
        if (canceled) {
            jobRepository.save(job);
            log.info("Job canceled: id={}", jobId);
        }

        return job;
    }

    public DownloadJob pause(UUID jobId) {
        DownloadJob job = get(jobId);
        boolean paused;
        synchronized (job) {
            paused = job.pause(Instant.now(), "Paused by request");
        }
        if (!paused) {
            throw new IllegalStateException("Job cannot be paused in state " + job.getStatus());
        }

        pendingRestarts.remove(jobId);
        DownloadExecutionContext context = executions.get(jobId);
        if (context != null) {
            context.cancel();
        }
        jobRepository.save(job);
        log.info("Job paused: id={}", jobId);
        return job;
    }

    public DownloadJob resume(UUID jobId) {
        DownloadJob job = get(jobId);
        synchronized (job) {
            if (!job.resume(Instant.now(), "Resumed by request")) {
                throw new IllegalStateException("Job cannot be resumed in state " + job.getStatus());
            }
            scheduleRun(job);
        }
        jobRepository.save(job);
        log.info("Job resumed: id={}", jobId);
        return job;
    }

    public DownloadJob retry(UUID jobId) {
        DownloadJob job = get(jobId);
        synchronized (job) {
            if (!job.retry(Instant.now(), "Retried by request")) {
                throw new IllegalStateException("Job cannot be retried in state " + job.getStatus());
            }
            scheduleRun(job);
        }
        jobRepository.save(job);
        log.info("Job retried: id={}", jobId);
        return job;
    }

    /**
     * Starts the job now, or right after its previous run finishes stopping, so a quick
     * pause+resume never runs two downloaders on the same partial file. Caller holds the job lock.
     */
    private void scheduleRun(DownloadJob job) {
        if (executions.containsKey(job.getJobId())) {
            pendingRestarts.add(job.getJobId());
            job.setMessage(sanitizeMessage("Waiting for previous run to stop"));
            return;
        }
        executor.submit(() -> runJob(job));
    }

    public List<StorageTarget> listStorageTargets(Long requiredBytes) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(baseDownloadDir);
        collectSubDirs(baseDownloadDir, candidates);
        collectSubDirs(Path.of("/mnt"), candidates);
        collectSubDirs(Path.of("/media"), candidates);

        List<StorageTarget> targets = new ArrayList<>();
        for (Path candidate : candidates) {
            try {
                Path absolute = candidate.toAbsolutePath().normalize();
                if (!Files.exists(absolute) || !Files.isDirectory(absolute)) {
                    continue;
                }
                long freeBytes = 0L;
                long totalBytes = 0L;
                try {
                    FileStore fileStore = Files.getFileStore(absolute);
                    freeBytes = fileStore.getUsableSpace();
                    totalBytes = fileStore.getTotalSpace();
                } catch (Exception ignored) {
                    // Keep zero values if space cannot be determined.
                }
                boolean writable = Files.isWritable(absolute);
                Boolean canFit = null;
                if (requiredBytes != null && requiredBytes > 0) {
                    canFit = freeBytes >= requiredBytes;
                }
                targets.add(new StorageTarget(absolute, freeBytes, totalBytes, writable, canFit));
            } catch (Exception ignored) {
                // Skip invalid/unreadable candidates.
            }
        }
        return targets;
    }

    public StorageEstimate estimateDownloadSize(JobType type, String url) {
        if (type != JobType.DIRECT) {
            return new StorageEstimate(null, false, "size estimate is only available for DIRECT");
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String headFailureMessage = null;
        try {
            HttpRequest headRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> headResp = client.send(headRequest, HttpResponse.BodyHandlers.discarding());
            if (headResp.statusCode() < 400) {
                OptionalLong contentLength = headResp.headers().firstValueAsLong("Content-Length");
                if (contentLength.isPresent() && contentLength.getAsLong() > 0) {
                    return new StorageEstimate(contentLength.getAsLong(), true, "size from Content-Length");
                }
            } else {
                headFailureMessage = "HEAD returned " + headResp.statusCode();
            }
        } catch (Exception ex) {
            headFailureMessage = "HEAD failed: " + ex.getMessage();
        }

        try {
            HttpRequest rangedRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Range", "bytes=0-0")
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(rangedRequest, HttpResponse.BodyHandlers.discarding());
            String contentRange = resp.headers().firstValue("Content-Range").orElse(null);
            if (contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < contentRange.length()) {
                    long total = Long.parseLong(contentRange.substring(slash + 1).trim());
                    if (total > 0) {
                        return new StorageEstimate(total, true, "size from Content-Range");
                    }
                }
            }
            OptionalLong contentLength = resp.headers().firstValueAsLong("Content-Length");
            if (contentLength.isPresent() && contentLength.getAsLong() > 0) {
                return new StorageEstimate(contentLength.getAsLong(), true, "size from Content-Length");
            }
        } catch (Exception ex) {
            if (headFailureMessage != null) {
                return new StorageEstimate(null, false, headFailureMessage + "; GET failed: " + ex.getMessage());
            }
            return new StorageEstimate(null, false, "size is unknown");
        }

        if (headFailureMessage != null) {
            return new StorageEstimate(null, false, headFailureMessage + "; size is unknown");
        }
        return new StorageEstimate(null, false, "size is unknown");
    }

    public DownloadJob moveJobOutput(UUID jobId, String storagePath) throws Exception {
        DownloadJob job = get(jobId);
        String outputPath = job.getOutputPath();
        if (outputPath == null || outputPath.isBlank()) {
            throw new IllegalStateException("job output file is not available");
        }

        Path source = Path.of(outputPath).toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            throw new IllegalStateException("job output file does not exist");
        }

        Path targetDir = resolveDownloadDir(normalizeStoragePath(storagePath));
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(source.getFileName()).toAbsolutePath().normalize();
        if (source.equals(target)) {
            return job;
        }

        moveFile(source, target);
        synchronized (job) {
            job.setOutputPath(target.toString());
            job.setMessage(sanitizeMessage("File moved to " + target));
        }
        jobRepository.save(job);
        return job;
    }

    /**
     * Path of a job's output (a file, or a directory for multi-file torrents).
     *
     * @throws OutputNotAvailableException when the job has no output on disk
     */
    public Path resolveJobOutput(UUID jobId) {
        DownloadJob job = get(jobId);
        String outputPath = job.getOutputPath();
        if (outputPath == null || outputPath.isBlank()) {
            throw new OutputNotAvailableException("job output is not available");
        }
        Path source = Path.of(outputPath).toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            throw new OutputNotAvailableException("job output does not exist on disk");
        }
        return source;
    }

    public StoredFile uploadToStorage(String storagePath, String fileName, java.io.InputStream inputStream) throws Exception {
        String normalizedStoragePath = normalizeStoragePath(storagePath);
        Path targetDir = resolveDownloadDir(normalizedStoragePath);
        Files.createDirectories(targetDir);

        String safeName = sanitizeFileName(fileName);
        Path target = targetDir.resolve(safeName).toAbsolutePath().normalize();
        long bytes = Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile(target, bytes);
    }

    public DownloadJob deleteJobOutput(UUID jobId) throws Exception {
        DownloadJob job = get(jobId);
        String outputPath = job.getOutputPath();
        if (outputPath == null || outputPath.isBlank()) {
            return job;
        }
        Path source = Path.of(outputPath).toAbsolutePath().normalize();
        if (source.getParent() == null || source.equals(baseDownloadDir) || baseDownloadDir.startsWith(source)) {
            throw new IllegalStateException("refusing to delete " + source);
        }
        deleteRecursively(source);
        synchronized (job) {
            job.setOutputPath(null);
            job.setMessage(sanitizeMessage("Output file removed"));
        }
        jobRepository.save(job);
        return job;
    }

    private void runJob(DownloadJob job) {
        if (job.getStatus() == JobStatus.CANCELED || job.getStatus() == JobStatus.PAUSED) {
            return;
        }

        Downloader downloader = downloaders.get(job.getType());
        if (downloader == null) {
            synchronized (job) {
                if (!job.isTerminal()) {
                    job.start(Instant.now(), sanitizeMessage("Started"));
                    job.fail(Instant.now(), sanitizeMessage("No downloader implementation for type " + job.getType()));
                }
            }
            log.warn("No downloader implementation for job type: id={}, type={}", job.getJobId(), job.getType());
            return;
        }

        DownloadExecutionContext context = new DownloadExecutionContext();
        synchronized (job) {
            if (executions.putIfAbsent(job.getJobId(), context) != null) {
                // Previous run is still stopping; it restarts the job when it exits.
                pendingRestarts.add(job.getJobId());
                return;
            }
            if ((job.getStatus() == JobStatus.CANCELED || job.getStatus() == JobStatus.PAUSED)
                    || !job.start(Instant.now(), sanitizeMessage("Started"))) {
                executions.remove(job.getJobId());
                return;
            }
        }
        jobRepository.save(job);
        log.info("Job started: id={}, type={}", job.getJobId(), job.getType());

        try {
            Path jobDownloadDir = resolveDownloadDir(job.getStoragePath());
            Files.createDirectories(jobDownloadDir);
            DownloadRequest request = new DownloadRequest(job.getJobId(), job.getType(), job.getUrl(), jobDownloadDir);
            DownloadResult result = downloader.download(request, context, update -> applyProgress(job, context, update));

            synchronized (job) {
                if (isCurrentExecution(job, context) && job.complete(Instant.now(), sanitizeMessage(result.message()), result.outputPath())) {
                    pendingRestarts.remove(job.getJobId());
                    if (result.outputPath() != null && !result.outputPath().isBlank()) {
                        try {
                            long outputSize = sizeOf(Path.of(result.outputPath()));
                            job.setOutputSizeBytes(outputSize);
                            // Tools report rounded sizes (aria2c "16MiB"); the file on disk is exact.
                            job.setTotalBytes(outputSize);
                        } catch (Exception ignored) {
                            // Leave size unknown if file metadata cannot be read.
                        }
                    }
                }
            }
            log.info("Job completed: id={}, outputPath={}", job.getJobId(), job.getOutputPath());
        } catch (CancellationException ex) {
            synchronized (job) {
                // Pause/cancel already set the status; only a stop nobody asked for
                // (node shutdown) leaves the job RUNNING here.
                if (isCurrentExecution(job, context) && job.getStatus() == JobStatus.RUNNING) {
                    job.cancel(Instant.now(), sanitizeMessage("Canceled"));
                }
            }
            log.info("Job stopped during execution: id={}, status={}", job.getJobId(), job.getStatus());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            boolean treatAsCanceled = context.isCanceled() || ex instanceof InterruptedException;
            synchronized (job) {
                if (isCurrentExecution(job, context) && job.getStatus() == JobStatus.RUNNING) {
                    if (treatAsCanceled) {
                        job.cancel(Instant.now(), sanitizeMessage("Canceled"));
                    } else {
                        job.fail(Instant.now(), sanitizeMessage(ex.getMessage()));
                    }
                }
            }
            if (treatAsCanceled) {
                log.info("Job stopped during execution: id={}, status={}", job.getJobId(), job.getStatus());
            } else {
                log.warn("Job failed: id={}, message={}", job.getJobId(), ex.getMessage());
            }
        } finally {
            boolean restart;
            synchronized (job) {
                if (executions.remove(job.getJobId(), context)) {
                    lastProgressUpdateMillis.remove(job.getJobId());
                }
                restart = pendingRestarts.remove(job.getJobId()) && job.getStatus() == JobStatus.QUEUED;
            }
            // A stop caused by shutdown is not persisted: the job stays RUNNING on disk and continues after restart.
            if (!shuttingDown || job.getStatus() != JobStatus.CANCELED) {
                jobRepository.save(job);
            }
            if (restart && !shuttingDown) {
                executor.submit(() -> runJob(job));
            }
        }
    }

    private void applyProgress(DownloadJob job, DownloadExecutionContext context, ProgressUpdate update) {
        synchronized (job) {
            if (!isCurrentExecution(job, context)) {
                return;
            }
            if (job.getStatus() != JobStatus.RUNNING) {
                return;
            }
            long now = System.currentTimeMillis();
            Long lastUpdate = lastProgressUpdateMillis.get(job.getJobId());
            if (lastUpdate != null && now - lastUpdate < PROGRESS_THROTTLE_MILLIS) {
                return;
            }
            lastProgressUpdateMillis.put(job.getJobId(), now);
            if (update.percent() != null) {
                job.setPercent(update.percent());
            }
            if (update.totalBytes() != null && update.totalBytes() > 0) {
                job.setTotalBytes(update.totalBytes());
            }
            if (update.speedBytes() != null) {
                job.setSpeedBytes(update.speedBytes());
            }
            if (update.etaSeconds() != null) {
                job.setEtaSeconds(update.etaSeconds());
            }
            if (update.message() != null && !update.message().isBlank()) {
                job.setMessage(sanitizeMessage(update.message()));
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("JobService shutdown started");
        // Jobs interrupted by shutdown keep their persisted QUEUED/RUNNING state and continue after restart.
        shuttingDown = true;
        executions.values().forEach(DownloadExecutionContext::cancel);
        executor.shutdownNow();
        try {
            // Give external tools time to save their resume state after SIGTERM.
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate within timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for executor shutdown");
        }
        log.info("JobService shutdown finished");
    }

    private String sanitizeMessage(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() <= MESSAGE_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MESSAGE_MAX_LENGTH);
    }

    private String normalizeStoragePath(String storagePath) {
        if (storagePath == null) {
            return null;
        }
        String normalized = storagePath.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private Path resolveDownloadDir(String storagePath) {
        if (storagePath == null) {
            return baseDownloadDir;
        }

        Path raw = Path.of(storagePath);
        if (raw.isAbsolute()) {
            return raw.toAbsolutePath().normalize();
        }
        Path resolved = baseDownloadDir.resolve(raw).normalize();
        if (!resolved.startsWith(baseDownloadDir)) {
            throw new IllegalArgumentException("relative storagePath must stay inside configured download dir");
        }
        return resolved;
    }

    private boolean isCurrentExecution(DownloadJob job, DownloadExecutionContext context) {
        return executions.get(job.getJobId()) == context;
    }

    private void collectSubDirs(Path root, LinkedHashSet<Path> out) {
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    // Hidden dirs hold node state (.lms-node), not download targets.
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .limit(STORAGE_CHILDREN_LIMIT)
                    .forEach(path -> out.add(path.toAbsolutePath().normalize()));
        } catch (Exception ignored) {
            // ignore missing permissions
        }
    }

    private void moveFile(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            if (Files.isDirectory(source)) {
                copyRecursively(source, target);
                deleteRecursively(source);
            } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(source);
            }
        }
    }

    private void copyRecursively(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        if (!Files.isDirectory(path)) {
            Files.delete(path);
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private long sizeOf(Path path) throws Exception {
        if (!Files.isDirectory(path)) {
            return Files.size(path);
        }
        try (var paths = Files.walk(path)) {
            long total = 0L;
            for (Path entry : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(entry)) {
                    total += Files.size(entry);
                }
            }
            return total;
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return UUID.randomUUID() + ".bin";
        }
        String normalized = name.replace('\\', '/');
        int slashIdx = normalized.lastIndexOf('/');
        if (slashIdx >= 0) {
            normalized = normalized.substring(slashIdx + 1);
        }
        normalized = normalized.trim();
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)) {
            return UUID.randomUUID() + ".bin";
        }
        return normalized;
    }
}
