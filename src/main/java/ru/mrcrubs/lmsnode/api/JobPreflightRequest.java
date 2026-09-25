package ru.mrcrubs.lmsnode.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record JobPreflightRequest(
        @NotBlank
        @Pattern(regexp = "(?i)(https?://|magnet:\\?).+", message = "url must start with http://, https:// or magnet:?")
        String url
) {
}
