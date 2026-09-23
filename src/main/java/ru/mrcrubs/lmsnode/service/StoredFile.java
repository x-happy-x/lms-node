package ru.mrcrubs.lmsnode.service;

import java.nio.file.Path;

public record StoredFile(
        Path path,
        long sizeBytes
) {
}
