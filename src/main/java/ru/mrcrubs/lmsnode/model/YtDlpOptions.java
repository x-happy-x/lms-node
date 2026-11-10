package ru.mrcrubs.lmsnode.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** Typed options only; callers cannot supply arbitrary process arguments. */
public record YtDlpOptions(
        @Min(144) @Max(4320) Integer maxHeight,
        Boolean audioOnly,
        @Pattern(regexp = "auto|mp4|mkv") String container,
        Boolean playlist,
        @Pattern(regexp = "m4a|mp3|opus|flac|wav") String audioFormat,
        @Min(1) @Max(240) Integer maxFps,
        @Min(0) @Max(604800) Integer startSeconds,
        @Min(1) @Max(604800) Integer endSeconds,
        Boolean forceKeyframes,
        Boolean embedMetadata
) {
    public YtDlpOptions(Integer maxHeight, Boolean audioOnly, String container, Boolean playlist) {
        this(maxHeight,audioOnly,container,playlist,null,null,null,null,null,null);
    }
    public void validate() {
        if (maxHeight != null && (maxHeight < 144 || maxHeight > 4320)) {
            throw new IllegalArgumentException("maxHeight must be between 144 and 4320");
        }
        if (container != null && !java.util.Set.of("auto", "mp4", "mkv").contains(container)) {
            throw new IllegalArgumentException("container must be auto, mp4 or mkv");
        }
        if(audioFormat!=null && !java.util.Set.of("m4a","mp3","opus","flac","wav").contains(audioFormat))throw new IllegalArgumentException("Invalid audio format");
        if(maxFps!=null && (maxFps<1 || maxFps>240))throw new IllegalArgumentException("Invalid FPS");
        if(startSeconds!=null && (startSeconds<0 || startSeconds>604800))throw new IllegalArgumentException("Invalid start time");
        if(endSeconds!=null && (endSeconds<1 || endSeconds>604800 || endSeconds<=java.util.Objects.requireNonNullElse(startSeconds,0)))throw new IllegalArgumentException("End must follow start");
        if(Boolean.TRUE.equals(forceKeyframes) && startSeconds==null && endSeconds==null)throw new IllegalArgumentException("Accurate cuts require a fragment");
    }
}
