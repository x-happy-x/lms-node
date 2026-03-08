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
public class Aria2cDownloader implements Downloader {
    private static final Pattern PERCENT_PATTERN = Pattern.compile("\\((\\d{1,3})%\\)");
    private static final Pattern SPEED_PATTERN = Pattern.compile("DL:([0-9.]+[KMG]?i?B)", Pattern.CASE_INSENSITIVE);

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
                                   java.util.function.Consumer<ProgressUpdate> progressConsumer) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--console-log-level=warn");
        command.add("--summary-interval=1");
        command.add("--dir=" + request.downloadDir());
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
                    throw new CancellationException("aria2c canceled");
                }
            }
        }

        int exitCode = process.waitFor();
        if (context.isCanceled()) {
            throw new CancellationException("aria2c canceled");
        }
        if (exitCode != 0) {
            throw new IllegalStateException("aria2c exited with code " + exitCode);
        }

        return new DownloadResult(null, lastLine == null ? "aria2c finished" : lastLine);
    }

    ProgressUpdate parseProgress(String line) {
        Matcher percentMatcher = PERCENT_PATTERN.matcher(line);
        Matcher speedMatcher = SPEED_PATTERN.matcher(line);

        Double percent = percentMatcher.find() ? Double.valueOf(percentMatcher.group(1)) : null;
        Long speedBytes = speedMatcher.find() ? parseBytes(speedMatcher.group(1)) : null;

        return new ProgressUpdate(percent, speedBytes, null, line);
    }

    Long parseBytes(String value) {
        String normalized = value.trim().toUpperCase();
        long multiplier = 1;

        if (normalized.endsWith("KIB")) {
            multiplier = 1024L;
            normalized = normalized.substring(0, normalized.length() - 3);
        } else if (normalized.endsWith("MIB")) {
            multiplier = 1024L * 1024L;
            normalized = normalized.substring(0, normalized.length() - 3);
        } else if (normalized.endsWith("GIB")) {
            multiplier = 1024L * 1024L * 1024L;
            normalized = normalized.substring(0, normalized.length() - 3);
        } else if (normalized.endsWith("KB")) {
            multiplier = 1000L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("MB")) {
            multiplier = 1000L * 1000L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("GB")) {
            multiplier = 1000L * 1000L * 1000L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("B")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        try {
            return (long) (Double.parseDouble(normalized) * multiplier);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
