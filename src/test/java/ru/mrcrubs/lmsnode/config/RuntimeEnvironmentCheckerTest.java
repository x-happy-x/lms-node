package ru.mrcrubs.lmsnode.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEnvironmentCheckerTest {

    @Test
    void shouldDetectExecutableByAbsolutePath() throws Exception {
        Path script = Files.createTempFile("checker-test", ".sh");
        try {
            Files.writeString(script, "#!/bin/sh\necho ok\n");
            script.toFile().setExecutable(true);

            RuntimeEnvironmentChecker checker = new RuntimeEnvironmentChecker(new NodeProperties(), "yt-dlp", "aria2c");
            assertTrue(checker.isExecutableOnPath(script.toString()));
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    void shouldReturnFalseForMissingAbsolutePath() {
        RuntimeEnvironmentChecker checker = new RuntimeEnvironmentChecker(new NodeProperties(), "yt-dlp", "aria2c");
        assertFalse(checker.isExecutableOnPath("/definitely/missing/tool"));
    }
}
