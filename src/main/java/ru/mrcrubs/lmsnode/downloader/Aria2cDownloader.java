package ru.mrcrubs.lmsnode.downloader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class Aria2cDownloader implements Downloader {
    private final String binary;

    public Aria2cDownloader(@Value("${node.tools.aria2c:aria2c}") String binary) {
        this.binary = binary;
    }

    @Override
    public JobType id() {
        return JobType.ARIA2C;
    }

    @Override
    public DownloadResult download(DownloadRequest request,
                                   DownloadExecutionContext context,
                                   Consumer<ProgressUpdate> progressConsumer) throws Exception {
        List<String> command = new ArrayList<>(commonArgs(binary, request.downloadDir()));
        // Torrents are a separate job type; here a .torrent link is just a file.
        command.add("--follow-torrent=false");
        command.add("--max-tries=10");
        command.add("--retry-wait=10");
        if (request.hasSpeedLimit()) {
            command.add("--max-download-limit=" + request.maxSpeedBytes());
        }
        command.add(request.url());

        Aria2Run run = Aria2Run.execute(command, request.downloadDir(), context, progressConsumer);
        if (run.exitCode() != 0) {
            throw new IllegalStateException(run.failureMessage());
        }
        String outputPath = run.results().stream()
                .filter(Aria2Output.Result::ok)
                .map(Aria2Output.Result::path)
                .filter(path -> Files.exists(Path.of(path)))
                .reduce((first, second) -> second)
                .orElse(null);
        progressConsumer.accept(new ProgressUpdate(100.0, null, null, 0L, "aria2c download finished"));
        return new DownloadResult(outputPath, "aria2c download finished");
    }

    /**
     * Options that make aria2c resumable after pause, cancel+retry or node restart:
     * it continues from the existing file and its {@code .aria2} control file instead of
     * starting over or writing {@code file.1}. The control file is saved on SIGTERM and every 10s.
     */
    static List<String> commonArgs(String binary, Path downloadDir) {
        return List.of(
                binary,
                "--dir=" + downloadDir,
                "--continue=true",
                // Resume when the server supports ranges, otherwise start the file over instead of failing.
                "--always-resume=false",
                "--max-resume-failure-tries=0",
                "--auto-file-renaming=false",
                "--allow-overwrite=false",
                "--file-allocation=none",
                "--auto-save-interval=10",
                "--console-log-level=warn",
                "--summary-interval=1",
                "--show-console-readout=true",
                "--enable-color=false"
        );
    }

    ProgressUpdate parseProgress(String line) {
        return Aria2Output.parseProgress(line);
    }

    Long parseBytes(String value) {
        return Aria2Output.parseBytes(value);
    }

    /**
     * One aria2c process run: progress is forwarded, all output is kept for the result table.
     */
    record Aria2Run(int exitCode, List<String> output, List<Aria2Output.Result> results) {
        static Aria2Run execute(List<String> command,
                                Path workDir,
                                DownloadExecutionContext context,
                                Consumer<ProgressUpdate> progressConsumer) throws Exception {
            List<String> output = new ArrayList<>();
            int exitCode = ExternalProcess.run(command, workDir, context, line -> {
                if (Aria2Output.isReadout(line)) {
                    progressConsumer.accept(Aria2Output.parseProgress(line));
                } else {
                    output.add(line);
                }
            });
            return new Aria2Run(exitCode, output, Aria2Output.parseResults(output));
        }

        String failureMessage() {
            String detail = output.stream()
                    .filter(line -> line.contains("[ERROR]") || line.startsWith("Exception:") || line.contains("errorCode="))
                    .reduce((first, second) -> second)
                    .orElse(null);
            return "aria2c exited with code " + exitCode + (detail == null ? "" : ": " + detail.strip());
        }
    }
}
