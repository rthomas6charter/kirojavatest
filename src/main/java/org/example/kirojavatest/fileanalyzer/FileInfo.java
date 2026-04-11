package org.example.kirojavatest.fileanalyzer;

import java.nio.file.Path;

/** Lightweight data object representing a file and its key attributes. */
public record FileInfo(
        Path path,
        String name,
        long size,
        String checksum
) {
    public String relativePath(Path root) {
        return root.relativize(path).toString();
    }
}
