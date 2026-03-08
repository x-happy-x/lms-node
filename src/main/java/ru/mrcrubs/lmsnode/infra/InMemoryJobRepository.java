package ru.mrcrubs.lmsnode.infra;

import org.springframework.stereotype.Repository;
import ru.mrcrubs.lmsnode.model.DownloadJob;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryJobRepository implements JobRepository {
    private final ConcurrentHashMap<UUID, DownloadJob> jobs = new ConcurrentHashMap<>();

    @Override
    public DownloadJob save(DownloadJob job) {
        jobs.put(job.getJobId(), job);
        return job;
    }

    @Override
    public Optional<DownloadJob> findById(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<DownloadJob> findAll() {
        return new ArrayList<>(jobs.values());
    }
}
