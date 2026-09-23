package ru.mrcrubs.lmsnode.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.mrcrubs.lmsnode.api.StorageEstimateRequest;
import ru.mrcrubs.lmsnode.api.StorageEstimateResponse;
import ru.mrcrubs.lmsnode.api.StorageTargetResponse;
import ru.mrcrubs.lmsnode.api.StorageTargetsResponse;
import ru.mrcrubs.lmsnode.api.UploadFileResponse;
import ru.mrcrubs.lmsnode.service.JobService;
import ru.mrcrubs.lmsnode.service.StoredFile;

import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    private final JobService jobService;

    public StorageController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/targets")
    public StorageTargetsResponse targets(@RequestParam(name = "requiredBytes", required = false) Long requiredBytes) {
        List<StorageTargetResponse> items = jobService.listStorageTargets(requiredBytes).stream()
                .map(target -> new StorageTargetResponse(
                        target.path().toString(),
                        target.freeBytes(),
                        target.totalBytes(),
                        target.writable(),
                        target.canFit()
                ))
                .toList();
        return new StorageTargetsResponse(jobService.getBaseDownloadDir().toString(), items);
    }

    @PostMapping("/estimate")
    public StorageEstimateResponse estimate(@Valid @RequestBody StorageEstimateRequest request) {
        var result = jobService.estimateDownloadSize(request.type(), request.url());
        return new StorageEstimateResponse(result.sizeBytes(), result.known(), result.message());
    }

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadFileResponse upload(@RequestParam(name = "storagePath", required = false) String storagePath,
                                     @RequestHeader(name = "X-File-Name", required = false) String fileName,
                                     HttpServletRequest request) throws Exception {
        try (InputStream stream = request.getInputStream()) {
            StoredFile stored = jobService.uploadToStorage(storagePath, fileName, stream);
            return new UploadFileResponse(stored.path().toString(), stored.sizeBytes());
        }
    }
}
