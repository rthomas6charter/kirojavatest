package org.example.kirojavatest;

// Feature: file-management-app, Property 27: Duplicate detection by checksum
// Feature: file-management-app, Property 28: Duplicate group wasted bytes
// **Validates: Requirements 14.1, 14.2**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.fileanalyzer.DuplicateGroup;
import org.example.kirojavatest.fileanalyzer.FileAnalyzer;
import org.example.kirojavatest.fileanalyzer.FileInfo;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Property tests for duplicate detection.
 *
 * Property 27: For any set of files, two files SHALL be in the same duplicate group
 * if and only if they have identical SHA-256 checksums.
 *
 * Property 28: For any duplicate group with N files of size S, the wasted bytes
 * SHALL equal (N - 1) * S.
 */
class DuplicateDetectionPropertyTest {

    private Path tempDir;

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("dup-detect-prop-test");
    }

    @AfterTry
    void tearDown() throws IOException {
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

    // --- Property 27: Duplicate detection by checksum ---

    @Property(tries = 100)
    void duplicateDetectionByChecksum(
            @ForAll("fileGroupsWithDuplicates") List<FileGroup> fileGroups
    ) throws IOException {
        // Create all files on disk
        Map<String, List<String>> expectedGroupsByContent = new HashMap<>();
        for (FileGroup group : fileGroups) {
            for (FileEntry entry : group.entries()) {
                Path filePath = tempDir.resolve(entry.relativePath());
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, group.content());

                // Track which content maps to which paths
                String contentKey = Arrays.toString(group.content());
                expectedGroupsByContent.computeIfAbsent(contentKey, k -> new ArrayList<>())
                        .add(entry.relativePath());
            }
        }

        // Run duplicate detection
        List<DuplicateGroup> duplicates = FileAnalyzer.findDuplicateFiles(tempDir);

        // Verify: files with the same checksum are in the same group
        // Build a map of path -> group checksum from the results
        Map<String, String> pathToGroupChecksum = new HashMap<>();
        for (DuplicateGroup dg : duplicates) {
            for (FileInfo fi : dg.files()) {
                String relPath = tempDir.relativize(fi.path()).toString();
                pathToGroupChecksum.put(relPath, dg.checksum());
            }
        }

        // All files within a DuplicateGroup must have the same checksum
        for (DuplicateGroup dg : duplicates) {
            String groupChecksum = dg.checksum();
            for (FileInfo fi : dg.files()) {
                assert fi.checksum().equals(groupChecksum)
                        : "File " + fi.path() + " has checksum " + fi.checksum()
                        + " but group checksum is " + groupChecksum;
            }
        }

        // Files that share the same content should be in the same group
        for (var entry : expectedGroupsByContent.entrySet()) {
            List<String> paths = entry.getValue();
            if (paths.size() < 2) continue;

            // All paths with the same content should map to the same checksum group
            Set<String> checksums = paths.stream()
                    .map(pathToGroupChecksum::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            assert checksums.size() == 1
                    : "Files with identical content are not in the same duplicate group. "
                    + "Paths: " + paths + ", checksums found: " + checksums;
        }

        // Files with different content should NOT be in the same group
        // Collect all checksums reported in groups
        for (DuplicateGroup dg : duplicates) {
            // All files in this group should have the same actual content (same size at minimum)
            Set<Long> sizes = dg.files().stream()
                    .map(FileInfo::size)
                    .collect(Collectors.toSet());
            assert sizes.size() == 1
                    : "Files in duplicate group have different sizes: " + sizes;
        }
    }

    // --- Property 28: Duplicate group wasted bytes ---

    @Property(tries = 100)
    void duplicateGroupWastedBytes(
            @ForAll("fileGroupsWithDuplicates") List<FileGroup> fileGroups
    ) throws IOException {
        // Create all files on disk
        for (FileGroup group : fileGroups) {
            for (FileEntry entry : group.entries()) {
                Path filePath = tempDir.resolve(entry.relativePath());
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, group.content());
            }
        }

        // Run duplicate detection
        List<DuplicateGroup> duplicates = FileAnalyzer.findDuplicateFiles(tempDir);

        // Verify: for each group, wasted bytes == (N - 1) * S
        for (DuplicateGroup dg : duplicates) {
            int n = dg.count();
            long s = dg.fileSize();
            long expectedWasted = (long) (n - 1) * s;
            long actualWasted = dg.wastedBytes();

            assert actualWasted == expectedWasted
                    : "Duplicate group with checksum " + dg.checksum()
                    + " has " + n + " files of size " + s
                    + ". Expected wasted bytes: " + expectedWasted
                    + ", actual: " + actualWasted;

            // Also verify N >= 2 (a group must have at least 2 files)
            assert n >= 2
                    : "Duplicate group has fewer than 2 files: " + n;
        }
    }

    // --- Generators ---

    @Provide
    Arbitrary<List<FileGroup>> fileGroupsWithDuplicates() {
        // Generate 1-5 content groups, each with 2-4 files sharing the same content
        // plus 0-3 unique files (single-entry groups)
        Arbitrary<List<FileGroup>> duplicateGroups = fileGroupWithDuplicates()
                .list().ofMinSize(1).ofMaxSize(5);

        Arbitrary<List<FileGroup>> uniqueFiles = uniqueFileGroup()
                .list().ofMinSize(0).ofMaxSize(3);

        return Combinators.combine(duplicateGroups, uniqueFiles).as((dups, uniques) -> {
            List<FileGroup> all = new ArrayList<>(dups);
            all.addAll(uniques);
            // Deduplicate paths across all groups
            Set<String> usedPaths = new HashSet<>();
            List<FileGroup> result = new ArrayList<>();
            for (FileGroup group : all) {
                List<FileEntry> validEntries = new ArrayList<>();
                for (FileEntry entry : group.entries()) {
                    if (usedPaths.add(entry.relativePath())) {
                        validEntries.add(entry);
                    }
                }
                if (validEntries.size() >= 2 || (group.entries().size() == 1 && validEntries.size() == 1)) {
                    result.add(new FileGroup(group.content(), validEntries));
                }
            }
            return result;
        });
    }

    private Arbitrary<FileGroup> fileGroupWithDuplicates() {
        // A group of 2-4 files all sharing the same content (guaranteed duplicates)
        Arbitrary<byte[]> content = Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(200);

        Arbitrary<List<FileEntry>> entries = uniqueRelativePaths()
                .list().ofMinSize(2).ofMaxSize(4);

        return Combinators.combine(content, entries)
                .as(FileGroup::new);
    }

    private Arbitrary<FileGroup> uniqueFileGroup() {
        // A single file with unique content (no duplicate)
        Arbitrary<byte[]> content = Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(200);

        Arbitrary<List<FileEntry>> entries = uniqueRelativePaths()
                .list().ofSize(1);

        return Combinators.combine(content, entries)
                .as(FileGroup::new);
    }

    private Arbitrary<FileEntry> uniqueRelativePaths() {
        // Using lowercase only to avoid case-insensitive filesystem collisions (macOS)
        Arbitrary<String> fileNames = Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                Arbitraries.of("txt", "jpg", "png", "pdf", "dat")
        ).as((name, ext) -> name + "." + ext);

        Arbitrary<String> dirSegments = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(5);

        Arbitrary<List<String>> dirParts = dirSegments.list().ofMinSize(0).ofMaxSize(2);

        Arbitrary<String> paths = Combinators.combine(dirParts, fileNames).as((dirs, file) -> {
            if (dirs.isEmpty()) {
                return file;
            }
            return String.join("/", dirs) + "/" + file;
        });

        return paths.map(p -> new FileEntry(p));
    }

    // --- Data types ---

    record FileEntry(String relativePath) {}

    record FileGroup(byte[] content, List<FileEntry> entries) {}
}
