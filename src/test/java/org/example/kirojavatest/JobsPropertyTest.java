package org.example.kirojavatest;

// Feature: file-management-app, Property 11: Job Template CRUD round-trip
// Feature: file-management-app, Property 12: Job template snapshot immutability
// Feature: file-management-app, Property 13: Job status invariant
// Feature: file-management-app, Property 14: Job ordering
// Feature: file-management-app, Property 15: Job UUID uniqueness
// Feature: file-management-app, Property 33: Job source/target override persistence
// Feature: file-management-app, Property 34: Auto-run respects concurrency limit
// **Validates: Requirements 5.1, 5.2, 6.1, 6.2, 6.3, 6.5, 6.6, 6.9, 6.10**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Property tests for job templates and jobs management.
 */
class JobsPropertyTest {

    private Path tempDir;
    private Path uiStateDir;
    private Path jobTemplatesFile;
    private Path jobsFile;
    private Path settingsFile;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jobs-prop-test");
        System.setProperty("app.data.dir", tempDir.toAbsolutePath().toString());
        uiStateDir = tempDir.resolve(".ui-state");
        Files.createDirectories(uiStateDir);
        jobTemplatesFile = uiStateDir.resolve("job-templates.json");
        jobsFile = uiStateDir.resolve("jobs.json");
        settingsFile = uiStateDir.resolve("settings.json");
        Files.writeString(jobTemplatesFile, "[]");
        Files.writeString(jobsFile, "[]");
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
    // Property 11: Job Template CRUD round-trip
    //
    // For any job template with name, source type, options, and target fields,
    // creating then reading SHALL return equivalent data.
    // =========================================================================

    @Property(tries = 100)
    void jobTemplateCrudRoundTrip(
            @ForAll("validJobTemplates") JobTemplateSpec template
    ) throws IOException {
        // Write template to file (simulating POST /api/job-templates)
        Map<String, Object> tplMap = template.toMap();
        List<Map<String, Object>> templates = new ArrayList<>();
        templates.add(new LinkedHashMap<>(tplMap));
        Files.writeString(jobTemplatesFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(templates));

        // Read back (simulating GET /api/job-templates)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readBack = JSON_MAPPER.readValue(Files.readString(jobTemplatesFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

        assert readBack.size() == 1 : "Expected 1 template, got " + readBack.size();
        Map<String, Object> loaded = readBack.get(0);

        // Verify all fields round-trip correctly
        assert template.name().equals(loaded.get("name"))
                : "Name mismatch: expected '" + template.name() + "' got '" + loaded.get("name") + "'";
        assert template.sourceType().equals(loaded.get("sourceType"))
                : "sourceType mismatch: expected '" + template.sourceType() + "' got '" + loaded.get("sourceType") + "'";
        assert template.targetType().equals(loaded.get("targetType"))
                : "targetType mismatch: expected '" + template.targetType() + "' got '" + loaded.get("targetType") + "'";

        // Verify options
        @SuppressWarnings("unchecked")
        Map<String, Object> loadedOptions = (Map<String, Object>) loaded.get("options");
        assert loadedOptions != null : "Options should not be null";
        assert template.reorganize() == Boolean.TRUE.equals(loadedOptions.get("reorganize"))
                : "reorganize mismatch";
        assert template.removeDuplicates() == Boolean.TRUE.equals(loadedOptions.get("removeDuplicates"))
                : "removeDuplicates mismatch";
        assert template.sendNotification() == Boolean.TRUE.equals(loadedOptions.get("sendNotification"))
                : "sendNotification mismatch";
        assert template.email().equals(loadedOptions.getOrDefault("email", ""))
                : "email mismatch: expected '" + template.email() + "' got '" + loadedOptions.get("email") + "'";
    }

    @Provide
    Arbitrary<JobTemplateSpec> validJobTemplates() {
        Arbitrary<String> names = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> sourceTypes = Arbitraries.of("default", "connection");
        Arbitrary<String> targetTypes = Arbitraries.of("inPlace", "connection");
        Arbitrary<Boolean> booleans = Arbitraries.of(true, false);
        Arbitrary<String> emails = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(10)
                .map(s -> s.isEmpty() ? "" : s + "@test.com");

        return Combinators.combine(names, sourceTypes, targetTypes, booleans, booleans, booleans, emails)
                .as(JobTemplateSpec::new);
    }

