package ru.mrcrubs.lmsnode.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import ru.mrcrubs.lmsnode.model.JobType;

public record StorageEstimateRequest(
        @NotNull JobType type,
        @NotBlank
        @Pattern(regexp = "https?://.+", message = "url must start with http:// or https://")
        String url
) {
}
