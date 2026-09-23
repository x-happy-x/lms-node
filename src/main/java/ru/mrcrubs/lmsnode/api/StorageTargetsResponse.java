package ru.mrcrubs.lmsnode.api;

import java.util.List;

public record StorageTargetsResponse(
        String defaultPath,
        List<StorageTargetResponse> targets
) {
}
