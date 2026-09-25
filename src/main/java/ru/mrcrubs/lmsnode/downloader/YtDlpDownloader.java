package ru.mrcrubs.lmsnode.downloader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YtDlpDownloader implements Downloader {
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%");
    private static final Pattern SPEED_PATTERN = Pattern.compile("at\\s+([0-9.]+\\s*[KMG]i?B/s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETA_PATTERN = Pattern.compile("ETA\\s+([0-9:]+)", Pattern.CASE_INSENSITIVE);

    private final String binary;

    public YtDlpDownloader(@Value("${node.tools.ytdlp:yt-dlp}") String binary) {
        this.binary = binary;
    }

    @Override
    public JobType id() {
        return JobType.YTDLP;
    }

    @Override
    public DownloadResult download(DownloadRequest request,
                                   DownloadExecutionContext context,
                                   Consumer<ProgressUpdate> progressConsumer) throws Exception {
        Path filesList = Files.createTempFile("lms-node-ytdlp-" + request.jobId(), ".txt");
        try {
            List<String> command = buildCommand(request, filesList);
            AtomicReference<String> lastLine = new AtomicReference<>();
            AtomicReference<String> lastError = new AtomicReference<>();
            int exitCode = ExternalProcess.run(command, request.downloadDir(), context, line -> {
                if (!line.startsWith("[info] Writing '%(filepath)s'")) {
                    lastLine.set(line);
                }
                if (line.startsWith("ERROR:")) {
                    lastError.set(line);
                }
                progressConsumer.accept(parseProgress(line));
            });
            if (exitCode != 0) {
                String detail = lastError.get() != null ? lastError.get() : lastLine.get();
                throw new IllegalStateException("yt-dlp exited with code " + exitCode + (detail == null ? "" : ": " + detail));
            }

            String outputPath = readLastExistingPath(filesList);
            return new DownloadResult(outputPath, lastLine.get() == null ? "yt-dlp finished" : lastLine.get());
        } finally {
            Files.deleteIfExists(filesList);
        }
    }

    /**
     * {@code --continue} keeps and reuses {@code .part} files (the process is stopped with
     * SIGTERM on pause, so they survive), and the final paths are written to a side file
     * so the job gets an {@code outputPath} like DIRECT downloads do.
     */
    List<String> buildCommand(DownloadRequest request, Path filesList) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--newline");
        command.add("--continue");
        command.add("--retries");
        command.add("20");
        command.add("--fragment-retries");
        command.add("20");
        command.add("--socket-timeout");
        command.add("60");
        command.add("--print-to-file");
        command.add("after_move:filepath");
        command.add(filesList.toString());
        command.add("-P");
        command.add(request.downloadDir().toString());
        command.add(request.url());
        return command;
    }

    private String readLastExistingPath(Path filesList) {
        try {
            List<String> lines = Files.readAllLines(filesList);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String candidate = lines.get(i).strip();
                if (!candidate.isEmpty() && Files.exists(Path.of(candidate))) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // Output path is only a hint.
        }
        return null;
    }

    ProgressUpdate parseProgress(String line) {
        Matcher percentMatcher = PERCENT_PATTERN.matcher(line);
        Matcher speedMatcher = SPEED_PATTERN.matcher(line);
        Matcher etaMatcher = ETA_PATTERN.matcher(line);

        Double percent = percentMatcher.find() ? parseDoubleSafely(percentMatcher.group(1)) : null;
        Long speedBytes = speedMatcher.find() ? parseBytesPerSecond(speedMatcher.group(1)) : null;
        Long etaSeconds = etaMatcher.find() ? parseEtaSeconds(etaMatcher.group(1)) : null;

        return new ProgressUpdate(percent, null, speedBytes, etaSeconds, line);
    }

    Long parseBytesPerSecond(String speed) {
        String normalized = speed.replace(" ", "").toUpperCase();
        if (!normalized.endsWith("/S")) {
            return null;
        }
        String numericPart = normalized.replace("B/S", "");

        long multiplier = 1;
        if (numericPart.endsWith("KI")) {
            multiplier = 1024L;
            numericPart = numericPart.substring(0, numericPart.length() - 2);
        } else if (numericPart.endsWith("MI")) {
            multiplier = 1024L * 1024L;
            numericPart = numericPart.substring(0, numericPart.length() - 2);
        } else if (numericPart.endsWith("GI")) {
            multiplier = 1024L * 1024L * 1024L;
            numericPart = numericPart.substring(0, numericPart.length() - 2);
        } else if (numericPart.endsWith("K")) {
            multiplier = 1000L;
            numericPart = numericPart.substring(0, numericPart.length() - 1);
        } else if (numericPart.endsWith("M")) {
            multiplier = 1000L * 1000L;
            numericPart = numericPart.substring(0, numericPart.length() - 1);
        } else if (numericPart.endsWith("G")) {
            multiplier = 1000L * 1000L * 1000L;
            numericPart = numericPart.substring(0, numericPart.length() - 1);
        }

        try {
            return (long) (Double.parseDouble(numericPart) * multiplier);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    Long parseEtaSeconds(String value) {
        String[] parts = value.split(":");
        try {
            if (parts.length == 2) {
                return Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1]);
            }
            if (parts.length == 3) {
                return Long.parseLong(parts[0]) * 3600L + Long.parseLong(parts[1]) * 60L + Long.parseLong(parts[2]);
            }
            return null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Double parseDoubleSafely(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
