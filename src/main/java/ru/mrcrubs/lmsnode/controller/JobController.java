package ru.mrcrubs.lmsnode.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.mrcrubs.lmsnode.api.CreateJobRequest;
import ru.mrcrubs.lmsnode.api.CreateJobResponse;
import ru.mrcrubs.lmsnode.api.JobPreflightRequest;
import ru.mrcrubs.lmsnode.api.JobPreflightResponse;
import ru.mrcrubs.lmsnode.api.JobResponse;
import ru.mrcrubs.lmsnode.api.MoveJobOutputRequest;
import ru.mrcrubs.lmsnode.service.DownloadPreflightService;
import ru.mrcrubs.lmsnode.service.PreviewService;
import ru.mrcrubs.lmsnode.service.JobService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobService;
    private static final String FILE_NAME_HEADER = "X-File-Name";

    private final DownloadPreflightService downloadPreflightService;
    private final PreviewService previewService;

    public JobController(JobService jobService,
                         DownloadPreflightService downloadPreflightService,
                         PreviewService previewService) {
        this.jobService = jobService;
        this.downloadPreflightService = downloadPreflightService;
        this.previewService = previewService;
    }

    @PostMapping("/preflight")
    public JobPreflightResponse preflight(@Valid @RequestBody JobPreflightRequest request) {
        return downloadPreflightService.preflight(request.url());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateJobResponse create(@Valid @RequestBody CreateJobRequest request) {
        UUID jobId = jobService.create(request.type(), request.url(), request.storagePath(), request.shouldStartImmediately());
        return new CreateJobResponse(jobId);
    }

    @GetMapping
    public List<JobResponse> list(@RequestParam(name = "active", required = false) Boolean active) {
        return jobService.list(active).stream().map(JobResponse::from).toList();
    }

    @GetMapping("/{jobId}")
    public JobResponse get(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.get(jobId));
    }

    @PostMapping("/{jobId}/cancel")
    public JobResponse cancel(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.cancel(jobId));
    }

    @PostMapping("/{jobId}/pause")
    public JobResponse pause(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.pause(jobId));
    }

    @PostMapping("/{jobId}/resume")
    public JobResponse resume(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.resume(jobId));
    }

    @PostMapping("/{jobId}/retry")
    public JobResponse retry(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.retry(jobId));
    }

    @PostMapping("/{jobId}/move")
    public JobResponse moveOutput(@PathVariable UUID jobId, @RequestBody MoveJobOutputRequest request) throws Exception {
        return JobResponse.from(jobService.moveJobOutput(jobId, request.storagePath()));
    }

    /**
     * Streams the job output. Files are served as a {@link Resource}, so Spring answers
     * Range requests (206/416) and HEAD; directories (multi-file torrents) come as a ZIP.
     */
    @GetMapping("/{jobId}/file")
    public ResponseEntity<Resource> downloadOutput(@PathVariable UUID jobId, HttpServletResponse response) throws Exception {
        Path output = jobService.resolveJobOutput(jobId);
        String name = output.getFileName().toString();
        if (Files.isDirectory(output)) {
            String zipName = name + ".zip";
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment(zipName));
            response.setHeader(FILE_NAME_HEADER, encodeFileName(zipName));
            ZipStreams.writeDirectory(output, response.getOutputStream());
            response.flushBuffer();
            return null;
        }
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(name).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .lastModified(Files.getLastModifiedTime(output).toMillis())
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(name))
                .header(FILE_NAME_HEADER, encodeFileName(name))
                .body(new FileSystemResource(output));
    }

    /** JPEG thumbnail of an image/video output; 404 when not available. */
    @GetMapping("/{jobId}/preview")
    public ResponseEntity<Resource> preview(@PathVariable UUID jobId) {
        Path output = jobService.resolveJobOutput(jobId);
        return previewService.preview(jobId, output)
                .map(path -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                        .<Resource>body(new FileSystemResource(path)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String attachment(String fileName) {
        return ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString();
    }

    /** Header values are ASCII-only: the router decodes this percent-encoded UTF-8 name. */
    private static String encodeFileName(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @PostMapping("/{jobId}/file/delete")
    public JobResponse deleteOutput(@PathVariable UUID jobId) throws Exception {
        JobResponse response = JobResponse.from(jobService.deleteJobOutput(jobId));
        previewService.evict(jobId);
        return response;
    }
}
