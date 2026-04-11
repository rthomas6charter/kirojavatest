package org.example.kirojavatest.fileanalyzer;

import java.util.List;

/** A group of files that are duplicates of each other (same content). */
public record DuplicateGroup(
        String checksum,
        long fileSize,
        List<FileInfo> files
) {
    public int count() {
        return files.size();
    }

    /** Total wasted space (all copies minus one). */
    public long wastedBytes() {
        return fileSize * (files.size() - 1);
    }
}
