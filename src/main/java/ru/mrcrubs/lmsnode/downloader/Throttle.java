package ru.mrcrubs.lmsnode.downloader;

import java.util.concurrent.CancellationException;

/**
 * Keeps a transfer at or below the context's speed limit. The limit is read on every
 * chunk, so it can change mid-download; a change restarts the measurement window.
 */
final class Throttle {
    private static final long MIN_CHUNK = 4 * 1024;

    private final DownloadExecutionContext context;
    private long windowLimit;
    private long windowStartNanos;
    private long windowBytes;

    Throttle(DownloadExecutionContext context) {
        this.context = context;
        restart(context.speedLimit());
    }

    /** Largest read that keeps the pause after it short (about a quarter second). */
    int chunkSize(int bufferSize) {
        long limit = context.speedLimit();
        if (limit <= 0) {
            return bufferSize;
        }
        return (int) Math.max(MIN_CHUNK, Math.min(bufferSize, limit / 4));
    }

    /** Accounts for bytes just transferred and sleeps as long as needed to stay under the limit. */
    void onBytes(long bytes) throws InterruptedException {
        long limit = context.speedLimit();
        if (limit != windowLimit) {
            restart(limit);
        }
        if (limit <= 0) {
            return;
        }
        windowBytes += bytes;
        long dueNanos = windowStartNanos + windowBytes * 1_000_000_000L / limit;
        long waitNanos;
        while ((waitNanos = dueNanos - System.nanoTime()) > 0) {
            if (context.isCanceled()) {
                throw new CancellationException("Download canceled");
            }
            Thread.sleep(Math.max(1, Math.min(100, waitNanos / 1_000_000)));
            if (context.speedLimit() != windowLimit) {
                return; // new limit applies from the next chunk
            }
        }
    }

    private void restart(long limit) {
        windowLimit = limit;
        windowStartNanos = System.nanoTime();
        windowBytes = 0;
    }
}
