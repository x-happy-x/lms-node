package ru.mrcrubs.lmsnode.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.mrcrubs.lmsnode.config.NodeProperties;
import ru.mrcrubs.lmsnode.downloader.DownloadExecutionContext;
import ru.mrcrubs.lmsnode.downloader.DownloadRequest;
import ru.mrcrubs.lmsnode.downloader.DownloadResult;
import ru.mrcrubs.lmsnode.downloader.Downloader;
import ru.mrcrubs.lmsnode.downloader.ProgressUpdate;
import ru.mrcrubs.lmsnode.infra.InMemoryJobRepository;
import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobStatus;
import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class JobServiceTest {

    private JobService jobService;

    @AfterEach
    void tearDown() {
        if (jobService != null) {
            jobService.shutdown();
        }
    }

    @Test
    void createShouldRunJobAndExposeDoneState() throws Exception {
        jobService = new JobService(List.of(new ImmediateDownloader()), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.DIRECT, "https://example.com/file.bin");

        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.DONE, 3000), "Job should finish");

        DownloadJob job = jobService.get(jobId);
        assertEquals(JobStatus.DONE, job.getStatus());
        assertEquals(100.0, job.getPercent());
        assertNotNull(job.getStartedAt());
        assertNotNull(job.getFinishedAt());
        assertNotNull(job.getOutputPath());

        List<DownloadJob> activeJobs = jobService.list(true);
        assertTrue(activeJobs.isEmpty(), "No active jobs expected after completion");
    }

    @Test
    void cancelShouldStopRunningJobAndSetCanceledStatus() {
        jobService = new JobService(List.of(new BlockingDownloader()), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.YTDLP, "https://example.com/video");

        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.RUNNING, 3000), "Job should start running");

        DownloadJob canceled = jobService.cancel(jobId);
        assertEquals(JobStatus.CANCELED, canceled.getStatus());

        assertTrue(waitUntil(() -> jobService.get(jobId).getFinishedAt() != null, 3000), "Canceled job should be finalized");
        assertEquals(JobStatus.CANCELED, jobService.get(jobId).getStatus());
        assertEquals("Canceled by request", jobService.get(jobId).getMessage());
    }

    @Test
    void listActiveShouldIncludeQueuedAndRunningOnly() {
        jobService = new JobService(
                List.of(new BlockingDownloader(), new ImmediateDownloader(JobType.DIRECT)),
                nodeProperties(),
                new InMemoryJobRepository()
        );

        UUID runningJobId = jobService.create(JobType.YTDLP, "https://example.com/long");
        UUID queuedJobId = jobService.create(JobType.DIRECT, "https://example.com/queued");

        assertTrue(waitUntil(() -> jobService.get(runningJobId).getStatus() == JobStatus.RUNNING, 3000), "First job should be running");
        assertEquals(JobStatus.QUEUED, jobService.get(queuedJobId).getStatus());

        List<DownloadJob> active = jobService.list(true);
        assertEquals(2, active.size());
        assertTrue(active.stream().allMatch(j -> j.getStatus() == JobStatus.RUNNING || j.getStatus() == JobStatus.QUEUED));
    }

    @Test
    void cancelQueuedJobShouldPreventDownloaderExecution() {
        BlockingDownloader first = new BlockingDownloader();
        RecordingDownloader second = new RecordingDownloader(JobType.DIRECT);
        jobService = new JobService(List.of(first, second), nodeProperties(), new InMemoryJobRepository());

        UUID firstJobId = jobService.create(JobType.YTDLP, "https://example.com/long");
        UUID secondJobId = jobService.create(JobType.DIRECT, "https://example.com/queued");

        assertTrue(waitUntil(() -> jobService.get(firstJobId).getStatus() == JobStatus.RUNNING, 3000), "First job should run");
        assertEquals(JobStatus.QUEUED, jobService.get(secondJobId).getStatus());

        jobService.cancel(secondJobId);
        assertEquals(JobStatus.CANCELED, jobService.get(secondJobId).getStatus());

        jobService.cancel(firstJobId);
        assertTrue(waitUntil(() -> jobService.get(firstJobId).getStatus() == JobStatus.CANCELED, 3000), "First job should cancel");

        // Give worker thread enough time to pick queued task and skip execution due canceled status.
        assertTrue(waitUntil(() -> second.invocations() == 0, 500), "Queued canceled job must not start downloader");
        assertEquals(0, second.invocations());
    }

    @Test
    void queuedJobShouldStartAfterRunningJobFinishes() {
        BlockingDownloader first = new BlockingDownloader();
        RecordingDownloader second = new RecordingDownloader(JobType.DIRECT);
        jobService = new JobService(List.of(first, second), nodeProperties(), new InMemoryJobRepository());

        UUID firstJobId = jobService.create(JobType.YTDLP, "https://example.com/long");
        UUID secondJobId = jobService.create(JobType.DIRECT, "https://example.com/next");

        assertTrue(waitUntil(() -> jobService.get(firstJobId).getStatus() == JobStatus.RUNNING, 3000), "First job should run");
        assertEquals(JobStatus.QUEUED, jobService.get(secondJobId).getStatus());

        jobService.cancel(firstJobId);
        assertTrue(waitUntil(() -> jobService.get(firstJobId).getStatus() == JobStatus.CANCELED, 3000), "First job should cancel");
        assertTrue(waitUntil(() -> jobService.get(secondJobId).getStatus() == JobStatus.DONE, 3000), "Second job should execute after first");
        assertEquals(1, second.invocations());
    }

    @Test
    void missingDownloaderShouldMoveJobToError() {
        jobService = new JobService(List.of(new ImmediateDownloader(JobType.DIRECT)), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.ARIA2C, "https://example.com/archive");

        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.ERROR, 3000), "Job should fail without downloader");
        DownloadJob job = jobService.get(jobId);
        assertNotNull(job.getFinishedAt());
        assertTrue(job.getMessage().contains("No downloader implementation"));
    }

    private NodeProperties nodeProperties() {
        NodeProperties properties = new NodeProperties();
        properties.setMaxParallel(1);
        properties.setDownloadDir("./target/test-downloads");
        return properties;
    }

    private boolean waitUntil(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static final class ImmediateDownloader implements Downloader {
        private final JobType type;

        private ImmediateDownloader() {
            this(JobType.DIRECT);
        }

        private ImmediateDownloader(JobType type) {
            this.type = type;
        }

        @Override
        public JobType id() {
            return type;
        }

        @Override
        public DownloadResult download(DownloadRequest request,
                                       DownloadExecutionContext context,
                                       java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
            Path output = request.downloadDir().resolve(request.jobId() + ".bin");
            Files.createDirectories(output.getParent());
            Files.writeString(output, "ok");
            progressConsumer.accept(new ProgressUpdate(50.0, 1000L, 1L, "half"));
            return new DownloadResult(output.toString(), "done");
        }
    }

    private static final class BlockingDownloader implements Downloader {
        @Override
        public JobType id() {
            return JobType.YTDLP;
        }

        @Override
        public DownloadResult download(DownloadRequest request,
                                       DownloadExecutionContext context,
                                       java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
            while (!context.isCanceled()) {
                Thread.sleep(20);
            }
            throw new CancellationException("canceled");
        }
    }

    private static final class RecordingDownloader implements Downloader {
        private final JobType type;
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicBoolean started = new AtomicBoolean();

        private RecordingDownloader(JobType type) {
            this.type = type;
        }

        @Override
        public JobType id() {
            return type;
        }

        @Override
        public DownloadResult download(DownloadRequest request,
                                       DownloadExecutionContext context,
                                       java.util.function.Consumer<ProgressUpdate> progressConsumer) {
            started.set(true);
            invocations.incrementAndGet();
            progressConsumer.accept(new ProgressUpdate(90.0, 42L, 1L, "almost done"));
            return new DownloadResult(request.downloadDir().resolve(request.jobId() + ".bin").toString(), "done");
        }

        private int invocations() {
            return invocations.get();
        }

        @SuppressWarnings("unused")
        private boolean started() {
            return started.get();
        }
    }
}
