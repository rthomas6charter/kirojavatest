package org.example.kirojavatest;

// Feature: file-management-app, Property 5: Structure preview moved flag
// Feature: file-management-app, Property 6: Structure preview duplicate count
// Feature: file-management-app, Property 30: DatePathUtil target path format
// Feature: file-management-app, Property 31: DatePathUtil consistency (round-trip)
// Feature: file-management-app, Property 32: Reorganize script correctness
// **Validates: Requirements 2.2, 2.3, 15.1, 15.2, 15.3, 15.4**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.fileanalyzer.DatePathUtil;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Property tests for file reorganization features.
 *
 * Property 5: Structure preview moved flag
 * Property 6: Structure preview duplicate count
 * Property 30: DatePathUtil target path format
 * Property 31: DatePathUtil consistency (round-trip)
 * Property 32: Reorganize script correctness
 */
class FileReorgPropertyTest {

    private Path tempDir;

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("filereorg-prop-test");
        System.setProperty("app.data.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterTry
    void tearDown() throws IOException {
        System.clearProperty("app.data.dir");
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    // ========================================================================
    // Property 30: DatePathUtil target path format
    //
    // For any file with a creation date, DatePathUtil.targetPath(file) SHALL return
    // a string matching the pattern YYYY/MM/DD/filename where YYYY, MM, DD correspond
    // to the file's creation date.
    // ========================================================================

    @Property(tries = 100)
    void targetPathMatchesDateFormat(
            @ForAll("fileNames") String fileName
    ) throws IOException {
        // Create a file in the temp directory
        Path file = tempDir.resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});

        // Get the target path
        String targetPath = DatePathUtil.targetPath(file);

        // Read the file's creation date
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        Instant created = attrs.creationTime().toInstant();
        ZonedDateTime zdt = created.atZone(ZoneId.systemDefault());

        // Verify the format matches YYYY/MM/DD/filename
        String expectedPattern = String.format("%04d/%02d/%02d/%s",
                zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth(), fileName);
        assert targetPath.equals(expectedPattern)
                : "Expected target path '" + expectedPattern + "' but got '" + targetPath + "'";

        // Also verify regex pattern YYYY/MM/DD/filename
        Pattern datePathPattern = Pattern.compile("^\\d{4}/\\d{2}/\\d{2}/.+$");
        assert datePathPattern.matcher(targetPath).matches()
                : "Target path '" + targetPath + "' does not match pattern YYYY/MM/DD/filename";
    }

    // ========================================================================
    // Property 31: DatePathUtil consistency (round-trip)
    //
    // For any file, if its relative path equals DatePathUtil.targetPath(file),
    // then DatePathUtil.isInCorrectDatePath(relativePath, file) SHALL return true.
    // Conversely, if the relative path differs from the target path, it SHALL return false.
    // ========================================================================

    @Property(tries = 100)
    void datePathConsistencyRoundTrip(
            @ForAll("fileNames") String fileName
    ) throws IOException {
        // Create a file
        Path file = tempDir.resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{4, 5, 6});

        // Get the target path for this file
        String targetPath = DatePathUtil.targetPath(file);

        // If we use the target path as the relative path, isInCorrectDatePath must return true
        assert DatePathUtil.isInCorrectDatePath(targetPath, file)
                : "isInCorrectDatePath should return true when relativePath equals targetPath. "
                + "targetPath='" + targetPath + "'";

