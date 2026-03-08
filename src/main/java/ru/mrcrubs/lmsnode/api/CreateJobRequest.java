package ru.mrcrubs.lmsnode.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.mrcrubs.lmsnode.model.JobType;

public record CreateJobRequest(
        @NotNull JobType type,
        @NotBlank String url
) {
}
