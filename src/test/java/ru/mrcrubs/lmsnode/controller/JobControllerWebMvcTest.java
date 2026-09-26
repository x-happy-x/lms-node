package ru.mrcrubs.lmsnode.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.mrcrubs.lmsnode.api.DownloadOptionResponse;
import ru.mrcrubs.lmsnode.api.JobPreflightResponse;
import ru.mrcrubs.lmsnode.auth.HmacAuthFilter;
import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobStatus;
import ru.mrcrubs.lmsnode.model.JobType;
import ru.mrcrubs.lmsnode.service.DownloadPreflightService;
import ru.mrcrubs.lmsnode.service.JobNotFoundException;
import ru.mrcrubs.lmsnode.service.JobService;
import ru.mrcrubs.lmsnode.service.OutputNotAvailableException;
import ru.mrcrubs.lmsnode.service.PreviewService;
import ru.mrcrubs.lmsnode.service.MediaExtractException;
import ru.mrcrubs.lmsnode.service.MediaExtractService;
import ru.mrcrubs.lmsnode.api.MediaExtractResponse;

import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = JobController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HmacAuthFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class JobControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    @MockitoBean
    private DownloadPreflightService downloadPreflightService;

    @MockitoBean
    private PreviewService previewService;

    @MockitoBean
    private MediaExtractService mediaExtractService;

    @TempDir
    Path tempDir;

    @Test
    void preflightShouldReturnCapabilities() throws Exception {
        when(downloadPreflightService.preflight("https://example.com/file")).thenReturn(new JobPreflightResponse(
                "https://example.com/file",
                123L,
                true,
                JobType.DIRECT,
                List.of(JobType.DIRECT, JobType.ARIA2C),
                List.of(new DownloadOptionResponse(JobType.DIRECT, true, true, true, "ok")),
                "/downloads",
                List.of()
        ));

        mockMvc.perform(post("/api/jobs/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/file"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedType").value("DIRECT"))
                .andExpect(jsonPath("$.sizeBytes").value(123))
                .andExpect(jsonPath("$.supportedTypes[0]").value("DIRECT"));
    }

    @Test
    void createShouldReturn201AndJobId() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.create(eq(JobType.YTDLP), eq("https://example.com/video"), isNull(), eq(true), isNull())).thenReturn(jobId);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "YTDLP",
                                  "url": "https://example.com/video"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void createShouldPassStoragePath() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.create(eq(JobType.DIRECT), eq("https://example.com/file"), eq("movies/2026"), eq(true), isNull())).thenReturn(jobId);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "DIRECT",
                                  "url": "https://example.com/file",
                                  "storagePath": "movies/2026"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void createShouldAllowAddingWithoutImmediateStart() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.create(eq(JobType.DIRECT), eq("https://example.com/file"), eq("movies/2026"), eq(false), isNull())).thenReturn(jobId);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "DIRECT",
                                  "url": "https://example.com/file",
                                  "storagePath": "movies/2026",
                                  "startImmediately": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void createShouldReturn400ForInvalidUrl() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "DIRECT",
                                  "url": "ftp://example.com/file"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("url")));
    }

    @Test
    void createShouldAcceptMagnetForTorrent() throws Exception {
        UUID jobId = UUID.randomUUID();
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=file";
        when(jobService.create(JobType.TORRENT, magnet, null, true, null)).thenReturn(jobId);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "TORRENT",
                                  "url": "%s"
                                }
                                """.formatted(magnet)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void createShouldRejectMagnetForNonTorrentType() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "DIRECT",
                                  "url": "magnet:?xt=urn:btih:abc"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("magnet")));
    }

    @Test
    void listShouldReturnJobsAndPassActiveFlag() throws Exception {
        DownloadJob job = job(UUID.randomUUID(), JobType.DIRECT, "https://example.com/file", JobStatus.RUNNING);
        when(jobService.list(true)).thenReturn(List.of(job));

        mockMvc.perform(get("/api/jobs").param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(job.getJobId().toString()))
                .andExpect(jsonPath("$[0].status").value("RUNNING"));

        verify(jobService).list(true);
    }

    @Test
    void getShouldReturn404WhenJobMissing() throws Exception {
        UUID missing = UUID.randomUUID();
        when(jobService.get(missing)).thenThrow(new JobNotFoundException(missing));

        mockMvc.perform(get("/api/jobs/{jobId}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("Job not found")));
    }

    @Test
    void getShouldReturn400ForInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/jobs/{jobId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad request"));
    }

    @Test
    void cancelShouldReturnUpdatedJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        DownloadJob canceled = job(jobId, JobType.ARIA2C, "https://example.com/archive", JobStatus.CANCELED);
        canceled.setMessage("Canceled by request");
        when(jobService.cancel(jobId)).thenReturn(canceled);

        mockMvc.perform(post("/api/jobs/{jobId}/cancel", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.message").value("Canceled by request"));
    }

    @Test
    void pauseShouldReturnUpdatedJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        DownloadJob paused = job(jobId, JobType.DIRECT, "https://example.com/file", JobStatus.PAUSED);
        paused.setMessage("Paused by request");
        when(jobService.pause(jobId)).thenReturn(paused);

        mockMvc.perform(post("/api/jobs/{jobId}/pause", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.message").value("Paused by request"));
    }

    @Test
    void resumeShouldReturnUpdatedJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        DownloadJob resumed = job(jobId, JobType.DIRECT, "https://example.com/file", JobStatus.QUEUED);
        resumed.setMessage("Resumed by request");
        when(jobService.resume(jobId)).thenReturn(resumed);

        mockMvc.perform(post("/api/jobs/{jobId}/resume", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.message").value("Resumed by request"));
    }

    private DownloadJob job(UUID id, JobType type, String url, JobStatus status) {
        DownloadJob job = new DownloadJob(id, type, url, Instant.now());
        if (status == JobStatus.RUNNING) {
            job.start(Instant.now(), "Started");
        } else if (status == JobStatus.PAUSED) {
            job.pause(Instant.now(), "Paused");
        } else if (status == JobStatus.CANCELED) {
            job.cancel(Instant.now(), "Canceled");
        } else if (status == JobStatus.DONE) {
            job.start(Instant.now(), "Started");
            job.complete(Instant.now(), "Done", "/downloads/file");
        } else if (status == JobStatus.ERROR) {
            job.start(Instant.now(), "Started");
            job.fail(Instant.now(), "Failed");
        }
        return job;
    }

    @Test
    void fileShouldSupportRangeAndTypeAndUtf8Name() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path file = tempDir.resolve("summer trip.mp4");
        Files.writeString(file, "0123456789abcdefghij");
        when(jobService.resolveJobOutput(jobId)).thenReturn(file);

        mockMvc.perform(get("/api/jobs/{jobId}/file", jobId).header("Range", "bytes=10-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 10-19/20"))
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("X-File-Name", "summer%20trip.mp4"))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(content().string("abcdefghij"));

        mockMvc.perform(get("/api/jobs/{jobId}/file", jobId))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().string("0123456789abcdefghij"));

        mockMvc.perform(get("/api/jobs/{jobId}/file", jobId).header("Range", "bytes=99-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    void fileNameHeaderShouldBePercentEncodedUtf8() throws Exception {
        // Non-ASCII paths need a UTF-8 JVM locale (the Docker image sets LANG=en_US.UTF-8).
        org.junit.jupiter.api.Assumptions.assumeTrue("UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding")),
                "file system encoding is not UTF-8");
        UUID jobId = UUID.randomUUID();
        Path file = tempDir.resolve("отпуск.mp4");
        Files.writeString(file, "x");
        when(jobService.resolveJobOutput(jobId)).thenReturn(file);

        mockMvc.perform(get("/api/jobs/{jobId}/file", jobId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-File-Name", "%D0%BE%D1%82%D0%BF%D1%83%D1%81%D0%BA.mp4"));
    }

    @Test
    void fileShouldStreamDirectoryAsZip() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path dir = tempDir.resolve("Show");
        Files.createDirectories(dir.resolve("s01"));
        Files.writeString(dir.resolve("s01/e01.mkv"), "episode");
        Files.writeString(dir.resolve("info.nfo"), "nfo");
        when(jobService.resolveJobOutput(jobId)).thenReturn(dir);

        byte[] zip = mockMvc.perform(get("/api/jobs/{jobId}/file", jobId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("X-File-Name", "Show.zip"))
                .andReturn().getResponse().getContentAsByteArray();

        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName() + "=" + new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(List.of("Show/info.nfo=nfo", "Show/s01/e01.mkv=episode"), names);
    }

    @Test
    void fileShouldReturn404WhenOutputMissing() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.resolveJobOutput(jobId)).thenThrow(new OutputNotAvailableException("job output is not available"));

        mockMvc.perform(get("/api/jobs/{jobId}/file", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job output is not available"));
    }

    @Test
    void previewShouldServeJpegOr404() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path file = tempDir.resolve("clip.mp4");
        Path thumb = tempDir.resolve("thumb.jpg");
        Files.writeString(file, "video");
        Files.writeString(thumb, "jpeg");
        when(jobService.resolveJobOutput(jobId)).thenReturn(file);
        when(previewService.preview(jobId, file)).thenReturn(Optional.of(thumb));

        mockMvc.perform(get("/api/jobs/{jobId}/preview", jobId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(content().string("jpeg"));

        when(previewService.preview(jobId, file)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/jobs/{jobId}/preview", jobId))
                .andExpect(status().isNotFound());
    }

    @Test
    void speedShouldSetLimitAndCreateShouldPassIt() throws Exception {
        UUID jobId = UUID.randomUUID();
        DownloadJob job = new DownloadJob(jobId, JobType.DIRECT, "https://example.com/file", Instant.now());
        job.setMaxSpeedBytes(1_048_576L);
        when(jobService.setSpeedLimit(jobId, 1_048_576L)).thenReturn(job);

        mockMvc.perform(post("/api/jobs/{jobId}/speed", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSpeedBytes\":1048576}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSpeedBytes").value(1048576));

        mockMvc.perform(post("/api/jobs/{jobId}/speed", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSpeedBytes\":-5}"))
                .andExpect(status().isBadRequest());

        when(jobService.create(eq(JobType.DIRECT), eq("https://example.com/file"), isNull(), eq(true), eq(2_000_000L))).thenReturn(jobId);
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DIRECT\",\"url\":\"https://example.com/file\",\"maxSpeedBytes\":2000000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void extractShouldListEntriesAndMapErrors() throws Exception {
        when(mediaExtractService.extract("https://example.com/list")).thenReturn(new MediaExtractResponse(
                "https://example.com/list", "playlist", "Mix", "Generic", null, null, null,
                List.of(new MediaExtractResponse.Entry("https://example.com/a.mp4", "A", 12L, null)), 1, false));
        mockMvc.perform(post("/api/jobs/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("playlist"))
                .andExpect(jsonPath("$.entries[0].url").value("https://example.com/a.mp4"))
                .andExpect(jsonPath("$.entries[0].durationSeconds").value(12));

        when(mediaExtractService.extract("https://example.com/nothing"))
                .thenThrow(new MediaExtractException("Unsupported URL: https://example.com/nothing"));
        mockMvc.perform(post("/api/jobs/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/nothing\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Unsupported URL: https://example.com/nothing"));

        mockMvc.perform(post("/api/jobs/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"magnet:?xt=urn:btih:abc\"}"))
                .andExpect(status().isBadRequest());
    }
}
