package ru.mrcrubs.lmsnode.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MediaExtractRequest(
        @NotBlank
        @Pattern(regexp = "(?i)https?://.+", message = "url must start with http:// or https://")
        String url,
        @jakarta.validation.constraints.Size(max = 80) String impersonate
) {
    public MediaExtractRequest(String url) { this(url, null); }
}
