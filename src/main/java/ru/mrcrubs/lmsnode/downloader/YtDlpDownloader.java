package ru.mrcrubs.lmsnode.downloader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.mrcrubs.lmsnode.model.JobType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
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
                                   java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--newline");
        command.add("-P");
        command.add(request.downloadDir().toString());
        command.add(request.url());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        context.registerCancelAction(() -> process.destroyForcibly());

        String lastLine = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastLine = line;
                progressConsumer.accept(parseProgress(line));
                if (context.isCanceled()) {
                    throw new CancellationException("yt-dlp canceled");
                }
            }
        }

        int exitCode = process.waitFor();
        if (context.isCanceled()) {
            throw new CancellationException("yt-dlp canceled");
        }
        if (exitCode != 0) {
            throw new IllegalStateException("yt-dlp exited with code " + exitCode);
        }

        return new DownloadResult(null, lastLine == null ? "yt-dlp finished" : lastLine);
    }

    private ProgressUpdate parseProgress(String line) {
        Matcher percentMatcher = PERCENT_PATTERN.matcher(line);
        Matcher speedMatcher = SPEED_PATTERN.matcher(line);
        Matcher etaMatcher = ETA_PATTERN.matcher(line);

        Double percent = percentMatcher.find() ? Double.parseDouble(percentMatcher.group(1)) : null;
        Long speedBytes = speedMatcher.find() ? parseBytesPerSecond(speedMatcher.group(1)) : null;
        Long etaSeconds = etaMatcher.find() ? parseEtaSeconds(etaMatcher.group(1)) : null;

        return new ProgressUpdate(percent, speedBytes, etaSeconds, line);
    }

    private Long parseBytesPerSecond(String speed) {
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

        return (long) (Double.parseDouble(numericPart) * multiplier);
    }

    private Long parseEtaSeconds(String value) {
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
}
