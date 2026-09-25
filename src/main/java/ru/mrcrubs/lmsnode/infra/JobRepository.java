package ru.mrcrubs.lmsnode.infra;

import ru.mrcrubs.lmsnode.model.DownloadJob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {
    /**
     * Stores a new job or records a state change of a known one.
     */
    DownloadJob save(DownloadJob job);

    Optional<DownloadJob> findById(UUID jobId);

    List<DownloadJob> findAll();
}
