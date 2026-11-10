package ru.mrcrubs.lmsnode.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** Typed options only; callers cannot supply arbitrary process arguments. */
public record YtDlpOptions(
        @Min(144) @Max(4320) Integer maxHeight,
        Boolean audioOnly,
        @Pattern(regexp = "auto|mp4|mkv") String container,
        Boolean playlist
) {
    public void validate() {
        if (maxHeight != null && (maxHeight < 144 || maxHeight > 4320)) {
            throw new IllegalArgumentException("maxHeight must be between 144 and 4320");
        }
        if (container != null && !java.util.Set.of("auto", "mp4", "mkv").contains(container)) {
            throw new IllegalArgumentException("container must be auto, mp4 or mkv");
        }
    }
}
