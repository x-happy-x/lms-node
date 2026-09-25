package ru.mrcrubs.lmsnode.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mrcrubs.lmsnode.config.NodeProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreviewServiceTest {

    @TempDir
    Path tempDir;

    private PreviewService service(String ffmpeg) {
        NodeProperties properties = new NodeProperties();
        properties.setStateFile(tempDir.resolve("state/jobs.json").toString());
        return new PreviewService(ffmpeg, properties);
    }

    @Test
    void onlyImagesAndVideosArePreviewable() throws Exception {
        Files.createDirectories(tempDir.resolve("dir"));
        assertTrue(PreviewService.isPreviewable(tempDir.resolve("a.JPG")));
        assertTrue(PreviewService.isPreviewable(tempDir.resolve("a.mkv")));
        assertFalse(PreviewService.isPreviewable(tempDir.resolve("a.zip")));
        assertFalse(PreviewService.isPreviewable(tempDir.resolve("dir")));
    }

    @Test
    void missingFfmpegGivesNoPreview() throws Exception {
        Path video = tempDir.resolve("clip.mp4");
        Files.writeString(video, "not really a video");

        assertEquals(Optional.empty(), service("/nonexistent/ffmpeg").preview(UUID.randomUUID(), video));
    }

    @Test
    void makesAndCachesVideoThumbnailWithRealFfmpeg() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg is not installed");
        Path video = tempDir.resolve("clip.mp4");
        // 2-second test clip: shorter than the 5s seek, so the start-of-clip fallback is used.
        Process make = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc=duration=2:size=1280x720:rate=10", "-pix_fmt", "yuv420p", video.toString())
                .redirectErrorStream(true).start();
        assertTrue(make.waitFor(60, TimeUnit.SECONDS) && make.exitValue() == 0, "test clip was not created");

        PreviewService previews = service("ffmpeg");
        UUID jobId = UUID.randomUUID();
        Path thumb = previews.preview(jobId, video).orElseThrow();

        byte[] bytes = Files.readAllBytes(thumb);
        assertTrue(bytes.length > 100 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8, "not a JPEG");
        assertTrue(thumb.startsWith(tempDir.resolve("state/previews")));

        long modified = Files.getLastModifiedTime(thumb).toMillis();
        assertEquals(thumb, previews.preview(jobId, video).orElseThrow());
        assertEquals(modified, Files.getLastModifiedTime(thumb).toMillis(), "cached thumbnail should be reused");

        previews.evict(jobId);
        assertFalse(Files.exists(thumb));
    }

    private static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return false;
        }
    }
}
