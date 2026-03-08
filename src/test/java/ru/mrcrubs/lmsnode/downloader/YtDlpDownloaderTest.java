package ru.mrcrubs.lmsnode.downloader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class YtDlpDownloaderTest {

    private final YtDlpDownloader downloader = new YtDlpDownloader("yt-dlp");

    @Test
    void parseProgressShouldExtractPercentSpeedAndEta() {
        String line = "[download]  42.5% of 12.00MiB at 2.00MiB/s ETA 00:03";

        ProgressUpdate update = downloader.parseProgress(line);

        assertEquals(42.5, update.percent());
        assertEquals(2L * 1024 * 1024, update.speedBytes());
        assertEquals(3L, update.etaSeconds());
        assertEquals(line, update.message());
    }

    @Test
    void parseBytesPerSecondShouldSupportDecimalUnits() {
        assertEquals(1_500_000L, downloader.parseBytesPerSecond("1.5 MB/s"));
        assertEquals(1_500L, downloader.parseBytesPerSecond("1.5 KB/s"));
    }

    @Test
    void parseBytesPerSecondShouldReturnNullForInvalidValues() {
        assertNull(downloader.parseBytesPerSecond("oops"));
        assertNull(downloader.parseBytesPerSecond("12 XB/s"));
    }

    @Test
    void parseEtaSecondsShouldParseMinuteAndHourFormats() {
        assertEquals(75L, downloader.parseEtaSeconds("01:15"));
        assertEquals(3670L, downloader.parseEtaSeconds("01:01:10"));
        assertNull(downloader.parseEtaSeconds("bad"));
    }
}
