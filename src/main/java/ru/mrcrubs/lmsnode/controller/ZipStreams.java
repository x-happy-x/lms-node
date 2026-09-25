package ru.mrcrubs.lmsnode.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams a directory as a ZIP without temporary files. */
final class ZipStreams {
    private ZipStreams() {
    }

    static void writeDirectory(Path root, OutputStream out) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        ZipOutputStream zip = new ZipOutputStream(out);
        // Downloads are mostly media: compression costs CPU and saves nothing.
        zip.setLevel(Deflater.NO_COMPRESSION);
        String prefix = root.getFileName().toString() + "/";
        for (Path file : files) {
            ZipEntry entry = new ZipEntry(prefix + root.relativize(file).toString().replace('\\', '/'));
            entry.setLastModifiedTime(Files.getLastModifiedTime(file));
            zip.putNextEntry(entry);
            Files.copy(file, zip);
            zip.closeEntry();
        }
        zip.finish();
    }
}
