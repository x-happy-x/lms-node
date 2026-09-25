package ru.mrcrubs.lmsnode.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.mrcrubs.lmsnode.config.NodeProperties;
import ru.mrcrubs.lmsnode.downloader.DownloadExecutionContext;
import ru.mrcrubs.lmsnode.downloader.DownloadRequest;
import ru.mrcrubs.lmsnode.downloader.DownloadResult;
import ru.mrcrubs.lmsnode.downloader.Downloader;
import ru.mrcrubs.lmsnode.downloader.ProgressUpdate;
import org.junit.jupiter.api.io.TempDir;
import ru.mrcrubs.lmsnode.infra.FileJobRepository;
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
    void pauseAndResumeShouldContinueJobLifecycle() {
        jobService = new JobService(List.of(new BlockingDownloader()), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.YTDLP, "https://example.com/video");
        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.RUNNING, 3000), "Job should start running");

        DownloadJob paused = jobService.pause(jobId);
        assertEquals(JobStatus.PAUSED, paused.getStatus());
        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.PAUSED, 3000), "Job should become paused");

        assertTrue(waitUntil(() -> {
            try {
                jobService.resume(jobId);
                return true;
            } catch (IllegalStateException ex) {
                return false;
            }
        }, 3000), "Job should become resumable after pause stop");
        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.RUNNING, 3000), "Job should run again");

        jobService.cancel(jobId);
        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.CANCELED, 3000), "Job should cancel");
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

    @Test
    void createWithoutImmediateStartShouldKeepJobPausedUntilResume() {
        RecordingDownloader downloader = new RecordingDownloader(JobType.DIRECT);
        jobService = new JobService(List.of(downloader), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.DIRECT, "https://example.com/file.bin", null, false);

        DownloadJob job = jobService.get(jobId);
        assertEquals(JobStatus.PAUSED, job.getStatus());
        assertNull(job.getStartedAt());
        assertEquals("Added without start", job.getMessage());
        assertEquals(0, downloader.invocations());

        jobService.resume(jobId);

        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.DONE, 3000), "Paused job should run after resume");
        assertEquals(1, downloader.invocations());
    }

    @Test
    void createShouldUseCustomStoragePathInsideBaseDir() throws Exception {
        jobService = new JobService(List.of(new ImmediateDownloader()), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.DIRECT, "https://example.com/file.bin", "movies/2026");

        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.DONE, 3000), "Job should finish");
        DownloadJob job = jobService.get(jobId);
        assertEquals("movies/2026", job.getStoragePath());
        assertNotNull(job.getOutputPath());
        assertTrue(job.getOutputPath().replace('\\', '/').contains("/movies/2026/"), "Output path should include custom dir");
    }

    @Test
    void createShouldRejectStoragePathOutsideBaseDir() {
        jobService = new JobService(List.of(new ImmediateDownloader()), nodeProperties(), new InMemoryJobRepository());

        assertThrows(IllegalArgumentException.class, () ->
                jobService.create(JobType.DIRECT, "https://example.com/file.bin", "../escape"));
    }

    @Test
    void shutdownShouldCancelRunningExecution() {
        ObservingBlockingDownloader blocking = new ObservingBlockingDownloader();
        jobService = new JobService(List.of(blocking), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.YTDLP, "https://example.com/long");
        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.RUNNING, 3000), "Job should start");

        jobService.shutdown();

        assertTrue(waitUntil(blocking::cancelObserved, 2000), "Running downloader should observe cancellation");
        assertEquals(JobStatus.CANCELED, jobService.get(jobId).getStatus());
    }

    @Test
    void resumeRightAfterPauseShouldWaitForPreviousRunToStop() {
        SlowStoppingDownloader downloader = new SlowStoppingDownloader();
        jobService = new JobService(List.of(downloader), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.YTDLP, "https://example.com/video");
        assertTrue(waitUntil(() -> downloader.invocations.get() == 1, 3000), "Job should start running");

        jobService.pause(jobId);
        DownloadJob resumed = assertDoesNotThrow(() -> jobService.resume(jobId));
        assertEquals(JobStatus.QUEUED, resumed.getStatus());

        assertTrue(waitUntil(() -> downloader.invocations.get() == 2
                && jobService.get(jobId).getStatus() == JobStatus.RUNNING, 3000), "Job should run again");
        assertEquals(1, downloader.maxConcurrent.get(), "Runs of one job must not overlap");

        jobService.cancel(jobId);
        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.CANCELED, 3000));
    }

    @Test
    void pauseAndResumeShouldNotCancelJobWhenStopIsSlow() {
        SlowStoppingDownloader downloader = new SlowStoppingDownloader();
        jobService = new JobService(List.of(downloader), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.YTDLP, "https://example.com/video");
        assertTrue(waitUntil(() -> downloader.invocations.get() == 1, 3000));

        jobService.pause(jobId);
        jobService.resume(jobId);
        jobService.pause(jobId);

        assertTrue(waitUntil(() -> downloader.active.get() == 0, 3000));
        sleep(200);
        assertEquals(JobStatus.PAUSED, jobService.get(jobId).getStatus());
        assertEquals(1, downloader.invocations.get(), "Pending restart must be dropped by the second pause");
    }

    @Test
    void restoreShouldContinueJobsInterruptedByRestart(@TempDir Path tempDir) {
        Path stateFile = tempDir.resolve("jobs.json");
        FileJobRepository before = new FileJobRepository(stateFile);
        DownloadJob running = DownloadJob.restore(UUID.randomUUID(), JobType.DIRECT, "https://example.com/a", null,
                JobStatus.RUNNING, java.time.Instant.now());
        running.setPercent(40.0);
        DownloadJob paused = DownloadJob.restore(UUID.randomUUID(), JobType.DIRECT, "https://example.com/b", null,
                JobStatus.PAUSED, java.time.Instant.now());
        before.save(running);
        before.save(paused);

        RecordingDownloader downloader = new RecordingDownloader(JobType.DIRECT);
        FileJobRepository after = new FileJobRepository(stateFile);
        jobService = new JobService(List.of(downloader), nodeProperties(), after);
        jobService.restoreInterruptedJobs();

        assertTrue(waitUntil(() -> jobService.get(running.getJobId()).getStatus() == JobStatus.DONE, 3000));
        assertEquals(1, downloader.invocations());
        assertEquals(JobStatus.PAUSED, jobService.get(paused.getJobId()).getStatus());
        assertEquals(JobStatus.DONE, new FileJobRepository(stateFile).findById(running.getJobId()).orElseThrow().getStatus());
    }

    @Test
    void restoreShouldKeepJobsPausedWhenAutoResumeIsDisabled(@TempDir Path tempDir) {
        Path stateFile = tempDir.resolve("jobs.json");
        DownloadJob running = DownloadJob.restore(UUID.randomUUID(), JobType.DIRECT, "https://example.com/a", null,
                JobStatus.RUNNING, java.time.Instant.now());
        new FileJobRepository(stateFile).save(running);

        RecordingDownloader downloader = new RecordingDownloader(JobType.DIRECT);
        NodeProperties properties = nodeProperties();
        properties.setResumeOnStartup(false);
        jobService = new JobService(List.of(downloader), properties, new FileJobRepository(stateFile));
        jobService.restoreInterruptedJobs();

        assertEquals(JobStatus.PAUSED, jobService.get(running.getJobId()).getStatus());
        sleep(100);
        assertEquals(0, downloader.invocations());
    }

    @Test
    void moveAndDeleteShouldHandleDirectoryOutput() throws Exception {
        DirectoryDownloader downloader = new DirectoryDownloader();
        jobService = new JobService(List.of(downloader), nodeProperties(), new InMemoryJobRepository());

        UUID jobId = jobService.create(JobType.TORRENT, "magnet:?xt=urn:btih:abc");
        assertTrue(waitUntil(() -> jobService.get(jobId).getStatus() == JobStatus.DONE, 3000));
        assertEquals(4L, jobService.get(jobId).getOutputSizeBytes());

        String subDir = "moved-" + jobId;
        DownloadJob moved = jobService.moveJobOutput(jobId, subDir);
        Path movedPath = Path.of(moved.getOutputPath());
        assertTrue(Files.isDirectory(movedPath));
        assertTrue(Files.exists(movedPath.resolve("a.txt")));

        jobService.deleteJobOutput(jobId);
        assertFalse(Files.exists(movedPath));
        Files.deleteIfExists(movedPath.getParent());
    }

    @Test
    void speedLimitIsLiveForDirectAndRestartsToolJobs(@TempDir Path tempDir) {
        SlowStoppingDownloader tool = new SlowStoppingDownloader();
        LimitObservingDownloader direct = new LimitObservingDownloader();
        FileJobRepository repository = new FileJobRepository(tempDir.resolve("jobs.json"));
        NodeProperties properties = nodeProperties();
        properties.setMaxParallel(2);
        jobService = new JobService(List.of(tool, direct), properties, repository);

        UUID directId = jobService.create(JobType.DIRECT, "https://example.com/a.bin", null, true, 1_000_000L);
        assertTrue(waitUntil(() -> direct.lastLimit.get() == 1_000_000L, 3000), "initial limit reaches the downloader");
        jobService.setSpeedLimit(directId, 250_000L);
        assertTrue(waitUntil(() -> direct.lastLimit.get() == 250_000L, 3000), "running HTTP download follows the change");
        assertEquals(1, direct.invocations.get(), "HTTP download is not restarted");

        UUID toolId = jobService.create(JobType.YTDLP, "https://example.com/video");
        assertTrue(waitUntil(() -> tool.invocations.get() == 1, 3000));
        jobService.setSpeedLimit(toolId, 500_000L);
        assertTrue(waitUntil(() -> tool.invocations.get() == 2 && jobService.get(toolId).getStatus() == JobStatus.RUNNING, 3000),
                "tool job restarts to pick up the limit");
        assertEquals(1, tool.maxConcurrent.get());

        jobService.setSpeedLimit(toolId, 0L);
        assertNull(jobService.get(toolId).getMaxSpeedBytes(), "0 removes the limit");
        assertEquals(250_000L, new FileJobRepository(tempDir.resolve("jobs.json")).findById(directId).orElseThrow().getMaxSpeedBytes());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
            progressConsumer.accept(new ProgressUpdate(50.0, null, 1000L, 1L, "half"));
            return new DownloadResult(output.toString(), "done");
        }
    }

    /**
     * Like an external tool saving its state: exits 300ms after being asked to stop.
     */
    private static final class SlowStoppingDownloader implements Downloader {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();

        @Override
        public JobType id() {
            return JobType.YTDLP;
        }

        @Override
        public DownloadResult download(DownloadRequest request,
                                       DownloadExecutionContext context,
                                       java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
            invocations.incrementAndGet();
            maxConcurrent.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                while (!context.isCanceled()) {
                    Thread.sleep(10);
                }
                Thread.sleep(300);
                throw new CancellationException("stopped");
            } finally {
                active.decrementAndGet();
            }
        }
    }

    /** Reports the context's live speed limit until canceled. */
    private static final class LimitObservingDownloader implements Downloader {
        private final AtomicInteger invocations = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicLong lastLimit = new java.util.concurrent.atomic.AtomicLong(-1);

        @Override
        public JobType id() {
            return JobType.DIRECT;
        }

        @Override
        public DownloadResult download(DownloadRequest request,
                                       DownloadExecutionContext context,
                                       java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
            invocations.incrementAndGet();
            while (!context.isCanceled()) {
                lastLimit.set(context.speedLimit());
                Thread.sleep(10);
            }
            throw new CancellationException("stopped");
        }
    }

    private static final class DirectoryDownloader implements Downloader {
        @Override
        public JobType id() {
            return JobType.TORRENT;
        }

        @Override
        public DownloadResult download(DownloadRequest request,
                                       DownloadExecutionContext context,
                                       java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
            Path dir = request.downloadDir().resolve("torrent-" + request.jobId());
            Files.createDirectories(dir.resolve("sub"));
            Files.writeString(dir.resolve("a.txt"), "ab");
            Files.writeString(dir.resolve("sub/b.txt"), "cd");
            return new DownloadResult(dir.toString(), "done");
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
            progressConsumer.accept(new ProgressUpdate(90.0, null, 42L, 1L, "almost done"));
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

    private static final class ObservingBlockingDownloader implements Downloader {
        private final AtomicBoolean cancelObserved = new AtomicBoolean(false);

        @Override
        public JobType id() {
            return JobType.YTDLP;
        }

        @Override
        public DownloadResult download(DownloadRequest request,
                                       DownloadExecutionContext context,
                                       java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
            try {
                while (!context.isCanceled()) {
                    Thread.sleep(20);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            cancelObserved.set(true);
            throw new CancellationException("canceled");
        }

        private boolean cancelObserved() {
            return cancelObserved.get();
        }
    }
}
