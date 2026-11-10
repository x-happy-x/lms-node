package ru.mrcrubs.lmsnode.model;

import java.util.List;

/** A typed yt-dlp profile, never arbitrary process/shell arguments. */
public final class YtDlpProfile {
    public static final String PATTERN = "(?:auto|(?:chrome|edge|safari|firefox|tor)(?:-[0-9][a-z0-9.]*)?(?::(?:windows|macos|linux|android|ios)(?:-[0-9][a-z0-9.]*)?)?)";
    private YtDlpProfile() {}
    public static void validate(String value) {
        if (value != null && !value.isEmpty() && (value.length() > 80 || !value.matches(PATTERN)))
            throw new IllegalArgumentException("Invalid yt-dlp impersonation profile");
    }
    public static void append(List<String> command, String profile) {
        validate(profile);
        if (profile != null && !profile.isEmpty()) command.addAll(List.of("--impersonate", "auto".equals(profile) ? "" : profile));
    }
}
