package ru.mrcrubs.lmsnode.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobType;
import ru.mrcrubs.lmsnode.model.YtDlpOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class YtDlpOptionsPersistenceTest {
    @Test
    void optionsSurviveRestartAndOldSnapshotsStillLoad(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("jobs.json");
        var job = new DownloadJob(UUID.randomUUID(), JobType.YTDLP, "https://example.com/list", "courses", Instant.now());
        var options = new YtDlpOptions(720, false, "mkv", true);
        job.setYtdlpOptions(options);
        new FileJobRepository(state).save(job);
        var restored = new FileJobRepository(state).findById(job.getJobId()).orElseThrow();
        assertEquals(options, restored.getYtdlpOptions());
        assertEquals("courses", restored.getStoragePath());
        Files.writeString(state, "[{\"jobId\":\"" + job.getJobId() + "\",\"type\":\"YTDLP\",\"url\":\"https://example.com/v\"}]");
        assertNull(new FileJobRepository(state).findById(job.getJobId()).orElseThrow().getYtdlpOptions());
    }
}