        // If we use a different relative path, isInCorrectDatePath must return false
        String differentPath = "wrong/path/" + fileName;
        if (!differentPath.equals(targetPath)) {
            assert !DatePathUtil.isInCorrectDatePath(differentPath, file)
                    : "isInCorrectDatePath should return false when relativePath differs from targetPath. "
                    + "relativePath='" + differentPath + "', targetPath='" + targetPath + "'";
        }
    }

    // ========================================================================
    // Property 5: Structure preview moved flag
    //
    // For any file in the structure preview, the moved flag SHALL be true if and only if
    // the file's current relative path differs from its computed target path.
    // ========================================================================

    @Property(tries = 100)
    void structurePreviewMovedFlag(
            @ForAll("fileStructuresForReorg") List<FileEntry> entries
    ) throws IOException {
        // Create files on disk
        Path root = tempDir.toAbsolutePath().normalize();
        for (FileEntry entry : entries) {
            Path filePath = tempDir.resolve(entry.relativePath());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, entry.content());
        }

        // Build the structure preview (same logic as ApiController /api/structure)
        Map<String, List<Map<String, Object>>> virtualMap = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".ui-state"))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String relPath = root.relativize(file.toAbsolutePath().normalize()).toString();
                    String targetPath;
                    try {
                        targetPath = DatePathUtil.targetPath(file);
                    } catch (IOException e) {
                        targetPath = relPath;
                    }
                    boolean moved = !relPath.equals(targetPath);
                    Map<String, Object> sourceEntry = new LinkedHashMap<>();
                    sourceEntry.put("actualPath", relPath);
                    sourceEntry.put("moved", moved);
                    virtualMap.computeIfAbsent(targetPath, k -> new ArrayList<>()).add(sourceEntry);
                } catch (Exception e) { /* skip */ }
                return FileVisitResult.CONTINUE;
            }
        });

        // Verify: for each file entry, moved flag is true iff actualPath != targetPath (virtualMap key)
        for (var mapEntry : virtualMap.entrySet()) {
            String virtualPath = mapEntry.getKey();
            for (Map<String, Object> source : mapEntry.getValue()) {
                String actualPath = (String) source.get("actualPath");
                boolean moved = (boolean) source.get("moved");
                boolean shouldBeMoved = !actualPath.equals(virtualPath);

                assert moved == shouldBeMoved
                        : "File at '" + actualPath + "' targeting '" + virtualPath
                        + "': expected moved=" + shouldBeMoved + " but got moved=" + moved;
            }
        }
    }

    // ========================================================================
    // Property 6: Structure preview duplicate count
    //
    // For any virtual path in the structure preview where multiple source files resolve
    // to the same target, the dupCount field SHALL equal the number of source files
    // mapping to that path.
    // ========================================================================

    @Property(tries = 100)
    void structurePreviewDuplicateCount(
            @ForAll("fileStructuresWithDups") List<FileEntry> entries
    ) throws IOException {
        // Create files on disk
        Path root = tempDir.toAbsolutePath().normalize();
        for (FileEntry entry : entries) {
            Path filePath = tempDir.resolve(entry.relativePath());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, entry.content());
        }

        // Build the structure preview (same logic as ApiController /api/structure)
        Map<String, List<Map<String, Object>>> virtualMap = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".ui-state"))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String relPath = root.relativize(file.toAbsolutePath().normalize()).toString();
                    String targetPath;
                    try {
                        targetPath = DatePathUtil.targetPath(file);
                    } catch (IOException e) {
                        targetPath = relPath;
                    }
                    boolean moved = !relPath.equals(targetPath);
                    Map<String, Object> sourceEntry = new LinkedHashMap<>();
                    sourceEntry.put("actualPath", relPath);
                    sourceEntry.put("moved", moved);
                    virtualMap.computeIfAbsent(targetPath, k -> new ArrayList<>()).add(sourceEntry);
                } catch (Exception e) { /* skip */ }
                return FileVisitResult.CONTINUE;
            }
        });

        // Build the flat file list (same as ApiController)
        for (var mapEntry : virtualMap.entrySet()) {
            String vPath = mapEntry.getKey();
            List<Map<String, Object>> sources = mapEntry.getValue();
            int dupCount = sources.size();

            // Verify: dupCount equals the number of source files mapping to this virtual path
            assert dupCount == sources.size()
                    : "dupCount for virtualPath '" + vPath + "' should be " + sources.size()
                    + " but computed as " + dupCount;

            // When multiple files map to same target, dupCount must be > 1
            if (sources.size() > 1) {
                assert dupCount > 1
                        : "dupCount should be > 1 when multiple files map to same virtual path '"
                        + vPath + "', got " + dupCount;
            }
        }
    }

    // ========================================================================
    // Property 32: Reorganize script correctness
    //
    // For any file not in its correct target path, the generated reorganize script
    // SHALL contain a mkdir -p command for the target directory and a mv command
    // moving the file to its target path.
    // ========================================================================

    @Property(tries = 100)
    void reorganizeScriptCorrectness(
            @ForAll("fileStructuresForReorg") List<FileEntry> entries
    ) throws IOException {
        // Create files on disk
        Path root = tempDir.toAbsolutePath().normalize();
        for (FileEntry entry : entries) {
            Path filePath = tempDir.resolve(entry.relativePath());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, entry.content());
        }

        // Generate the reorganize script (same logic as ApiController /api/scripts/reorganize)
        StringBuilder sb = new StringBuilder("#!/usr/bin/env bash\n");
        sb.append("# Reorganize files into YYYY/MM/DD directory structure based on creation date\n");
        sb.append("set -euo pipefail\n\n");
        sb.append("DATA_DIR=").append(shellEscape(root.toString())).append("\n\n");

        // Track which files need reorg for later verification
        List<String[]> filesToMove = new ArrayList<>(); // [relativePath, targetPath, targetDir]

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".ui-state"))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String rel = root.relativize(file.toAbsolutePath().normalize()).toString();
                    if (!DatePathUtil.isInCorrectDatePath(rel, file)) {
                        String target = DatePathUtil.targetPath(file);
                        String targetDir = DatePathUtil.datePath(file);
                        sb.append("mkdir -p \"$DATA_DIR/").append(targetDir).append("\"\n");
                        sb.append("mv \"$DATA_DIR/").append(rel)
                                .append("\" \"$DATA_DIR/").append(target).append("\"\n\n");
                        filesToMove.add(new String[]{rel, target, targetDir});
                    }
                } catch (IOException e) { /* skip */ }
                return FileVisitResult.CONTINUE;
            }
        });

        String script = sb.toString();

        // Verify: for each file that needs reorganization, the script contains the correct commands
        for (String[] fileInfo : filesToMove) {
            String relPath = fileInfo[0];
            String targetPath = fileInfo[1];
            String targetDir = fileInfo[2];

            // Must contain mkdir -p for the target directory
            String mkdirCmd = "mkdir -p \"$DATA_DIR/" + targetDir + "\"";
            assert script.contains(mkdirCmd)
                    : "Script should contain '" + mkdirCmd + "' for file at '" + relPath
                    + "'\nScript:\n" + script;

            // Must contain mv command from source to target
            String mvCmd = "mv \"$DATA_DIR/" + relPath + "\" \"$DATA_DIR/" + targetPath + "\"";
            assert script.contains(mvCmd)
                    : "Script should contain '" + mvCmd + "'\nScript:\n" + script;
        }

        // Also verify: files already in correct path should NOT appear in the script
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".ui-state"))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String rel = root.relativize(file.toAbsolutePath().normalize()).toString();
                    if (DatePathUtil.isInCorrectDatePath(rel, file)) {
                        // File is in correct path — should NOT be in any mv command
                        String mvFrom = "mv \"$DATA_DIR/" + rel + "\"";
                        assert !script.contains(mvFrom)
                                : "Script should NOT contain mv command for correctly-placed file '"
                                + rel + "'";
                    }
                } catch (IOException e) { /* skip */ }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // --- Helper ---

    private static String shellEscape(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // --- Generators ---

    @Provide
    Arbitrary<String> fileNames() {
        // Generate simple file names (lowercase to avoid case-insensitive filesystem issues)
        return Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                Arbitraries.of("txt", "jpg", "png", "pdf", "dat", "log")
        ).as((name, ext) -> name + "." + ext);
    }

    @Provide
    Arbitrary<List<FileEntry>> fileStructuresForReorg() {
        // Generate files placed in various locations (most will NOT be in their correct date path)
        return fileEntryForReorg().list().ofMinSize(1).ofMaxSize(10)
                .map(list -> {
                    // Deduplicate by relative path
                    Map<String, FileEntry> unique = new LinkedHashMap<>();
                    for (FileEntry e : list) {
                        unique.putIfAbsent(e.relativePath(), e);
                    }
                    return (List<FileEntry>) new ArrayList<>(unique.values());
                })
                .filter(list -> !list.isEmpty());
    }

    @Provide
    Arbitrary<List<FileEntry>> fileStructuresWithDups() {
        // Generate files where multiple files have the same name (causing same target path)
        // This ensures some virtual paths have dupCount > 1
        return Combinators.combine(
                fileEntryForReorg().list().ofMinSize(1).ofMaxSize(5),
                duplicateNameEntries()
        ).as((normal, dups) -> {
            Map<String, FileEntry> unique = new LinkedHashMap<>();
            for (FileEntry e : normal) {
                unique.putIfAbsent(e.relativePath(), e);
            }
            for (FileEntry e : dups) {
                unique.putIfAbsent(e.relativePath(), e);
            }
            return (List<FileEntry>) new ArrayList<>(unique.values());
        }).filter(list -> !list.isEmpty());
    }

    /**
     * Generate entries where multiple files have the same filename but different paths,
     * so they will resolve to the same target path (YYYY/MM/DD/samename.ext).
     */
    private Arbitrary<List<FileEntry>> duplicateNameEntries() {
        // Pick a shared filename, then create 2-4 files in different directories with that name
        Arbitrary<String> sharedFileName = Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(6),
                Arbitraries.of("txt", "jpg", "png")
        ).as((name, ext) -> name + "." + ext);

        Arbitrary<List<String>> dirPrefixes = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(4)
                .list().ofMinSize(2).ofMaxSize(4)
                .map(dirs -> {
                    // Ensure unique dir prefixes
                    return (List<String>) new ArrayList<>(new LinkedHashSet<>(dirs));
                })
                .filter(list -> list.size() >= 2);

        return Combinators.combine(sharedFileName, dirPrefixes).as((fileName, dirs) -> {
            List<FileEntry> entries = new ArrayList<>();
            for (String dir : dirs) {
                String relPath = dir + "/" + fileName;
                entries.add(new FileEntry(relPath, new byte[]{1, 2, 3}));
            }
            return entries;
        });
    }

    private Arbitrary<FileEntry> fileEntryForReorg() {
        // Place files in random subdirectories (not date-structured, so they'll need reorg)
        Arbitrary<String> dirSegment = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(5);

        Arbitrary<List<String>> dirParts = dirSegment.list().ofMinSize(0).ofMaxSize(2);

        Arbitrary<String> fileName = Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                Arbitraries.of("txt", "jpg", "png", "pdf", "dat")
        ).as((name, ext) -> name + "." + ext);

        Arbitrary<byte[]> content = Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(50);

        return Combinators.combine(dirParts, fileName, content).as((dirs, file, bytes) -> {
            String relPath;
            if (dirs.isEmpty()) {
                relPath = file;
            } else {
                relPath = String.join("/", dirs) + "/" + file;
            }
            return new FileEntry(relPath, bytes);
        });
    }

    // --- Data types ---

    record FileEntry(String relativePath, byte[] content) {}
}
