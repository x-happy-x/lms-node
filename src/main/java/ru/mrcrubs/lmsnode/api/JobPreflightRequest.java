package ru.mrcrubs.lmsnode.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record JobPreflightRequest(
        @NotBlank
        @Pattern(regexp = "https?://.+", message = "url must start with http:// or https://")
        String url
) {
}
