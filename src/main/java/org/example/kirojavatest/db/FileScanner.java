package org.example.kirojavatest.db;

import org.example.kirojavatest.AppConfig;
import org.example.kirojavatest.fileanalyzer.DatePathUtil;
import org.example.kirojavatest.fileanalyzer.FileAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/**
 * Scans the data directory, populates the file database,
 * and computes duplicate pairings and reorganization targets.
 */
public class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    private final FileDatabase db;

    public FileScanner(FileDatabase db) {
        this.db = db;
    }

    /**
     * Perform a full scan of the data directory.
     * Updates file_info records, removes stale entries,
     * recomputes duplicate pairs and target paths.
     */
    public void scan() {
        String dataDir = AppConfig.get("app.data.dir", "");
        if (dataDir.isEmpty()) return;
        Path root = Paths.get(dataDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;

        String scanTimestamp = Instant.now().toString();
        log.info("Starting file scan of {}", root);

        // Pass 1: walk and upsert all files
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (name.equals(".ui-state")) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    try {
                        String relPath = root.relativize(file.toAbsolutePath().normalize()).toString();
                        String fileName = file.getFileName().toString();
                        long size = attrs.size();
                        String created = attrs.creationTime().toInstant().toString();
                        String modified = attrs.lastModifiedTime().toInstant().toString();

                        // Check if we already have this file with same size+modified
                        Optional<FileRecord> existing = db.getByPath(relPath);
                        String checksum;
                        String id;
                        if (existing.isPresent()
                                && existing.get().fileSize() == size
                                && modified.equals(existing.get().modifiedAt())) {
                            // File unchanged — reuse existing checksum and ID
                            checksum = existing.get().checksum();
                            id = existing.get().id();
                        } else {
                            // New or changed file — compute checksum
                            checksum = FileAnalyzer.computeChecksum(file);
                            id = existing.map(FileRecord::id).orElse(UUID.randomUUID().toString());
                        }

                        // Compute target path
                        String targetPath = null;
                        try {
                            if (!DatePathUtil.isInCorrectDatePath(relPath, file)) {
                                targetPath = DatePathUtil.targetPath(file);
                            }
                        } catch (IOException e) { /* skip */ }

                        db.upsertFile(new FileRecord(
                                id, relPath, fileName, size, checksum,
                                created, modified, targetPath, scanTimestamp
                        ));
                    } catch (IOException e) {
                        log.warn("Failed to process file: {}", file, e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("File walk failed", e);
        }

        // Pass 2: remove files no longer present
        int removed = db.removeStale(scanTimestamp);
        if (removed > 0) log.info("Removed {} stale file records", removed);

        // Pass 3: recompute duplicate pairs
        computeDuplicates();

        log.info("File scan complete");
    }

    private void computeDuplicates() {
        db.clearDuplicates();
        List<FileRecord> allFiles = db.getAllFiles();

        // Group by checksum
        Map<String, List<FileRecord>> byChecksum = new LinkedHashMap<>();
        for (FileRecord f : allFiles) {
            if (f.checksum() != null && !f.checksum().isEmpty()) {
                byChecksum.computeIfAbsent(f.checksum(), k -> new ArrayList<>()).add(f);
            }
        }

        // Create pairs for groups with 2+ files
        for (var group : byChecksum.values()) {
            if (group.size() < 2) continue;
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    db.addDuplicatePair(group.get(i).id(), group.get(j).id());
                }
            }
        }
    }
}
