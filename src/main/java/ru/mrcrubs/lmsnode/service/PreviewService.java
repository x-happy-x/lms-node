package ru.mrcrubs.lmsnode.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import ru.mrcrubs.lmsnode.config.NodeProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * JPEG thumbnails for image and video outputs, made with ffmpeg and cached on disk
 * (next to the job state file, or in the temp dir when state is kept in memory).
 */
@Service
public class PreviewService {
    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);
    private static final int WIDTH = 480;
    private static final long TIMEOUT_SECONDS = 60;

    private final String ffmpeg;
    private final Path cacheDir;
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public PreviewService(@Value("${node.tools.ffmpeg:ffmpeg}") String ffmpeg, NodeProperties nodeProperties) {
        this.ffmpeg = ffmpeg;
        String stateFile = nodeProperties.getStateFile();
        this.cacheDir = (stateFile == null || stateFile.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "lms-node-previews")
                : Path.of(stateFile.strip()).toAbsolutePath().getParent().resolve("previews");
    }

    public static boolean isPreviewable(Path source) {
        if (Files.isDirectory(source)) {
            return false;
        }
        return MediaTypeFactory.getMediaType(source.getFileName().toString())
                .map(type -> "image".equals(type.getType()) || "video".equals(type.getType()))
                .orElse(false);
    }

    /** Thumbnail for the output, generated on first request; empty when not possible. */
    public Optional<Path> preview(UUID jobId, Path source) {
        if (!isPreviewable(source)) {
            return Optional.empty();
        }
        Path target = cacheDir.resolve(jobId + ".jpg");
        synchronized (locks.computeIfAbsent(jobId, id -> new Object())) {
            try {
                if (isFresh(target, source)) {
                    return Optional.of(target);
                }
                Files.createDirectories(cacheDir);
                boolean video = MediaTypeFactory.getMediaType(source.getFileName().toString())
                        .map(type -> "video".equals(type.getType()))
                        .orElse(false);
                // Videos: a frame a few seconds in (skips black intros); short clips fall back to the start.
                boolean made = video
                        ? render(source, target, "5") || render(source, target, "0")
                        : render(source, target, null);
                return made ? Optional.of(target) : Optional.empty();
            } catch (Exception ex) {
                log.warn("Preview failed: job={}, file={}, reason={}", jobId, source, ex.getMessage());
                return Optional.empty();
            }
        }
    }

    public void evict(UUID jobId) {
        try {
            Files.deleteIfExists(cacheDir.resolve(jobId + ".jpg"));
        } catch (IOException ignored) {
            // Stale previews are regenerated when the source is newer anyway.
        }
    }

    private boolean isFresh(Path target, Path source) throws IOException {
        return Files.exists(target) && Files.size(target) > 0
                && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0;
    }

    private boolean render(Path source, Path target, String seekSeconds) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp.jpg");
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
        if (seekSeconds != null) {
            command.addAll(List.of("-ss", seekSeconds));
        }
        command.addAll(List.of(
                "-i", source.toString(),
                "-frames:v", "1",
                "-vf", "scale='min(" + WIDTH + ",iw)':-2",
                "-q:v", "5",
                tmp.toString()
        ));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ex) {
            log.debug("ffmpeg is not available: {}", ex.getMessage());
            return false;
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            Files.deleteIfExists(tmp);
            return false;
        }
        if (process.exitValue() != 0 || !Files.exists(tmp) || Files.size(tmp) == 0) {
            Files.deleteIfExists(tmp);
            return false;
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
