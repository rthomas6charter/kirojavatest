package org.example.kirojavatest.fileanalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Utility class for analyzing files within a directory tree. */
public class FileAnalyzer {

    /**
     * Find groups of duplicate files under the given root directory.
     * Files are compared by size first (cheap), then by SHA-256 checksum (definitive).
     */
    public static List<DuplicateGroup> findDuplicateFiles(Path root) throws IOException {
        // Pass 1: group files by size
        Map<Long, List<Path>> bySize = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".ui-state")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    bySize.computeIfAbsent(attrs.size(), k -> new ArrayList<>()).add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        // Pass 2: for size groups with more than one file, compute checksums
        List<DuplicateGroup> duplicates = new ArrayList<>();
        for (var entry : bySize.entrySet()) {
            List<Path> paths = entry.getValue();
            if (paths.size() < 2) continue;

            Map<String, List<FileInfo>> byChecksum = new LinkedHashMap<>();
            for (Path p : paths) {
                try {
                    String checksum = computeChecksum(p);
                    FileInfo info = new FileInfo(p, p.getFileName().toString(), entry.getKey(), checksum);
                    byChecksum.computeIfAbsent(checksum, k -> new ArrayList<>()).add(info);
                } catch (IOException e) {
                    // skip unreadable files
                }
            }

            for (var csEntry : byChecksum.entrySet()) {
                if (csEntry.getValue().size() > 1) {
                    duplicates.add(new DuplicateGroup(
                            csEntry.getKey(), entry.getKey(), csEntry.getValue()));
                }
            }
        }
        return duplicates;
    }

    /**
     * Calculate total size, file count, and directory count for a directory tree.
     */
    public static DirectorySummary calculateDirectoryTotalSize(Path root) throws IOException {
        long[] totalSize = {0};
        int[] fileCount = {0};
        int[] dirCount = {0};

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    totalSize[0] += attrs.size();
                    fileCount[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    dirCount[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return new DirectorySummary(root.toString(), totalSize[0], fileCount[0], dirCount[0]);
    }

    /** Compute SHA-256 checksum of a file. */
    public static String computeChecksum(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
