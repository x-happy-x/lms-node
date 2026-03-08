package ru.mrcrubs.lmsnode.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import ru.mrcrubs.lmsnode.config.NodeProperties;
import ru.mrcrubs.lmsnode.downloader.*;
import ru.mrcrubs.lmsnode.infra.JobRepository;
import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobStatus;
import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobService {
    private static final EnumSet<JobStatus> ACTIVE_STATUSES = EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING);
    private static final long PROGRESS_THROTTLE_MILLIS = 1000L;

    private final JobRepository jobRepository;
    private final Map<UUID, DownloadExecutionContext> executions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastProgressUpdateMillis = new ConcurrentHashMap<>();
    private final Map<JobType, Downloader> downloaders;
    private final ExecutorService executor;
    private final Path downloadDir;

    public JobService(List<Downloader> downloaderList, NodeProperties nodeProperties, JobRepository jobRepository) {
        this.jobRepository = jobRepository;
        this.downloaders = downloaderList.stream().collect(Collectors.toUnmodifiableMap(Downloader::id, Function.identity()));
        this.executor = Executors.newFixedThreadPool(Math.max(1, nodeProperties.getMaxParallel()));
        this.downloadDir = Path.of(nodeProperties.getDownloadDir());
    }

    public UUID create(JobType type, String url) {
        UUID jobId = UUID.randomUUID();
        DownloadJob job = new DownloadJob(jobId, type, url, Instant.now());
        jobRepository.save(job);

        executor.submit(() -> runJob(job));
        return jobId;
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
        synchronized (job) {
            job.cancel(Instant.now(), "Canceled by request");
        }

        DownloadExecutionContext context = executions.get(jobId);
        if (context != null) {
            context.cancel();
        }

        return job;
    }

    private void runJob(DownloadJob job) {
        if (job.getStatus() == JobStatus.CANCELED) {
            return;
        }

        Downloader downloader = downloaders.get(job.getType());
        if (downloader == null) {
            synchronized (job) {
                if (!job.isTerminal()) {
                    job.start(Instant.now(), "Started");
                    job.fail(Instant.now(), "No downloader implementation for type " + job.getType());
                }
            }
            return;
        }

        DownloadExecutionContext context = new DownloadExecutionContext();
        executions.put(job.getJobId(), context);

        synchronized (job) {
            if (job.getStatus() == JobStatus.CANCELED || !job.start(Instant.now(), "Started")) {
                executions.remove(job.getJobId());
                return;
            }
        }

        try {
            Files.createDirectories(downloadDir);
            DownloadRequest request = new DownloadRequest(job.getJobId(), job.getType(), job.getUrl(), downloadDir);
            DownloadResult result = downloader.download(request, context, update -> applyProgress(job, update));

            synchronized (job) {
                if (job.getStatus() != JobStatus.CANCELED) {
                    job.complete(Instant.now(), result.message(), result.outputPath());
                }
            }
        } catch (CancellationException ex) {
            synchronized (job) {
                if (job.getStatus() != JobStatus.CANCELED) {
                    job.cancel(Instant.now(), "Canceled");
                }
            }
        } catch (Exception ex) {
            synchronized (job) {
                if (job.getStatus() != JobStatus.CANCELED) {
                    job.fail(Instant.now(), ex.getMessage());
                }
            }
        } finally {
            executions.remove(job.getJobId());
            lastProgressUpdateMillis.remove(job.getJobId());
        }
    }

    private void applyProgress(DownloadJob job, ProgressUpdate update) {
        synchronized (job) {
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
            if (update.speedBytes() != null) {
                job.setSpeedBytes(update.speedBytes());
            }
            if (update.etaSeconds() != null) {
                job.setEtaSeconds(update.etaSeconds());
            }
            if (update.message() != null && !update.message().isBlank()) {
                job.setMessage(update.message());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
