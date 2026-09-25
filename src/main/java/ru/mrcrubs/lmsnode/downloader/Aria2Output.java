package ru.mrcrubs.lmsnode.downloader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for aria2c console output: progress readouts and the final "Download Results" table.
 */
final class Aria2Output {
    // [#2089b0 400.0KiB/33.2MiB(1%) CN:1 DL:115.7KiB ETA:4m51s]
    // [#a1b2c3 12MiB/700MiB(1%) CN:44 SD:10 DL:3.2MiB UL:0B(0B) ETA:3m36s]
    private static final Pattern READOUT_PATTERN = Pattern.compile("^\\s*\\[#[0-9a-fA-F]+ .*]\\s*$");
    private static final Pattern SIZE_PATTERN = Pattern.compile("\\s([0-9.]+[KMGT]?i?B)/([0-9.]+[KMGT]?i?B)\\((\\d{1,3})%\\)");
    private static final Pattern PERCENT_PATTERN = Pattern.compile("\\((\\d{1,3})%\\)");
    private static final Pattern SPEED_PATTERN = Pattern.compile("DL:([0-9.]+[KMGT]?i?B)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETA_PATTERN = Pattern.compile("ETA:((?:\\d+h)?(?:\\d+m)?(?:\\d+s)?)");
    private static final Pattern SEED_PATTERN = Pattern.compile("\\sSEED\\(");
    // 2089b0|OK  |   1.5MiB/s|/downloads/file.iso
    private static final Pattern RESULT_PATTERN = Pattern.compile("^([0-9a-fA-F]{6})\\|(OK|ERR|INPR|RM)\\s*\\|.*\\|(.*)$");
    private static final Pattern MORE_FILES_SUFFIX = Pattern.compile("\\s*\\(\\d+more\\)$");

    private Aria2Output() {
    }

    static boolean isReadout(String line) {
        return READOUT_PATTERN.matcher(line).matches();
    }

    static ProgressUpdate parseProgress(String line) {
        Double percent = null;
        Long totalBytes = null;
        Matcher sizeMatcher = SIZE_PATTERN.matcher(line);
        if (sizeMatcher.find()) {
            totalBytes = parseBytes(sizeMatcher.group(2));
            percent = Double.valueOf(sizeMatcher.group(3));
            if (totalBytes != null && totalBytes <= 0) {
                // Magnet metadata phase reports 0B/0B; the real size is not known yet.
                totalBytes = null;
                percent = null;
            }
        } else {
            Matcher percentMatcher = PERCENT_PATTERN.matcher(line);
            if (percentMatcher.find()) {
                percent = Double.valueOf(percentMatcher.group(1));
            }
        }
        if (SEED_PATTERN.matcher(line).find()) {
            percent = 100.0;
        }

        Matcher speedMatcher = SPEED_PATTERN.matcher(line);
        Long speedBytes = speedMatcher.find() ? parseBytes(speedMatcher.group(1)) : null;
        Matcher etaMatcher = ETA_PATTERN.matcher(line);
        Long etaSeconds = etaMatcher.find() ? parseEta(etaMatcher.group(1)) : null;

        return new ProgressUpdate(percent, totalBytes, speedBytes, etaSeconds, line);
    }

    static Long parseBytes(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        long multiplier = 1;
        String[][] units = {
                {"TIB", String.valueOf(1L << 40)}, {"GIB", String.valueOf(1L << 30)},
                {"MIB", String.valueOf(1L << 20)}, {"KIB", String.valueOf(1L << 10)},
                {"TB", "1000000000000"}, {"GB", "1000000000"}, {"MB", "1000000"}, {"KB", "1000"},
                {"B", "1"}
        };
        for (String[] unit : units) {
            if (normalized.endsWith(unit[0])) {
                multiplier = Long.parseLong(unit[1]);
                normalized = normalized.substring(0, normalized.length() - unit[0].length());
                break;
            }
        }
        try {
            return (long) (Double.parseDouble(normalized) * multiplier);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Long parseEta(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\d+)([hms])").matcher(value);
        long seconds = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            long amount = Long.parseLong(matcher.group(1));
            seconds += switch (matcher.group(2)) {
                case "h" -> amount * 3600;
                case "m" -> amount * 60;
                default -> amount;
            };
        }
        return found ? seconds : null;
    }

    /**
     * Result rows of the "Download Results" table in output order. Rows for in-memory
     * metadata downloads (magnet info, .torrent followed in memory) are skipped.
     */
    static List<Result> parseResults(List<String> lines) {
        List<Result> results = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = RESULT_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            String path = MORE_FILES_SUFFIX.matcher(matcher.group(3).trim()).replaceFirst("");
            if (path.isEmpty() || path.startsWith("[")) {
                continue;
            }
            results.add(new Result(matcher.group(1), matcher.group(2), path));
        }
        return results;
    }

    record Result(String gid, String status, String path) {
        boolean ok() {
            return "OK".equals(status);
        }
    }
}
