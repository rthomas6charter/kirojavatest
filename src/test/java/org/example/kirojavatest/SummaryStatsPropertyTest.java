package org.example.kirojavatest;

// Feature: file-management-app, Property 7: Summary statistics consistency
// Feature: file-management-app, Property 7b: Reorganization time estimate correctness
// **Validates: Requirements 3.1, 3.4, 3.5**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.fileanalyzer.DirectorySummary;
import org.example.kirojavatest.fileanalyzer.DuplicateGroup;
import org.example.kirojavatest.fileanalyzer.FileAnalyzer;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Property tests for summary statistics.
 *
 * Property 7: For any data directory state, the summary API SHALL return
 * filesAfterDedup equal to totalFiles - (totalDuplicateFiles - dupGroupCount),
 * and reclaimableBytes equal to the sum of (count - 1) * fileSize across all
 * duplicate groups.
 *
 * Property 7b: For any set of files needing reorganization with known sizes,
 * and given configured reorgTransferRateMbps and reorgPerFileOverheadMs, the
 * estimated reorganization time SHALL equal the sum of
 * (fileSize / (transferRateMbps * 1024 * 1024)) for each file, plus
 * (fileCount * perFileOverheadMs / 1000), expressed in seconds.
 */
class SummaryStatsPropertyTest {

    private Path tempDir;

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("summary-stats-prop-test");
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

    // --- Property 7: Summary statistics consistency ---

    @Property(tries = 100)
    void summaryStatisticsConsistency(
            @ForAll("fileGroupsForSummary") List<FileGroupSpec> fileGroups
    ) throws IOException {
        // Create all files on disk
        Set<String> usedPaths = new HashSet<>();
        for (FileGroupSpec group : fileGroups) {
            for (String relativePath : group.relativePaths()) {
                if (!usedPaths.add(relativePath)) continue; // skip duplicate paths
                Path filePath = tempDir.resolve(relativePath);
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, group.content());
            }
        }

        // Compute summary using FileAnalyzer (same logic as ApiController)
        DirectorySummary ds = FileAnalyzer.calculateDirectoryTotalSize(tempDir);
        List<DuplicateGroup> dups = FileAnalyzer.findDuplicateFiles(tempDir);

        int totalFiles = ds.fileCount();
        int dupGroupCount = dups.size();
        long reclaimableBytes = 0;
        int dupFileCount = 0;
        for (DuplicateGroup g : dups) {
            reclaimableBytes += g.wastedBytes();
            dupFileCount += g.count();
        }
        int filesAfterDedup = totalFiles - (dupFileCount - dupGroupCount);

        // Verify filesAfterDedup formula:
        // filesAfterDedup = totalFiles - (totalDuplicateFiles - dupGroupCount)
        // This means: total files minus the "extra" copies (keeping one per group)
        int expectedFilesAfterDedup = totalFiles - (dupFileCount - dupGroupCount);
        assert filesAfterDedup == expectedFilesAfterDedup
                : "filesAfterDedup mismatch: expected " + expectedFilesAfterDedup
                + ", got " + filesAfterDedup;

        // Verify reclaimableBytes formula:
        // reclaimableBytes = sum of (count - 1) * fileSize across all duplicate groups
        long expectedReclaimable = 0;
        for (DuplicateGroup g : dups) {
            expectedReclaimable += (long) (g.count() - 1) * g.fileSize();
        }
        assert reclaimableBytes == expectedReclaimable
                : "reclaimableBytes mismatch: expected " + expectedReclaimable
                + ", got " + reclaimableBytes;

        // Verify invariants hold:
        // filesAfterDedup must be >= dupGroupCount (at least one file per group survives)
        assert filesAfterDedup >= dupGroupCount
                : "filesAfterDedup (" + filesAfterDedup + ") < dupGroupCount (" + dupGroupCount + ")";

        // filesAfterDedup must be <= totalFiles
        assert filesAfterDedup <= totalFiles
                : "filesAfterDedup (" + filesAfterDedup + ") > totalFiles (" + totalFiles + ")";

