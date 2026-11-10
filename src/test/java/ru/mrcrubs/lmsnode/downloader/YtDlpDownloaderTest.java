package ru.mrcrubs.lmsnode.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mrcrubs.lmsnode.model.JobType;
import ru.mrcrubs.lmsnode.model.YtDlpOptions;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class YtDlpDownloaderTest {

    private final YtDlpDownloader downloader = new YtDlpDownloader("yt-dlp");

    @Test
    void advancedOptionsReachYtdlpAsTypedArguments(@TempDir Path dir) {
        var options = new YtDlpOptions(1080, false, "mp4", false, null, 30, 15, 75, true, true);
        options.validate();
        var command = downloader.buildCommand(new DownloadRequest(UUID.randomUUID(), JobType.YTDLP,
                "https://example.com/video", dir, null, options), dir.resolve("paths.txt"));
        assertEquals("bestvideo*[height<=1080][fps<=30]+bestaudio/best[height<=1080][fps<=30]", command.get(command.indexOf("-f")+1));
        assertEquals("*15-75", command.get(command.indexOf("--download-sections")+1));
        assertTrue(command.contains("--force-keyframes-at-cuts")); assertTrue(command.contains("--embed-metadata"));
        var audio = new YtDlpOptions(null,true,null,false,"opus",null,null,null,false,false);
        var audioCommand = downloader.buildCommand(new DownloadRequest(UUID.randomUUID(),JobType.YTDLP,"https://example.com/video",dir,null,audio),dir.resolve("paths.txt"));
        assertEquals("opus", audioCommand.get(audioCommand.indexOf("--audio-format")+1));
        assertThrows(IllegalArgumentException.class,()->new YtDlpOptions(null,false,null,false,null,null,80,20,false,false).validate());
        assertThrows(IllegalArgumentException.class,()->new YtDlpOptions(null,false,null,false,"--exec",null,null,null,false,false).validate());
    }

    @Test
    void playlistResultShouldExposeAllFilesAsDirectory(@TempDir Path dir) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path stub = dir.resolve(windows ? "fake-ytdlp.cmd" : "fake-ytdlp");
        String script = windows ? """
                @echo off
                setlocal
                :parse
                if "%~1"=="" goto done
                if "%~1"=="--print-to-file" set "list=%~3"
                if "%~1"=="-P" set "out=%~2"
                shift
                goto parse
                :done
                echo one>"%out%\\01.mp4"
                echo two>"%out%\\02.mp4"
                >"%list%" echo %out%\\01.mp4
                >>"%list%" echo %out%\\02.mp4
                exit /b 0
                """ : """
                #!/bin/sh
                while [ "$#" -gt 0 ]; do
                  case "$1" in --print-to-file) list="$3";; -P) out="$2";; esac
                  shift
                done
                printf one > "$out/01.mp4"
                printf two > "$out/02.mp4"
                printf '%s\\n' "$out/01.mp4" "$out/02.mp4" > "$list"
                """;
        Files.writeString(stub, windows ? script.replace("\n", "\r\n") : script);
        if (!windows) assertTrue(stub.toFile().setExecutable(true));
        var request = new DownloadRequest(UUID.randomUUID(), JobType.YTDLP, "https://example.com/list", dir,
                null, new YtDlpOptions(null, false, "auto", true));
        var tool = new YtDlpDownloader(stub.toString());
        DownloadResult result = tool.download(request, new DownloadExecutionContext(), ignored -> {});
        Path output = Path.of(result.outputPath());
        assertTrue(Files.isDirectory(output));
        assertTrue(Files.exists(output.resolve("01.mp4")));
        assertTrue(Files.exists(output.resolve("02.mp4")));
        assertEquals(tool.outputDirectory(request), output);
    }

    @Test
    void qualityAndPlaylistShouldReachCommandAndKeepStableSeparateDirectory(@TempDir Path dir) {
        var request = new DownloadRequest(UUID.randomUUID(), JobType.YTDLP, "https://example.com/list", dir,
                1024L, new YtDlpOptions(1080, false, "mp4", true));
        var command = downloader.buildCommand(request, dir.resolve("paths.txt"));
        assertTrue(command.contains("--yes-playlist"));
        assertEquals("bestvideo*[height<=1080]+bestaudio/best[height<=1080]", command.get(command.indexOf("-f") + 1));
        assertEquals("mp4", command.get(command.indexOf("--remux-video") + 1));
        assertEquals(dir.resolve("playlist-" + request.jobId()), downloader.outputDirectory(request));
        assertEquals(downloader.outputDirectory(request).toString(), command.get(command.indexOf("-P") + 1));
        assertTrue(command.get(command.indexOf("-o") + 1).contains("playlist_index"));
        assertEquals(List.of("--", request.url()), command.subList(command.size() - 2, command.size()));
        assertEquals(command, downloader.buildCommand(request, dir.resolve("paths.txt")), "resume keeps filenames and directory");
    }

    @Test
    void audioOnlyShouldIgnoreVideoQualityAndContainer(@TempDir Path dir) {
        var request = new DownloadRequest(UUID.randomUUID(), JobType.YTDLP, "https://example.com/video", dir,
                null, new YtDlpOptions(720, true, "mkv", false));
        var command = downloader.buildCommand(request, dir.resolve("paths.txt"));
        assertTrue(command.contains("--no-playlist"));
        assertTrue(command.contains("--extract-audio"));
        assertEquals("m4a", command.get(command.indexOf("--audio-format") + 1));
        assertFalse(command.contains("--remux-video"));
        assertEquals(dir, downloader.outputDirectory(request));
        assertThrows(IllegalArgumentException.class, () -> new YtDlpOptions(50, false, "auto", null).validate());
        assertThrows(IllegalArgumentException.class, () -> new YtDlpOptions(null, false, "--exec", null).validate());
    }

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
