package ru.mrcrubs.lmsnode.infra;

import org.junit.jupiter.api.Test;
import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryJobRepositoryTest {

    @Test
    void shouldSaveAndFindById() {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        DownloadJob job = new DownloadJob(UUID.randomUUID(), JobType.DIRECT, "https://example.com/a", Instant.now());

        repository.save(job);

        DownloadJob loaded = repository.findById(job.getJobId()).orElseThrow();
        assertSame(job, loaded);
    }

    @Test
    void findAllShouldReturnSnapshot() {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        DownloadJob first = new DownloadJob(UUID.randomUUID(), JobType.DIRECT, "https://example.com/1", Instant.now());
        DownloadJob second = new DownloadJob(UUID.randomUUID(), JobType.YTDLP, "https://example.com/2", Instant.now());

        repository.save(first);
        repository.save(second);

        Set<UUID> ids = repository.findAll().stream().map(DownloadJob::getJobId).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(first.getJobId(), second.getJobId()), ids);
    }

    @Test
    void shouldReturnEmptyOptionalForUnknownJob() {
        InMemoryJobRepository repository = new InMemoryJobRepository();

        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }
}
