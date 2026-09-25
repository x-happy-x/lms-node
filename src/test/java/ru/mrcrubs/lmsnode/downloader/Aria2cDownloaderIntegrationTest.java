package ru.mrcrubs.lmsnode.downloader;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real aria2c binary (skipped when it is not installed): pause via cancel,
 * then a second run must continue from the partial file with a Range request.
 */
class Aria2cDownloaderIntegrationTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void secondRunShouldResumeAfterStop(@TempDir Path tempDir) throws Exception {
        assumeTrue(isOnPath("aria2c"), "aria2c is not installed");
        byte[] content = new byte[2 * 1024 * 1024];
        new Random(7).nextBytes(content);
        List<String> ranges = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/big.bin", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            ranges.add(String.valueOf(range));
            int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.indexOf('-')));
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            if (range != null) {
                exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (content.length - 1) + "/" + content.length);
                exchange.sendResponseHeaders(206, content.length - start);
            } else {
                exchange.sendResponseHeaders(200, content.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                for (int offset = start; offset < content.length; offset += 64 * 1024) {
                    out.write(content, offset, Math.min(64 * 1024, content.length - offset));
                    out.flush();
                    Thread.sleep(40);
                }
            } catch (Exception ignored) {
                // Client went away (the stop under test).
            }
        });
        server.start();

        Aria2cDownloader downloader = new Aria2cDownloader("aria2c");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/big.bin";
        DownloadRequest request = new DownloadRequest(UUID.randomUUID(), null, url, tempDir);
        Path output = tempDir.resolve("big.bin");

        DownloadExecutionContext first = new DownloadExecutionContext();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Thread run = Thread.ofVirtual().start(() -> {
            try {
                downloader.download(request, first, update -> {
                });
            } catch (Throwable ex) {
                firstError.set(ex);
            }
        });
        long deadline = System.currentTimeMillis() + 10_000;
        while ((!Files.exists(output) || Files.size(output) < 300_000) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        first.cancel();
        run.join(20_000);

        if (!(firstError.get() instanceof CancellationException)) {
            throw new AssertionError("first run should end with CancellationException", firstError.get());
        }
        assertTrue(Files.exists(tempDir.resolve("big.bin.aria2")), "aria2c should keep its control file on stop");
        long partialSize = Files.size(output);
        assertTrue(partialSize > 0 && partialSize < content.length, "partial size " + partialSize);

        DownloadResult result = downloader.download(request, new DownloadExecutionContext(), update -> {
        });

        assertEquals(output.toString(), result.outputPath());
        assertArrayEquals(content, Files.readAllBytes(output));
        assertTrue(ranges.stream().anyMatch(range -> range.startsWith("bytes=") && !range.startsWith("bytes=0-")),
                "second run should request a range: " + ranges);
    }

    private static boolean isOnPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(entry).resolve(binary))) {
                return true;
            }
        }
        return false;
    }
}
