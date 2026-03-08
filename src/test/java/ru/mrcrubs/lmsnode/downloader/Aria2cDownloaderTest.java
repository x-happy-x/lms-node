package ru.mrcrubs.lmsnode.downloader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Aria2cDownloaderTest {

    private final Aria2cDownloader downloader = new Aria2cDownloader("aria2c");

    @Test
    void parseProgressShouldExtractPercentAndSpeed() {
        String line = "[#123abc 10MiB/100MiB(10%) CN:1 DL:2.5MiB ETA:30s]";

        ProgressUpdate update = downloader.parseProgress(line);

        assertEquals(10.0, update.percent());
        assertEquals((long) (2.5 * 1024 * 1024), update.speedBytes());
        assertEquals(line, update.message());
    }

    @Test
    void parseBytesShouldHandleBinaryAndDecimalUnits() {
        assertEquals(1024L, downloader.parseBytes("1KiB"));
        assertEquals(1_000_000L, downloader.parseBytes("1MB"));
        assertEquals(512L, downloader.parseBytes("512B"));
    }

    @Test
    void parseBytesShouldReturnNullForInvalidInput() {
        assertNull(downloader.parseBytes("bad"));
    }
}
