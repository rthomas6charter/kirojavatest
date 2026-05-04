package org.example.kirojavatest;

// Feature: file-management-app, Property 26: File scan populates database
// **Validates: Requirements 13.9**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.db.FileDatabase;
import org.example.kirojavatest.db.FileRecord;
import org.example.kirojavatest.db.FileScanner;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Property 26: File scan populates database
 *
 * For any data directory containing files, after a scan completes,
 * every regular file in the directory (excluding .ui-state) SHALL have
 * a corresponding record in the database with matching relative path.
 */
class FileScanPropertyTest {

    private Path tempDir;

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("filescan-prop-test");
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

    @Property(tries = 100)
    void fileScanPopulatesDatabase(
            @ForAll("fileStructures") FileStructure structure
    ) throws IOException {
        // Create the file structure in the temp directory
        Set<String> createdRelativePaths = createFiles(structure);

        // Run the scanner against the temp directory
        FileDatabase db = new FileDatabase();
        FileScanner scanner = new FileScanner(db);
        scanner.scan();

        // Retrieve all records from the database
        List<FileRecord> allRecords = db.getAllFiles();
        Set<String> dbPaths = allRecords.stream()
                .map(FileRecord::relativePath)
                .collect(Collectors.toSet());

        // Every regular file (excluding .ui-state) must have a matching record
        for (String expectedPath : createdRelativePaths) {
            assert dbPaths.contains(expectedPath)
                    : "File '" + expectedPath + "' was not found in the database after scan. "
                    + "DB contains: " + dbPaths;
        }

        // The database should not contain extra records beyond what we created
        assert dbPaths.size() == createdRelativePaths.size()
                : "Database has " + dbPaths.size() + " records but expected "
                + createdRelativePaths.size() + ". Extra: "
                + dbPaths.stream().filter(p -> !createdRelativePaths.contains(p)).toList();
    }

    /**
     * Creates the file structure on disk and returns the set of relative paths
     * for all regular files (excluding .ui-state).
     */
    private Set<String> createFiles(FileStructure structure) throws IOException {
        Set<String> relativePaths = new HashSet<>();

        for (FileEntry entry : structure.entries()) {
            Path filePath = tempDir.resolve(entry.relativePath());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, entry.content());
            relativePaths.add(entry.relativePath());
        }

        return relativePaths;
    }

    // --- Generators ---

    @Provide
    Arbitrary<FileStructure> fileStructures() {
        Arbitrary<List<FileEntry>> entries = fileEntries().list().ofMinSize(1).ofMaxSize(15);
        return entries.map(list -> {
            // Deduplicate by relative path (keep first occurrence)
            Map<String, FileEntry> unique = new LinkedHashMap<>();
            for (FileEntry e : list) {
                unique.putIfAbsent(e.relativePath(), e);
            }
            return new FileStructure(new ArrayList<>(unique.values()));
        });
    }

    private Arbitrary<FileEntry> fileEntries() {
        Arbitrary<String> relativePaths = relativePaths();
        Arbitrary<byte[]> contents = Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(100);

        return Combinators.combine(relativePaths, contents)
                .as(FileEntry::new);
    }

    private Arbitrary<String> relativePaths() {
        // Generate file names: lowercase alphanumeric with extension
        // Using lowercase only to avoid case-insensitive filesystem collisions (macOS)
        Arbitrary<String> fileNames = Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                Arbitraries.of("txt", "jpg", "png", "pdf", "dat", "log")
        ).as((name, ext) -> name + "." + ext);

        // Generate directory segments (lowercase only, exclude .ui-state which the scanner skips)
        Arbitrary<String> dirSegments = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(6);

        // Generate 0-3 directory levels
        Arbitrary<List<String>> dirParts = dirSegments.list().ofMinSize(0).ofMaxSize(3);

        return Combinators.combine(dirParts, fileNames).as((dirs, file) -> {
            if (dirs.isEmpty()) {
                return file;
            }
            return String.join("/", dirs) + "/" + file;
        });
    }

    // --- Data types for the generator ---

    record FileEntry(String relativePath, byte[] content) {}

    record FileStructure(List<FileEntry> entries) {}
}
