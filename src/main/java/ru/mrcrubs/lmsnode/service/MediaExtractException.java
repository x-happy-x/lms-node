package ru.mrcrubs.lmsnode.service;

/** yt-dlp could not read the URL (unsupported site, private video, network error...). */
public class MediaExtractException extends RuntimeException {
    public MediaExtractException(String message) {
        super(message);
    }

    public MediaExtractException(String message, Throwable cause) {
        super(message, cause);
    }
}
