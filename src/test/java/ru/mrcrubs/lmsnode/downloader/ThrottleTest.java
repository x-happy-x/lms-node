package ru.mrcrubs.lmsnode.downloader;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottleTest {

    @Test
    void keepsTransferUnderTheLimit() throws Exception {
        DownloadExecutionContext context = new DownloadExecutionContext();
        context.setSpeedLimit(100_000L);
        Throttle throttle = new Throttle(context);

        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            throttle.onBytes(10_000); // 100 KB at 100 KB/s ≈ 1 s
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 900 && elapsedMs < 1600, "elapsed " + elapsedMs + " ms");
        assertEquals(25_000, throttle.chunkSize(64 * 1024), "chunk is a quarter second of transfer");
    }

    @Test
    void noLimitMeansNoWaitAndFullChunks() throws Exception {
        DownloadExecutionContext context = new DownloadExecutionContext();
        Throttle throttle = new Throttle(context);
        long start = System.nanoTime();
        throttle.onBytes(50_000_000);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 50);
        assertEquals(65536, throttle.chunkSize(65536));
    }

    @Test
    void limitChangeAppliesImmediatelyAndCancelStopsWaiting() throws Exception {
        DownloadExecutionContext context = new DownloadExecutionContext();
        context.setSpeedLimit(1_000L);
        Throttle throttle = new Throttle(context);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // test helper
            }
            context.setSpeedLimit(null);
        });
        long start = System.nanoTime();
        throttle.onBytes(10_000); // would take 10 s at 1 KB/s
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500, "lifting the limit should end the wait");

        context.setSpeedLimit(1_000L);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // test helper
            }
            context.cancel();
        });
        assertThrows(CancellationException.class, () -> throttle.onBytes(10_000));
    }
}
