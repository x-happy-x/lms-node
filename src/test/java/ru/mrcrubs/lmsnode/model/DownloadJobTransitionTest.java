package ru.mrcrubs.lmsnode.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DownloadJobTransitionTest {

    @Test
    void shouldFollowQueuedRunningDoneFlow() {
        DownloadJob job = new DownloadJob(UUID.randomUUID(), JobType.DIRECT, "https://example.com/file", Instant.now());

        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertTrue(job.start(Instant.now(), "Started"));
        assertEquals(JobStatus.RUNNING, job.getStatus());

        assertTrue(job.complete(Instant.now(), "Done", "/downloads/file.bin"));
        assertEquals(JobStatus.DONE, job.getStatus());
        assertEquals(100.0, job.getPercent());
        assertTrue(job.isTerminal());

        assertFalse(job.start(Instant.now(), "Restart"));
        assertFalse(job.cancel(Instant.now(), "Cancel after done"));
        assertFalse(job.fail(Instant.now(), "Fail after done"));
    }

    @Test
    void shouldAllowQueuedCancel() {
        DownloadJob job = new DownloadJob(UUID.randomUUID(), JobType.YTDLP, "https://example.com/video", Instant.now());

        assertTrue(job.cancel(Instant.now(), "Canceled by request"));
        assertEquals(JobStatus.CANCELED, job.getStatus());
        assertNotNull(job.getFinishedAt());
        assertEquals("Canceled by request", job.getMessage());
        assertTrue(job.isTerminal());

        assertFalse(job.start(Instant.now(), "late start"));
        assertFalse(job.complete(Instant.now(), "late done", null));
    }

    @Test
    void shouldFailOnlyFromRunning() {
        DownloadJob queued = new DownloadJob(UUID.randomUUID(), JobType.ARIA2C, "https://example.com/archive", Instant.now());
        assertFalse(queued.fail(Instant.now(), "cannot fail from queued"));
        assertEquals(JobStatus.QUEUED, queued.getStatus());

        assertTrue(queued.start(Instant.now(), "started"));
        assertTrue(queued.fail(Instant.now(), "network error"));
        assertEquals(JobStatus.ERROR, queued.getStatus());
        assertEquals("network error", queued.getMessage());
        assertNotNull(queued.getFinishedAt());
    }
}
