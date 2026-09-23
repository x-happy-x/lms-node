package ru.mrcrubs.lmsnode.controller;

import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
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
import ru.mrcrubs.lmsnode.service.JobOutputFile;
import ru.mrcrubs.lmsnode.service.JobService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobService;
    private final DownloadPreflightService downloadPreflightService;

    public JobController(JobService jobService, DownloadPreflightService downloadPreflightService) {
        this.jobService = jobService;
        this.downloadPreflightService = downloadPreflightService;
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

    @GetMapping("/{jobId}/file")
    public ResponseEntity<InputStreamResource> downloadOutput(@PathVariable UUID jobId) throws Exception {
        JobOutputFile output = jobService.openJobOutput(jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(output.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + output.path().getFileName() + "\"")
                .header("X-File-Name", output.path().getFileName().toString())
                .body(new InputStreamResource(output.stream()));
    }

    @PostMapping("/{jobId}/file/delete")
    public JobResponse deleteOutput(@PathVariable UUID jobId) throws Exception {
        return JobResponse.from(jobService.deleteJobOutput(jobId));
    }
}
