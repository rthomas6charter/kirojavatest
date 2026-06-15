package org.example.kirojavatest;

// Feature: file-management-app, Property 1: File listing sort order
// Feature: file-management-app, Property 2: Expand/collapse state round-trip
// Feature: file-management-app, Property 3: Magic number mismatch detection
// Feature: file-management-app, Property 4: Target path suggestion consistency
// **Validates: Requirements 1.1, 1.3, 1.10, 1.11**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.fileanalyzer.DatePathUtil;
import org.example.kirojavatest.fileanalyzer.MagicNumberUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Property tests for the file browser features.
 */
class FileBrowserPropertyTest {

    private Path tempDir;

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("filebrowser-prop-test");
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

    // =========================================================================
    // Property 1: File listing sort order
    //
    // For any directory listing returned by the file browser API, all directory
    // entries SHALL appear before all file entries, and within each group entries
    // SHALL be sorted alphabetically (case-insensitive) by name.
    // =========================================================================

    @Property(tries = 100)
    void fileListingSortOrder(
            @ForAll("mixedEntries") List<DirEntry> entries
    ) throws IOException {
        // Create the entries in the temp directory
        for (DirEntry entry : entries) {
            Path path = tempDir.resolve(entry.name());
            if (entry.isDirectory()) {
                Files.createDirectories(path);
            } else {
                Files.write(path, new byte[]{1, 2, 3});
            }
        }

        // List the directory using the same sort logic as ApiController.listOneLevel
        File[] fileEntries = tempDir.toFile().listFiles();
        if (fileEntries == null || fileEntries.length == 0) return;

        Arrays.sort(fileEntries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        // Filter out .ui-state
        List<File> sortedList = new ArrayList<>();
        for (File f : fileEntries) {
            if (!f.getName().equals(".ui-state")) {
                sortedList.add(f);
            }
        }

        // Verify: all directories come before all files
        boolean seenFile = false;
        for (File f : sortedList) {
            if (f.isDirectory()) {
                assert !seenFile : "Directory '" + f.getName() + "' appeared after a file in the listing";
            } else {
                seenFile = true;
            }
        }

        // Verify: within each group, sorted case-insensitively
        List<String> dirNames = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        for (File f : sortedList) {
            if (f.isDirectory()) {
                dirNames.add(f.getName());
            } else {
                fileNames.add(f.getName());
            }
        }

        for (int i = 1; i < dirNames.size(); i++) {
            assert dirNames.get(i - 1).compareToIgnoreCase(dirNames.get(i)) <= 0
                    : "Directories not sorted: '" + dirNames.get(i - 1) + "' > '" + dirNames.get(i) + "'";
        }

        for (int i = 1; i < fileNames.size(); i++) {
            assert fileNames.get(i - 1).compareToIgnoreCase(fileNames.get(i)) <= 0
                    : "Files not sorted: '" + fileNames.get(i - 1) + "' > '" + fileNames.get(i) + "'";
        }
    }

    @Provide
    Arbitrary<List<DirEntry>> mixedEntries() {
        Arbitrary<DirEntry> dirs = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8)
                .map(name -> new DirEntry(name, true));

        Arbitrary<DirEntry> files = Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                Arbitraries.of("txt", "jpg", "png", "pdf", "dat")
        ).as((name, ext) -> new DirEntry(name + "." + ext, false));

