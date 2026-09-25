package ru.mrcrubs.lmsnode.downloader;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Aria2OutputTest {

    @Test
    void parseProgressShouldExtractTotalPercentSpeedAndEta() {
        ProgressUpdate update = Aria2Output.parseProgress("[#2089b0 400.0KiB/33.2MiB(1%) CN:1 DL:115.7KiB ETA:4m51s]");

        assertEquals(1.0, update.percent());
        assertEquals((long) (33.2 * 1024 * 1024), update.totalBytes());
        assertEquals((long) (115.7 * 1024), update.speedBytes());
        assertEquals(4 * 60 + 51L, update.etaSeconds());
    }

    @Test
    void parseProgressShouldHandleTorrentReadout() {
        ProgressUpdate update = Aria2Output.parseProgress(
                "[#a1b2c3 12MiB/1.5GiB(0%) CN:44 SD:10 DL:3.2MiB UL:0B(0B) ETA:1h3m36s]");

        assertEquals(0.0, update.percent());
        assertEquals((long) (1.5 * 1024 * 1024 * 1024), update.totalBytes());
        assertEquals(3600 + 3 * 60 + 36L, update.etaSeconds());
    }

    @Test
    void parseProgressShouldNotReportSizeDuringMagnetMetadataPhase() {
        ProgressUpdate update = Aria2Output.parseProgress("[#a1b2c3 0B/0B CN:5 SD:0 DL:0B]");

        assertNull(update.percent());
        assertNull(update.totalBytes());
        assertEquals(0L, update.speedBytes());
    }

    @Test
    void parseProgressShouldTreatSeedingAsComplete() {
        ProgressUpdate update = Aria2Output.parseProgress("[#a1b2c3 SEED(0.3) CN:3 SD:1 UL:1.1MiB(12MiB)]");

        assertEquals(100.0, update.percent());
    }

    @Test
    void isReadoutShouldMatchOnlyProgressLines() {
        assertTrue(Aria2Output.isReadout("[#2089b0 400.0KiB/33.2MiB(1%) CN:1 DL:115.7KiB ETA:4m51s]"));
        assertFalse(Aria2Output.isReadout("FILE: /downloads/file.bin"));
        assertFalse(Aria2Output.isReadout("09/25 12:40:41 [ERROR] CUID#8 - Download aborted."));
    }

    @Test
    void parseResultsShouldReturnDataRowsAndSkipMetadata() {
        List<Aria2Output.Result> results = Aria2Output.parseResults(List.of(
                "Download Results:",
                "gid   |stat|avg speed  |path/URI",
                "======+====+===========+=======================================================",
                "3f2d1a|OK  |       0B/s|[MEMORY][METADATA]ubuntu.iso",
                "9ab812|OK  |   2.5MiB/s|/downloads/Some Show/episode 01.mkv (3more)",
                "c0ffee|ERR |       0B/s|/downloads/broken.bin",
                "Status Legend:"
        ));

        assertEquals(2, results.size());
        assertEquals("/downloads/Some Show/episode 01.mkv", results.get(0).path());
        assertTrue(results.get(0).ok());
        assertFalse(results.get(1).ok());
    }

    @Test
    void torrentRootShouldReturnTopLevelEntryUnderDownloadDir() {
        Path dir = Path.of("/downloads/movies");

        assertEquals(Path.of("/downloads/movies/Some Show"),
                TorrentDownloader.torrentRoot(dir, Path.of("/downloads/movies/Some Show/s01/e01.mkv")));
        assertEquals(Path.of("/downloads/movies/single.iso"),
                TorrentDownloader.torrentRoot(dir, Path.of("/downloads/movies/single.iso")));
        assertNull(TorrentDownloader.torrentRoot(dir, Path.of("/elsewhere/file.bin")));
    }

    @Test
    void torrentUrlDetection() {
        assertTrue(TorrentDownloader.isMagnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        assertTrue(TorrentDownloader.looksLikeTorrent("https://tracker.example/get/file.torrent?passkey=1"));
        assertFalse(TorrentDownloader.looksLikeTorrent("https://example.com/video.mp4"));
    }

    @Test
    void torrentCommandShouldEnableResumeAndStopSeeding() {
        TorrentDownloader downloader = new TorrentDownloader("aria2c", 0, "6881");
        List<String> command = downloader.buildCommand(new DownloadRequest(
                java.util.UUID.randomUUID(), null, "magnet:?xt=urn:btih:abc", Path.of("/downloads")));

        assertTrue(command.contains("--continue=true"));
        assertTrue(command.contains("--bt-save-metadata=true"));
        assertTrue(command.contains("--bt-load-saved-metadata=true"));
        assertTrue(command.contains("--seed-time=0"));
        assertTrue(command.contains("--listen-port=6881"));
        assertEquals("magnet:?xt=urn:btih:abc", command.getLast());
    }
}
