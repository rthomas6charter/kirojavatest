package org.example.kirojavatest.fileanalyzer;

/** Summary statistics for a directory tree. */
public record DirectorySummary(
        String path,
        long totalSize,
        int fileCount,
        int directoryCount
) {}
