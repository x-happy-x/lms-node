package ru.mrcrubs.lmsnode.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobStatus;
import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Job store that survives node restarts: jobs are kept in memory and written to a JSON
 * file on every save, so interrupted downloads can be continued after a redeploy.
 */
public class FileJobRepository extends InMemoryJobRepository {
    private static final Logger log = LoggerFactory.getLogger(FileJobRepository.class);
    private static final TypeReference<List<JobSnapshot>> SNAPSHOT_LIST = new TypeReference<>() {
    };

    private final Path stateFile;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Object writeLock = new Object();

    public FileJobRepository(Path stateFile) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
        load();
    }

    public Path getStateFile() {
        return stateFile;
    }

    @Override
    public DownloadJob save(DownloadJob job) {
        super.save(job);
        flush();
        return job;
    }

    public void flush() {
        synchronized (writeLock) {
            try {
                List<JobSnapshot> snapshots = jobs.values().stream()
                        .sorted(Comparator.comparing(DownloadJob::getCreatedAt))
                        .map(JobSnapshot::from)
                        .toList();
                Files.createDirectories(stateFile.getParent());
                Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
                Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshots));
                try {
                    Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomicMoveFailed) {
                    Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ex) {
                log.warn("Failed to persist job state to {}: {}", stateFile, ex.getMessage());
            }
        }
    }

    private void load() {
        if (!Files.exists(stateFile)) {
            return;
        }
        try {
            List<JobSnapshot> snapshots = mapper.readValue(stateFile.toFile(), SNAPSHOT_LIST);
            for (JobSnapshot snapshot : snapshots) {
                if (snapshot.jobId() != null && snapshot.type() != null) {
                    super.save(snapshot.toJob());
                }
            }
            log.info("Loaded {} job(s) from {}", jobs.size(), stateFile);
        } catch (Exception ex) {
            log.warn("Failed to load job state from {}: {}", stateFile, ex.getMessage());
        }
    }

    record JobSnapshot(UUID jobId,
                       JobType type,
                       String url,
                       String storagePath,
                       JobStatus status,
                       Double percent,
                       Long totalBytes,
                       String message,
                       Instant createdAt,
                       Instant startedAt,
                       Instant finishedAt,
                       String outputPath,
                       Long outputSizeBytes,
                       Long maxSpeedBytes) {
        // Fields are volatile; no job lock here, callers may already hold other jobs' locks.
        static JobSnapshot from(DownloadJob job) {
            return new JobSnapshot(job.getJobId(), job.getType(), job.getUrl(), job.getStoragePath(),
                    job.getStatus(), job.getPercent(), job.getTotalBytes(), job.getMessage(),
                    job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(),
                    job.getOutputPath(), job.getOutputSizeBytes(), job.getMaxSpeedBytes());
        }

        DownloadJob toJob() {
            DownloadJob job = DownloadJob.restore(jobId, type, url, storagePath, status,
                    createdAt == null ? Instant.now() : createdAt);
            job.setPercent(percent);
            job.setTotalBytes(totalBytes);
            job.setMessage(message);
            job.setStartedAt(startedAt);
            job.setFinishedAt(finishedAt);
            job.setOutputPath(outputPath);
            job.setOutputSizeBytes(outputSizeBytes);
            job.setMaxSpeedBytes(maxSpeedBytes);
            return job;
        }
    }
}
