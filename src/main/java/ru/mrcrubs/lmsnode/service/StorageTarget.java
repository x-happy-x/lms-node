package ru.mrcrubs.lmsnode.service;

import java.nio.file.Path;

public record StorageTarget(
        Path path,
        long freeBytes,
        long totalBytes,
        boolean writable,
        Boolean canFit
) {
}
