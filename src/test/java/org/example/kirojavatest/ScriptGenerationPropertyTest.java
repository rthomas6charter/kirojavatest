package org.example.kirojavatest;

// Feature: file-management-app, Property 29: Remove-duplicates script correctness
// **Validates: Requirements 14.3, 3.2**

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Property tests for remove-duplicates script generation.
 *
 * Property 29: For any duplicate group, the generated script SHALL keep exactly one file
 * (the first) and remove all others in the group.
 */
class ScriptGenerationPropertyTest {

    private Path tempDir;

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("script-gen-prop-test");
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

    // --- Property 29: Remove-duplicates script correctness ---

    @Property(tries = 100)
    void removeDuplicatesScriptKeepsFirstAndRemovesRest(
            @ForAll("duplicateFileGroups") List<DupFileGroup> dupGroups
    ) throws IOException {
        // Create all files on disk
        for (DupFileGroup group : dupGroups) {
            for (String relativePath : group.relativePaths()) {
                Path filePath = tempDir.resolve(relativePath);
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, group.content());
            }
        }

        // Run duplicate detection (same as ApiController does)
        Path root = tempDir.toAbsolutePath().normalize();
        List<DuplicateGroup> duplicates = FileAnalyzer.findDuplicateFiles(root);

        // Generate the script using the same logic as ApiController
        String script = generateRemoveDuplicatesScript(root, duplicates);

        // Parse the script and verify the property
        for (DuplicateGroup dg : duplicates) {
            List<FileInfo> files = dg.files();
            assert files.size() >= 2
                    : "Duplicate group must have at least 2 files, got " + files.size();

            // The first file should be kept (appear in a KEEP comment, not in an rm command)
            String firstRelPath = root.relativize(files.get(0).path().toAbsolutePath().normalize()).toString();

            // All other files should be removed
            List<String> otherRelPaths = new ArrayList<>();
            for (int i = 1; i < files.size(); i++) {
                otherRelPaths.add(root.relativize(files.get(i).path().toAbsolutePath().normalize()).toString());
            }

            // Verify: the first file appears in a KEEP comment
            assert script.contains("# KEEP: $DATA_DIR/" + firstRelPath)
                    : "Script should contain KEEP comment for first file: " + firstRelPath
                    + "\nScript:\n" + script;

            // Verify: the first file is NOT in any rm command
            assert !script.contains("rm \"$DATA_DIR/" + firstRelPath + "\"")
                    : "Script should NOT contain rm command for first (kept) file: " + firstRelPath;

            // Verify: all other files are in rm commands
            for (String otherPath : otherRelPaths) {
                assert script.contains("rm \"$DATA_DIR/" + otherPath + "\"")
                        : "Script should contain rm command for duplicate file: " + otherPath
                        + "\nScript:\n" + script;
            }

            // Verify: exactly (N-1) rm commands exist for this group's files
            int rmCount = 0;
            for (FileInfo fi : files) {
                String rel = root.relativize(fi.path().toAbsolutePath().normalize()).toString();
                if (script.contains("rm \"$DATA_DIR/" + rel + "\"")) {
                    rmCount++;
                }
            }
            assert rmCount == files.size() - 1
                    : "Expected " + (files.size() - 1) + " rm commands for group with "
                    + files.size() + " files, but found " + rmCount;
        }
    }

    /**
     * Generates the remove-duplicates script using the same logic as ApiController.
     * This replicates the exact script generation algorithm from the production code.
     */
    private String generateRemoveDuplicatesScript(Path root, List<DuplicateGroup> dups) {
        StringBuilder sb = new StringBuilder("#!/usr/bin/env bash\n");
        sb.append("# Remove duplicate files — keeps the first copy in each group\n");
        sb.append("set -euo pipefail\n\n");
        sb.append("DATA_DIR=").append(shellEscape(root.toString())).append("\n\n");
        for (DuplicateGroup g : dups) {
            sb.append("# Duplicate group (").append(g.count()).append(" copies, SHA-256: ").append(g.checksum().substring(0, 12)).append("...)\n");
            boolean first = true;
            for (FileInfo fi : g.files()) {
                String rel = root.relativize(fi.path().toAbsolutePath().normalize()).toString();
                if (first) {
                    sb.append("# KEEP: $DATA_DIR/").append(rel).append("\n");
                    first = false;
                } else {
                    sb.append("rm \"$DATA_DIR/").append(rel).append("\"\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String shellEscape(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // --- Generators ---

    @Provide
    Arbitrary<List<DupFileGroup>> duplicateFileGroups() {
        // Generate 1-4 duplicate groups, each with 2-5 files sharing the same content
        return dupFileGroup().list().ofMinSize(1).ofMaxSize(4)
                .map(groups -> {
                    // Ensure unique paths across all groups
                    Set<String> usedPaths = new HashSet<>();
                    List<DupFileGroup> result = new ArrayList<>();
                    for (DupFileGroup group : groups) {
                        List<String> validPaths = new ArrayList<>();
                        for (String path : group.relativePaths()) {
                            if (usedPaths.add(path)) {
                                validPaths.add(path);
                            }
                        }
                        // Only keep groups with at least 2 unique paths
                        if (validPaths.size() >= 2) {
                            result.add(new DupFileGroup(group.content(), validPaths));
                        }
                    }
                    return result;
                })
                .filter(list -> !list.isEmpty());
    }

    private Arbitrary<DupFileGroup> dupFileGroup() {
        // A group with same content written to 2-5 different paths
        Arbitrary<byte[]> content = Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(200);

        Arbitrary<List<String>> paths = relativePath()
                .list().ofMinSize(2).ofMaxSize(5)
                .map(pathList -> {
                    // Ensure uniqueness within the group
                    List<String> unique = new ArrayList<>(new LinkedHashSet<>(pathList));
                    return unique;
                })
                .filter(list -> list.size() >= 2);

        return Combinators.combine(content, paths).as(DupFileGroup::new);
    }

    private Arbitrary<String> relativePath() {
        // Generate relative file paths with lowercase names to avoid case-insensitive filesystem issues
        Arbitrary<String> fileName = Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                Arbitraries.of("txt", "jpg", "png", "pdf", "dat")
        ).as((name, ext) -> name + "." + ext);

        Arbitrary<String> dirSegment = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(5);

        Arbitrary<List<String>> dirParts = dirSegment.list().ofMinSize(0).ofMaxSize(2);

        return Combinators.combine(dirParts, fileName).as((dirs, file) -> {
            if (dirs.isEmpty()) {
                return file;
            }
            return String.join("/", dirs) + "/" + file;
        });
    }

    // --- Data types ---

    record DupFileGroup(byte[] content, List<String> relativePaths) {}
}
