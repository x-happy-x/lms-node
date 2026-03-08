package ru.mrcrubs.lmsnode.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.mrcrubs.lmsnode.api.CreateJobRequest;
import ru.mrcrubs.lmsnode.api.CreateJobResponse;
import ru.mrcrubs.lmsnode.api.JobResponse;
import ru.mrcrubs.lmsnode.service.JobService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateJobResponse create(@Valid @RequestBody CreateJobRequest request) {
        UUID jobId = jobService.create(request.type(), request.url());
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
}
