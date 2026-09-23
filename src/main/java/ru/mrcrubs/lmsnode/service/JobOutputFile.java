package ru.mrcrubs.lmsnode.service;

import java.io.InputStream;
import java.nio.file.Path;

public record JobOutputFile(
        Path path,
        InputStream stream,
        long sizeBytes
) {
}
