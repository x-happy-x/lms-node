package ru.mrcrubs.lmsnode.api;

import ru.mrcrubs.lmsnode.model.JobType;

public record DownloadOptionResponse(
        JobType type,
        boolean supported,
        boolean resumeSupported,
        boolean segmentedPossible,
        String message
) {
}
