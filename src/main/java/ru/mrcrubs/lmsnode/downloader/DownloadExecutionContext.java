package ru.mrcrubs.lmsnode.downloader;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DownloadExecutionContext {
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong speedLimit = new java.util.concurrent.atomic.AtomicLong(0);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>(() -> {
    });

    /** Current speed limit in bytes per second, 0 = unlimited; may change while downloading. */
    public long speedLimit() {
        return speedLimit.get();
    }

    public void setSpeedLimit(Long bytesPerSecond) {
        speedLimit.set(bytesPerSecond == null || bytesPerSecond <= 0 ? 0 : bytesPerSecond);
    }

    public boolean isCanceled() {
        return canceled.get();
    }

    public void registerCancelAction(Runnable action) {
        cancelAction.set(action);
        if (isCanceled()) {
            action.run();
        }
    }

    public void cancel() {
        if (canceled.compareAndSet(false, true)) {
            cancelAction.get().run();
        }
    }
}
