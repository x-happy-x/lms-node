package ru.mrcrubs.lmsnode.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeEnvironmentChecker {
    private static final Logger log = LoggerFactory.getLogger(RuntimeEnvironmentChecker.class);

    private final NodeProperties nodeProperties;
    private final String ytdlpBinary;
    private final String aria2cBinary;

    public RuntimeEnvironmentChecker(NodeProperties nodeProperties,
                                     @Value("${node.tools.ytdlp:yt-dlp}") String ytdlpBinary,
                                     @Value("${node.tools.aria2c:aria2c}") String aria2cBinary) {
        this.nodeProperties = nodeProperties;
        this.ytdlpBinary = ytdlpBinary;
        this.aria2cBinary = aria2cBinary;
    }

    @PostConstruct
    public void check() {
        checkDownloadDirectory();
        checkToolAvailability("yt-dlp", ytdlpBinary);
        checkToolAvailability("aria2c", aria2cBinary);
    }

    private void checkDownloadDirectory() {
        Path downloadDir = Path.of(nodeProperties.getDownloadDir());
        try {
            Files.createDirectories(downloadDir);
        } catch (Exception ex) {
            log.warn("Failed to create download directory {}: {}", downloadDir, ex.getMessage());
            return;
        }

        if (!Files.isDirectory(downloadDir)) {
            log.warn("Configured download path is not a directory: {}", downloadDir);
            return;
        }
        if (!Files.isWritable(downloadDir)) {
            log.warn("Download directory is not writable: {}", downloadDir);
            return;
        }

        log.info("Download directory is ready: {}", downloadDir);
    }

    private void checkToolAvailability(String toolName, String configuredBinary) {
        if (isExecutableOnPath(configuredBinary)) {
            log.info("{} binary is available: {}", toolName, configuredBinary);
        } else {
            log.warn("{} binary is not available: {}", toolName, configuredBinary);
        }
    }

    boolean isExecutableOnPath(String commandOrPath) {
        Path direct = Path.of(commandOrPath);
        if (direct.isAbsolute() || commandOrPath.contains("/") || commandOrPath.contains("\\")) {
            return Files.isExecutable(direct);
        }

        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }

        for (String entry : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(entry).resolve(commandOrPath);
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
