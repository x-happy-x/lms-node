package ru.mrcrubs.lmsnode.downloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs an external downloader (aria2c, yt-dlp) and streams its output line by line.
 *
 * <p>Stopping is graceful: the process tree first gets SIGTERM so the tool can flush
 * its resume state (aria2c writes the {@code .aria2} control file, yt-dlp keeps
 * {@code .part} files), and is killed only if it does not exit in time.
 */
final class ExternalProcess {
    static final long DEFAULT_STOP_TIMEOUT_SECONDS = 15;

    private ExternalProcess() {
    }

    /**
     * @return process exit code
     * @throws CancellationException if the context was canceled while the process ran
     */
    static int run(List<String> command,
                   Path workDir,
                   DownloadExecutionContext context,
                   Consumer<String> lineConsumer) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        Process process = builder.start();
        // Stop asynchronously: cancel() is called from request threads and must not block on the tool.
        context.registerCancelAction(() -> Thread.ofVirtual()
                .name("stop-" + command.getFirst())
                .start(() -> stopGracefully(process, DEFAULT_STOP_TIMEOUT_SECONDS)));

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (context.isCanceled()) {
                    // Keep draining output so the tool is not blocked on a full pipe while it saves state.
                    continue;
                }
                if (!line.isBlank()) {
                    lineConsumer.accept(line);
                }
            }
        } catch (IOException ex) {
            if (!context.isCanceled()) {
                throw ex;
            }
        } finally {
            if (process.isAlive() && !context.isCanceled()) {
                stopGracefully(process, DEFAULT_STOP_TIMEOUT_SECONDS);
            }
        }

        int exitCode = process.waitFor();
        if (context.isCanceled()) {
            throw new CancellationException(command.getFirst() + " stopped");
        }
        return exitCode;
    }

    /**
     * Signals via {@link ProcessHandle}: {@link Process#destroy()} would also close the
     * output pipe, losing the tool's last lines (aria2c's result table, yt-dlp errors).
     */
    static void stopGracefully(Process process, long timeoutSeconds) {
        ProcessHandle handle = process.toHandle();
        List<ProcessHandle> descendants = handle.descendants().toList();
        handle.destroy();
        descendants.forEach(ProcessHandle::destroy);
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                handle.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            handle.destroyForcibly();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
}