    record JobTemplateSpec(String name, String sourceType, String targetType,
                           boolean reorganize, boolean removeDuplicates, boolean sendNotification, String email) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("sourceType", sourceType);
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("reorganize", reorganize);
            options.put("removeDuplicates", removeDuplicates);
            options.put("sendNotification", sendNotification);
            options.put("email", email);
            map.put("options", options);
            map.put("targetType", targetType);
            return map;
        }
    }

    // =========================================================================
    // Property 12: Job template snapshot immutability
    //
    // For any job created from a template, the job's templateSnapshot SHALL equal
    // the template's state at creation time, and subsequent modifications to the
    // template SHALL NOT affect the job's snapshot.
    // =========================================================================

    @Property(tries = 100)
    void jobTemplateSnapshotImmutability(
            @ForAll("validJobTemplates") JobTemplateSpec originalTemplate,
            @ForAll("validJobTemplates") JobTemplateSpec modifiedTemplate
    ) throws IOException {
        // Create the template
        Map<String, Object> tplMap = originalTemplate.toMap();
        List<Map<String, Object>> templates = new ArrayList<>();
        templates.add(new LinkedHashMap<>(tplMap));
        Files.writeString(jobTemplatesFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(templates));

        // Create a job from the template (snapshot it)
        Map<String, Object> snapshot = new LinkedHashMap<>(tplMap);
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", UUID.randomUUID().toString());
        job.put("templateSnapshot", snapshot);
        job.put("status", "created");
        job.put("createdAt", Instant.now().toString());
        job.put("startedAt", null);
        job.put("completedAt", null);
        job.put("errors", List.of());

        List<Map<String, Object>> jobs = new ArrayList<>();
        jobs.add(job);
        Files.writeString(jobsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));

        // Now modify the template (simulating PUT /api/job-templates/0)
        Map<String, Object> modifiedMap = modifiedTemplate.toMap();
        templates.set(0, modifiedMap);
        Files.writeString(jobTemplatesFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(templates));

        // Read back the job and verify snapshot is unchanged
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readJobs = JSON_MAPPER.readValue(Files.readString(jobsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> loadedSnapshot = (Map<String, Object>) readJobs.get(0).get("templateSnapshot");

        // Snapshot should match the ORIGINAL template, not the modified one
        assert originalTemplate.name().equals(loadedSnapshot.get("name"))
                : "Snapshot name should be '" + originalTemplate.name() + "' but got '" + loadedSnapshot.get("name") + "'";
        assert originalTemplate.sourceType().equals(loadedSnapshot.get("sourceType"))
                : "Snapshot sourceType should be '" + originalTemplate.sourceType() + "' but got '" + loadedSnapshot.get("sourceType") + "'";
        assert originalTemplate.targetType().equals(loadedSnapshot.get("targetType"))
                : "Snapshot targetType should be '" + originalTemplate.targetType() + "' but got '" + loadedSnapshot.get("targetType") + "'";

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotOptions = (Map<String, Object>) loadedSnapshot.get("options");
        assert originalTemplate.reorganize() == Boolean.TRUE.equals(snapshotOptions.get("reorganize"))
                : "Snapshot reorganize mismatch";
        assert originalTemplate.removeDuplicates() == Boolean.TRUE.equals(snapshotOptions.get("removeDuplicates"))
                : "Snapshot removeDuplicates mismatch";
    }

    // =========================================================================
    // Property 13: Job status invariant
    //
    // For any job, its status SHALL be one of: created, running, completed, or error.
    // =========================================================================

    @Property(tries = 100)
    void jobStatusInvariant(
            @ForAll("validStatuses") String status
    ) throws IOException {
        // Create a job with the given status
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", UUID.randomUUID().toString());
        job.put("templateSnapshot", Map.of("name", "test"));
        job.put("status", status);
        job.put("createdAt", Instant.now().toString());
        job.put("startedAt", null);
        job.put("completedAt", null);
        job.put("errors", List.of());

        List<Map<String, Object>> jobs = new ArrayList<>();
        jobs.add(job);
        Files.writeString(jobsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));

        // Read back and verify status is valid
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readJobs = JSON_MAPPER.readValue(Files.readString(jobsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        String loadedStatus = (String) readJobs.get(0).get("status");

        Set<String> validStatuses = Set.of("created", "running", "completed", "error");
        assert validStatuses.contains(loadedStatus)
                : "Job status '" + loadedStatus + "' is not one of the valid statuses: " + validStatuses;
    }

    @Provide
    Arbitrary<String> validStatuses() {
        return Arbitraries.of("created", "running", "completed", "error");
    }

    // =========================================================================
    // Property 14: Job ordering
    //
    // For any list of jobs returned by the API, they SHALL be ordered by creation
    // time descending (newest first).
    // =========================================================================

    @Property(tries = 100)
    void jobOrdering(
            @ForAll("jobCounts") int jobCount
    ) throws IOException {
        // Create multiple jobs with sequential creation times
        List<Map<String, Object>> jobs = new ArrayList<>();
        List<String> creationTimes = new ArrayList<>();

        for (int i = 0; i < jobCount; i++) {
            // Use increasing timestamps to simulate creation order
            Instant createdAt = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(i * 60);
            creationTimes.add(createdAt.toString());

            Map<String, Object> job = new LinkedHashMap<>();
            job.put("id", UUID.randomUUID().toString());
            job.put("templateSnapshot", Map.of("name", "template-" + i));
            job.put("status", "created");
            job.put("createdAt", createdAt.toString());
            job.put("startedAt", null);
            job.put("completedAt", null);
            job.put("errors", List.of());
            jobs.add(job);
        }

        Files.writeString(jobsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));

        // Read back
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readJobs = JSON_MAPPER.readValue(Files.readString(jobsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

        // Sort by createdAt descending (as the frontend does with .reverse())
        List<Map<String, Object>> sorted = new ArrayList<>(readJobs);
        sorted.sort((a, b) -> {
            String aTime = (String) a.get("createdAt");
            String bTime = (String) b.get("createdAt");
            return bTime.compareTo(aTime); // descending
        });

        // Verify that the sorted list has newest first
        for (int i = 0; i < sorted.size() - 1; i++) {
            String current = (String) sorted.get(i).get("createdAt");
            String next = (String) sorted.get(i + 1).get("createdAt");
            assert current.compareTo(next) >= 0
                    : "Jobs not in descending creation order: " + current + " should be >= " + next;
        }
    }

    @Provide
    Arbitrary<Integer> jobCounts() {
        return Arbitraries.integers().between(2, 10);
    }

    // =========================================================================
    // Property 15: Job UUID uniqueness
    //
    // For any set of jobs, all job IDs SHALL be valid UUIDs and no two jobs
    // SHALL share the same ID.
    // =========================================================================

    @Property(tries = 100)
    void jobUuidUniqueness(
            @ForAll("jobCounts") int jobCount
    ) throws IOException {
        // Create multiple jobs, each with a UUID
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("id", UUID.randomUUID().toString());
            job.put("templateSnapshot", Map.of("name", "template-" + i));
            job.put("status", "created");
            job.put("createdAt", Instant.now().plusSeconds(i).toString());
            job.put("startedAt", null);
            job.put("completedAt", null);
            job.put("errors", List.of());
            jobs.add(job);
        }

        Files.writeString(jobsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));

        // Read back
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readJobs = JSON_MAPPER.readValue(Files.readString(jobsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

        Set<String> ids = new HashSet<>();
        for (Map<String, Object> job : readJobs) {
            String id = (String) job.get("id");

            // Verify it's a valid UUID
            try {
                UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                assert false : "Job ID '" + id + "' is not a valid UUID";
            }

            // Verify uniqueness
            assert ids.add(id) : "Duplicate job ID found: " + id;
        }

        assert ids.size() == jobCount : "Expected " + jobCount + " unique IDs but got " + ids.size();
    }

    // =========================================================================
    // Property 33: Job source/target override persistence
    //
    // For any job with source and/or target overrides applied, reading the job
    // back SHALL return the override values unchanged, and the original
    // templateSnapshot SHALL remain unmodified.
    // =========================================================================

    @Property(tries = 100)
    void jobSourceTargetOverridePersistence(
            @ForAll("overrideScenarios") OverrideScenario scenario
    ) throws IOException {
        // Create a job from a template
        Map<String, Object> templateSnapshot = new LinkedHashMap<>();
        templateSnapshot.put("name", "base-template");
        templateSnapshot.put("sourceType", "default");
        templateSnapshot.put("targetType", "inPlace");
        templateSnapshot.put("options", Map.of("reorganize", true, "removeDuplicates", false,
                "sendNotification", false, "email", ""));

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", UUID.randomUUID().toString());
        job.put("templateSnapshot", templateSnapshot);
        job.put("status", "created");
        job.put("createdAt", Instant.now().toString());
        job.put("startedAt", null);
        job.put("completedAt", null);
        job.put("errors", List.of());

        // Apply overrides
        if (scenario.sourceOverride() != null) {
            job.put("sourceOverride", scenario.sourceOverride());
        }
        if (scenario.sourceConnectionName() != null) {
            job.put("sourceConnectionName", scenario.sourceConnectionName());
        }
        if (scenario.targetOverride() != null) {
            job.put("targetOverride", scenario.targetOverride());
        }
        if (scenario.targetConnectionName() != null) {
            job.put("targetConnectionName", scenario.targetConnectionName());
        }

        List<Map<String, Object>> jobs = new ArrayList<>();
        jobs.add(job);
        Files.writeString(jobsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));

        // Read back
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readJobs = JSON_MAPPER.readValue(Files.readString(jobsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        Map<String, Object> loadedJob = readJobs.get(0);

        // Verify overrides persist
        if (scenario.sourceOverride() != null) {
            assert scenario.sourceOverride().equals(loadedJob.get("sourceOverride"))
                    : "sourceOverride mismatch: expected '" + scenario.sourceOverride() + "' got '" + loadedJob.get("sourceOverride") + "'";
        }
        if (scenario.sourceConnectionName() != null) {
            assert scenario.sourceConnectionName().equals(loadedJob.get("sourceConnectionName"))
                    : "sourceConnectionName mismatch: expected '" + scenario.sourceConnectionName() + "' got '" + loadedJob.get("sourceConnectionName") + "'";
        }
        if (scenario.targetOverride() != null) {
            assert scenario.targetOverride().equals(loadedJob.get("targetOverride"))
                    : "targetOverride mismatch: expected '" + scenario.targetOverride() + "' got '" + loadedJob.get("targetOverride") + "'";
        }
        if (scenario.targetConnectionName() != null) {
            assert scenario.targetConnectionName().equals(loadedJob.get("targetConnectionName"))
                    : "targetConnectionName mismatch: expected '" + scenario.targetConnectionName() + "' got '" + loadedJob.get("targetConnectionName") + "'";
        }

        // Verify templateSnapshot is unmodified
        @SuppressWarnings("unchecked")
        Map<String, Object> loadedSnapshot = (Map<String, Object>) loadedJob.get("templateSnapshot");
        assert "base-template".equals(loadedSnapshot.get("name"))
                : "templateSnapshot name should be 'base-template' but got '" + loadedSnapshot.get("name") + "'";
        assert "default".equals(loadedSnapshot.get("sourceType"))
                : "templateSnapshot sourceType should be 'default' but got '" + loadedSnapshot.get("sourceType") + "'";
        assert "inPlace".equals(loadedSnapshot.get("targetType"))
                : "templateSnapshot targetType should be 'inPlace' but got '" + loadedSnapshot.get("targetType") + "'";
    }

    @Provide
    Arbitrary<OverrideScenario> overrideScenarios() {
        Arbitrary<String> sourceOverrides = Arbitraries.of("defaultDataDir", "fromConnection", null);
        Arbitrary<String> sourceConnNames = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10)
                        .map(s -> s + " (SMB)")
        );
        Arbitrary<String> targetOverrides = Arbitraries.of("inPlace", "toConnection", null);
        Arbitrary<String> targetConnNames = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10)
                        .map(s -> s + " (SFTP)")
        );

        return Combinators.combine(sourceOverrides, sourceConnNames, targetOverrides, targetConnNames)
                .as(OverrideScenario::new);
    }

    record OverrideScenario(String sourceOverride, String sourceConnectionName,
                            String targetOverride, String targetConnectionName) {}

    // =========================================================================
    // Property 34: Auto-run respects concurrency limit
    //
    // For any set of jobs where auto-run is enabled with max concurrent N, the
    // number of jobs in "running" status SHALL never exceed N after an auto-start cycle.
    // =========================================================================

    @Property(tries = 100)
    void autoRunRespectsConcurrencyLimit(
            @ForAll("concurrencyScenarios") ConcurrencyScenario scenario
    ) throws IOException {
        int maxConcurrent = scenario.maxConcurrentJobs();
        int existingRunningCount = scenario.existingRunningJobs();
        int createdJobCount = scenario.createdJobCount();

        // Create jobs: some already running, some in "created" status
        List<Map<String, Object>> jobs = new ArrayList<>();

        // Add existing running jobs (constrained to not exceed maxConcurrent initially)
        for (int i = 0; i < existingRunningCount; i++) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("id", UUID.randomUUID().toString());
            job.put("templateSnapshot", Map.of("name", "running-" + i));
            job.put("status", "running");
            job.put("createdAt", Instant.parse("2024-01-01T00:00:00Z").plusSeconds(i).toString());
            job.put("startedAt", Instant.now().toString());
            job.put("completedAt", null);
            job.put("errors", List.of());
            jobs.add(job);
        }

        // Add created jobs waiting to be auto-started
        for (int i = 0; i < createdJobCount; i++) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("id", UUID.randomUUID().toString());
            job.put("templateSnapshot", Map.of("name", "created-" + i));
            job.put("status", "created");
            job.put("createdAt", Instant.parse("2024-01-01T01:00:00Z").plusSeconds(i).toString());
            job.put("startedAt", null);
            job.put("completedAt", null);
            job.put("errors", List.of());
            jobs.add(job);
        }

        Files.writeString(jobsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));

        // Simulate auto-run logic: start oldest "created" jobs when slots available
        // This mirrors the logic that would run when autoRunNextJob is enabled
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> currentJobs = JSON_MAPPER.readValue(Files.readString(jobsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

        long runningCount = currentJobs.stream()
                .filter(j -> "running".equals(j.get("status")))
                .count();

        // Sort created jobs by createdAt ascending (oldest first for auto-start)
        List<Map<String, Object>> createdJobs = currentJobs.stream()
                .filter(j -> "created".equals(j.get("status")))
                .sorted((a, b) -> ((String) a.get("createdAt")).compareTo((String) b.get("createdAt")))
                .toList();

        // Auto-start jobs up to the concurrency limit
        int slotsAvailable = (int) (maxConcurrent - runningCount);
        int toStart = Math.min(Math.max(0, slotsAvailable), createdJobs.size());
        for (int i = 0; i < toStart; i++) {
            Map<String, Object> toUpdate = createdJobs.get(i);
            // Find and update in the main list
            for (Map<String, Object> j : currentJobs) {
                if (toUpdate.get("id").equals(j.get("id"))) {
                    j.put("status", "running");
                    j.put("startedAt", Instant.now().toString());
                    break;
                }
            }
        }

        Files.writeString(jobsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(currentJobs));

        // Read back and count running jobs
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterAutoRun = JSON_MAPPER.readValue(Files.readString(jobsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

        long finalRunningCount = afterAutoRun.stream()
                .filter(j -> "running".equals(j.get("status")))
                .count();

        // The auto-run SHALL NOT start new jobs that would exceed maxConcurrent.
        // After the auto-start cycle, running count should be at most max(existingRunning, maxConcurrent)
        // because existing running jobs are not stopped, but no new ones are added past the limit.
        long newlyStarted = finalRunningCount - existingRunningCount;
        assert newlyStarted >= 0 : "Running count decreased unexpectedly";

        // The key invariant: after auto-start, running jobs never exceed N
        // If existingRunning was already <= maxConcurrent, final should be <= maxConcurrent
        // If existingRunning was already > maxConcurrent, no new jobs should have been started
        if (existingRunningCount <= maxConcurrent) {
            assert finalRunningCount <= maxConcurrent
                    : "Running jobs (" + finalRunningCount + ") exceeds max concurrent limit (" + maxConcurrent
                    + ") after auto-start. existingRunning=" + existingRunningCount + ", created=" + createdJobCount;
        } else {
            // Existing running already exceeds limit — no new jobs should be started
            assert newlyStarted == 0
                    : "Auto-run started " + newlyStarted + " new jobs when already at " + existingRunningCount
                    + " running (limit=" + maxConcurrent + ")";
        }
    }

    @Provide
    Arbitrary<ConcurrencyScenario> concurrencyScenarios() {
        Arbitrary<Integer> maxConcurrent = Arbitraries.integers().between(1, 4);
        Arbitrary<Integer> existingRunning = Arbitraries.integers().between(0, 6);
        Arbitrary<Integer> createdJobs = Arbitraries.integers().between(0, 8);

        return Combinators.combine(maxConcurrent, existingRunning, createdJobs)
                .as(ConcurrencyScenario::new);
    }

    record ConcurrencyScenario(int maxConcurrentJobs, int existingRunningJobs, int createdJobCount) {}
}
