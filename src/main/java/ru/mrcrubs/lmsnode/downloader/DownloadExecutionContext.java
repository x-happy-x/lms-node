package ru.mrcrubs.lmsnode.downloader;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DownloadExecutionContext {
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>(() -> {
    });

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