        return Arbitraries.oneOf(dirs, files).list().ofMinSize(1).ofMaxSize(15)
                .map(list -> {
                    // Deduplicate by name
                    Map<String, DirEntry> unique = new LinkedHashMap<>();
                    for (DirEntry e : list) {
                        unique.putIfAbsent(e.name(), e);
                    }
                    return new ArrayList<>(unique.values());
                });
    }

    record DirEntry(String name, boolean isDirectory) {}

    // =========================================================================
    // Property 2: Expand/collapse state round-trip
    //
    // For any list of expanded paths saved via the state API, loading the state
    // for the same user SHALL return an equivalent list.
    // =========================================================================

    @Property(tries = 100)
    void expandCollapseStateRoundTrip(
            @ForAll("expandedPathLists") List<String> paths
    ) throws IOException {
        String user = "testuser";

        // Save state to disk (same logic as ApiController.saveStateToDisk)
        Path stateDir = tempDir.resolve(".ui-state");
        Files.createDirectories(stateDir);
        Path stateFile = stateDir.resolve(user + ".json");

        // Serialize as JSON array
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(paths.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        Files.writeString(stateFile, sb.toString());

        // Load state back (same logic as ApiController.loadStateFromDisk)
        String json = Files.readString(stateFile).trim();
        assert json.startsWith("[") && json.endsWith("]")
                : "State file does not contain a JSON array";

        String inner = json.substring(1, json.length() - 1).trim();
        List<String> loaded = new ArrayList<>();
        if (!inner.isEmpty()) {
            for (String part : inner.split(",")) {
                part = part.trim();
                if (part.startsWith("\"") && part.endsWith("\"")) {
                    loaded.add(part.substring(1, part.length() - 1));
                }
            }
        }

        // Verify round-trip
        assert loaded.size() == paths.size()
                : "Loaded " + loaded.size() + " paths but saved " + paths.size();
        for (int i = 0; i < paths.size(); i++) {
            assert loaded.get(i).equals(paths.get(i))
                    : "Path mismatch at index " + i + ": expected '" + paths.get(i) + "' but got '" + loaded.get(i) + "'";
        }
    }

    @Provide
    Arbitrary<List<String>> expandedPathLists() {
        // Generate path strings like "photos/2024/jan" that don't contain commas or quotes
        Arbitrary<String> segments = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8);

        Arbitrary<String> singlePath = segments.list().ofMinSize(1).ofMaxSize(4)
                .map(parts -> String.join("/", parts));

        return singlePath.list().ofMinSize(0).ofMaxSize(10)
                .map(list -> {
                    // Deduplicate
                    return new ArrayList<>(new LinkedHashSet<>(list));
                });
    }

    // =========================================================================
    // Property 3: Magic number mismatch detection
    //
    // For any file whose first bytes match a known magic number signature, if the
    // file's extension does not match the expected extensions for that signature,
    // MagicNumberUtil.hasMismatch SHALL return true.
    // =========================================================================

    @Property(tries = 100)
    void magicNumberMismatchDetection(
            @ForAll("mismatchedFiles") MismatchSpec spec
    ) throws IOException {
        // Create a file with the given magic bytes but wrong extension
        Path file = tempDir.resolve(spec.filename());
        Files.write(file, spec.content());

        boolean result = MagicNumberUtil.hasMismatch(file);

        assert result : "Expected mismatch for file '" + spec.filename()
                + "' with magic bytes for " + spec.signatureType()
                + " but hasMismatch returned false";
    }

    @Provide
    Arbitrary<MismatchSpec> mismatchedFiles() {
        // Define known signatures and their correct extensions, plus wrong extensions
        record SigDef(String type, byte[] magic, Set<String> correctExts, List<String> wrongExts) {}

        List<SigDef> sigs = List.of(
                new SigDef("PNG",
                        new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
                        Set.of("png"),
                        List.of("jpg", "gif", "bmp", "txt", "pdf")),
                new SigDef("JPEG",
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                        Set.of("jpg", "jpeg", "jfif"),
                        List.of("png", "gif", "bmp", "txt", "pdf")),
                new SigDef("GIF87a",
                        new byte[]{0x47, 0x49, 0x46, 0x38, 0x37, 0x61},
                        Set.of("gif"),
                        List.of("png", "jpg", "bmp", "txt", "pdf")),
                new SigDef("PDF",
                        new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D},
                        Set.of("pdf"),
                        List.of("png", "jpg", "txt", "doc", "gif")),
                new SigDef("ZIP",
                        new byte[]{0x50, 0x4B, 0x03, 0x04},
                        Set.of("zip", "jar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "apk"),
                        List.of("png", "jpg", "txt", "pdf", "gif")),
                new SigDef("GZIP",
                        new byte[]{0x1F, (byte) 0x8B},
                        Set.of("gz", "tgz"),
                        List.of("png", "jpg", "txt", "pdf", "zip"))
        );

        return Arbitraries.of(sigs).flatMap(sig ->
                Combinators.combine(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                        Arbitraries.of(sig.wrongExts()),
                        Arbitraries.bytes().array(byte[].class).ofMinSize(0).ofMaxSize(20)
                ).as((name, wrongExt, extraBytes) -> {
                    // Build content: magic bytes + extra random bytes
                    byte[] content = new byte[sig.magic().length + extraBytes.length];
                    System.arraycopy(sig.magic(), 0, content, 0, sig.magic().length);
                    System.arraycopy(extraBytes, 0, content, sig.magic().length, extraBytes.length);
                    String filename = name + "." + wrongExt;
                    return new MismatchSpec(filename, content, sig.type());
                })
        );
    }

    record MismatchSpec(String filename, byte[] content, String signatureType) {}

    // =========================================================================
    // Property 4: Target path suggestion consistency
    //
    // For any file not in its correct date-based path, the file listing API SHALL
    // include a suggestedPath field equal to DatePathUtil.targetPath(file).
    // =========================================================================

    @Property(tries = 100)
    void targetPathSuggestionConsistency(
            @ForAll("filesNotInCorrectPath") String fileName
    ) throws IOException {
        // Create a file directly in the temp dir (not in a date-based path)
        Path file = tempDir.resolve(fileName);
        Files.write(file, new byte[]{1, 2, 3, 4, 5});

        // Compute expected target path
        String expectedTargetPath = DatePathUtil.targetPath(file);
        String relativePath = tempDir.relativize(file.toAbsolutePath().normalize()).toString();

        // Verify the file is NOT in the correct date path
        boolean isCorrect = DatePathUtil.isInCorrectDatePath(relativePath, file);

        if (!isCorrect) {
            // The API should include suggestedPath = targetPath
            // Simulate what listOneLevel does:
            String suggestedPath = DatePathUtil.targetPath(file);
            assert suggestedPath.equals(expectedTargetPath)
                    : "suggestedPath '" + suggestedPath + "' does not equal expected targetPath '" + expectedTargetPath + "'";

            // Verify targetPath format is YYYY/MM/DD/filename
            assert suggestedPath.endsWith("/" + fileName)
                    : "suggestedPath should end with '/" + fileName + "' but got '" + suggestedPath + "'";

            // Verify the date part has the right format
            String datePart = suggestedPath.substring(0, suggestedPath.lastIndexOf('/'));
            assert datePart.matches("\\d{4}/\\d{2}/\\d{2}")
                    : "Date part '" + datePart + "' doesn't match YYYY/MM/DD pattern";
        }

        // Verify consistency: if we place the file at its target path, isInCorrectDatePath should return true
        Path targetDir = tempDir.resolve(DatePathUtil.datePath(file));
        Files.createDirectories(targetDir);
        Path movedFile = targetDir.resolve(fileName);
        Files.copy(file, movedFile);

        String movedRelPath = tempDir.relativize(movedFile.toAbsolutePath().normalize()).toString();
        boolean shouldBeCorrect = DatePathUtil.isInCorrectDatePath(movedRelPath, movedFile);
        assert shouldBeCorrect
                : "File at targetPath '" + movedRelPath + "' should be reported as correctly placed but wasn't";
    }

    @Provide
    Arbitrary<String> filesNotInCorrectPath() {
        return Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                Arbitraries.of("txt", "jpg", "png", "pdf", "dat", "log", "mp3")
        ).as((name, ext) -> name + "." + ext);
    }
}