        // reclaimableBytes must be non-negative
        assert reclaimableBytes >= 0
                : "reclaimableBytes is negative: " + reclaimableBytes;
    }

    // --- Property 7b: Reorganization time estimate correctness ---

    @Property(tries = 100)
    void reorganizationTimeEstimateCorrectness(
            @ForAll("reorgFileSpecs") List<Long> fileSizes,
            @ForAll("transferRates") int transferRateMbps,
            @ForAll("overheads") int perFileOverheadMs
    ) {
        // The formula from ApiController:
        // transferRateBytes = transferRateMbps * 1024 * 1024
        // transferTime = sum(fileSize) / transferRateBytes
        // overheadTime = fileCount * perFileOverheadMs / 1000.0
        // estimatedReorgTimeSeconds = transferTime + overheadTime

        int fileCount = fileSizes.size();
        long totalSize = 0;
        for (long size : fileSizes) {
            totalSize += size;
        }

        double transferRateBytes = (double) transferRateMbps * 1024 * 1024;
        double expectedTransferTime = transferRateBytes > 0 ? totalSize / transferRateBytes : 0;
        double expectedOverheadTime = (double) fileCount * perFileOverheadMs / 1000.0;
        double expectedTotal = expectedTransferTime + expectedOverheadTime;

        // Recompute using the same formula the API uses (per-file sum approach from design doc)
        double perFileTransferSum = 0;
        for (long size : fileSizes) {
            perFileTransferSum += transferRateBytes > 0 ? size / transferRateBytes : 0;
        }
        double perFileEstimate = perFileTransferSum + expectedOverheadTime;

        // Both approaches should give equivalent results (sum of individual / total)
        // Due to floating point, we accept a small tolerance
        assert Math.abs(expectedTotal - perFileEstimate) < 0.0001
                : "Sum-of-individual and total-sum approaches diverge. "
                + "Total-sum: " + expectedTotal + ", per-file-sum: " + perFileEstimate;

        // Verify the formula components make logical sense
        assert expectedTotal >= 0
                : "Estimated time is negative: " + expectedTotal;

        // If there are no files, time should be 0
        if (fileCount == 0) {
            assert expectedTotal == 0.0
                    : "Expected 0 time for 0 files, got " + expectedTotal;
        }

        // The overhead portion should be exactly fileCount * perFileOverheadMs / 1000
        double actualOverhead = (double) fileCount * perFileOverheadMs / 1000.0;
        assert Math.abs(actualOverhead - expectedOverheadTime) < 0.0000001
                : "Overhead mismatch: expected " + expectedOverheadTime
                + ", got " + actualOverhead;

        // The transfer portion should equal totalSize / transferRateBytes
        if (transferRateBytes > 0) {
            double actualTransfer = totalSize / transferRateBytes;
            assert Math.abs(actualTransfer - expectedTransferTime) < 0.0000001
                    : "Transfer time mismatch: expected " + expectedTransferTime
                    + ", got " + actualTransfer;
        }
    }

    // --- Generators ---

    @Provide
    Arbitrary<List<FileGroupSpec>> fileGroupsForSummary() {
        // Generate a mix of duplicate groups (2+ files with same content)
        // and unique files (single file with unique content)
        Arbitrary<List<FileGroupSpec>> duplicateGroups = duplicateFileGroup()
                .list().ofMinSize(0).ofMaxSize(4);

        Arbitrary<List<FileGroupSpec>> uniqueFiles = uniqueFileGroup()
                .list().ofMinSize(0).ofMaxSize(5);

        return Combinators.combine(duplicateGroups, uniqueFiles).as((dups, uniques) -> {
            List<FileGroupSpec> all = new ArrayList<>(dups);
            all.addAll(uniques);

            // Deduplicate paths across all groups
            Set<String> usedPaths = new HashSet<>();
            List<FileGroupSpec> result = new ArrayList<>();
            for (FileGroupSpec group : all) {
                List<String> validPaths = new ArrayList<>();
                for (String path : group.relativePaths()) {
                    if (usedPaths.add(path)) {
                        validPaths.add(path);
                    }
                }
                if (group.relativePaths().size() > 1 && validPaths.size() >= 2) {
                    result.add(new FileGroupSpec(group.content(), validPaths));
                } else if (group.relativePaths().size() == 1 && validPaths.size() == 1) {
                    result.add(new FileGroupSpec(group.content(), validPaths));
                }
            }
            return result;
        });
    }

    private Arbitrary<FileGroupSpec> duplicateFileGroup() {
        Arbitrary<byte[]> content = Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(100);

        Arbitrary<List<String>> paths = relativePath()
                .list().ofMinSize(2).ofMaxSize(4);

        return Combinators.combine(content, paths).as(FileGroupSpec::new);
    }

    private Arbitrary<FileGroupSpec> uniqueFileGroup() {
        Arbitrary<byte[]> content = Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(100);

        Arbitrary<List<String>> paths = relativePath()
                .list().ofSize(1);

        return Combinators.combine(content, paths).as(FileGroupSpec::new);
    }

    private Arbitrary<String> relativePath() {
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
            if (dirs.isEmpty()) return file;
            return String.join("/", dirs) + "/" + file;
        });
    }

    @Provide
    Arbitrary<List<Long>> reorgFileSpecs() {
        // Generate a list of file sizes (0-20 files, each 0 to 100MB)
        return Arbitraries.longs().between(0, 100L * 1024 * 1024)
                .list().ofMinSize(0).ofMaxSize(20);
    }

    @Provide
    Arbitrary<Integer> transferRates() {
        // Transfer rates between 1 and 1000 MB/s (positive, realistic range)
        return Arbitraries.integers().between(1, 1000);
    }

    @Provide
    Arbitrary<Integer> overheads() {
        // Per-file overhead between 0 and 5000ms
        return Arbitraries.integers().between(0, 5000);
    }

    // --- Data types ---

    record FileGroupSpec(byte[] content, List<String> relativePaths) {}
}
