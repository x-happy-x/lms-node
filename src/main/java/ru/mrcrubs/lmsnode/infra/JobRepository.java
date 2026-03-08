package ru.mrcrubs.lmsnode.infra;

import ru.mrcrubs.lmsnode.model.DownloadJob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {
    DownloadJob save(DownloadJob job);

    Optional<DownloadJob> findById(UUID jobId);

    List<DownloadJob> findAll();
}
