package org.example.kirojavatest;

// Feature: file-management-app, Property 23: Active task list consistency
// Feature: file-management-app, Property 24: Task completion timestamp
// Feature: file-management-app, Property 25: Completed-since filtering
// **Validates: Requirements 12.2, 12.3, 12.4**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.db.SettingsManager;
import org.example.kirojavatest.tasks.BackgroundTask;
import org.example.kirojavatest.tasks.TaskQueue;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Property tests for the background task queue.
 *
 * Property 23: Active task list consistency
 * For any task with status QUEUED or RUNNING, it SHALL appear in the active tasks list.
 * Tasks with status COMPLETED, FAILED, or TIMED_OUT SHALL NOT appear in the active tasks list.
 *
 * Property 24: Task completion timestamp
 * For any task that transitions to COMPLETED or FAILED status, its completedAt field
 * SHALL be non-null and represent the time of completion.
 *
 * Property 25: Completed-since filtering
 * For any timestamp T and set of tasks, getCompletedSince(T) SHALL return only tasks
 * whose completedAt is after T.
 */
class TaskQueuePropertyTest {

    private Path tempDir;
    private TaskQueue taskQueue;

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("taskqueue-prop-test");
        System.setProperty("app.data.dir", tempDir.toAbsolutePath().toString());
        Files.createDirectories(tempDir.resolve(".ui-state"));
        SettingsManager settings = new SettingsManager();
        taskQueue = new TaskQueue(settings);
    }

    @AfterTry
    void tearDown() throws IOException {
        System.clearProperty("app.data.dir");
        if (taskQueue != null) {
            taskQueue.shutdown();
        }
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

    // --- Property 23: Active task list consistency ---

    @Property(tries = 100)
    void completedTasksNotInActiveList(
            @ForAll("taskDescriptions") String description
    ) throws Exception {
        // Submit a task that completes immediately
        CountDownLatch latch = new CountDownLatch(1);
        BackgroundTask task = taskQueue.submit("test", description, t -> {
            latch.countDown();
        });

        // Wait for task to complete
        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(50); // Allow status to update

        List<BackgroundTask> active = taskQueue.getActive();
        boolean found = active.stream().anyMatch(t -> t.id().equals(task.id()));

        assert !found
                : "Completed task should NOT appear in active list, but task " + task.id()
                + " with status " + task.status() + " was found";
    }

    @Property(tries = 100)
    void failedTasksNotInActiveList(
            @ForAll("taskDescriptions") String description
    ) throws Exception {
        // Submit a task that fails
        CountDownLatch latch = new CountDownLatch(1);
        BackgroundTask task = taskQueue.submit("test", description, t -> {
            latch.countDown();
            throw new RuntimeException("intentional failure");
        });

        // Wait for task to fail
        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(50); // Allow status to update

        List<BackgroundTask> active = taskQueue.getActive();
        boolean found = active.stream().anyMatch(t -> t.id().equals(task.id()));

        assert !found
                : "Failed task should NOT appear in active list, but task " + task.id()
                + " with status " + task.status() + " was found";
    }

    @Property(tries = 100)
    void queuedOrRunningTasksAppearInActiveList(
            @ForAll("taskDescriptions") String description
    ) throws Exception {
        // Submit a task that blocks until we release it
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);

        BackgroundTask task = taskQueue.submit("test", description, t -> {
            taskStarted.countDown();
            try {
                taskRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait for the task to start running
        taskStarted.await(5, TimeUnit.SECONDS);

        // While running, it should be in the active list
        List<BackgroundTask> active = taskQueue.getActive();
        boolean found = active.stream().anyMatch(t -> t.id().equals(task.id()));

        assert found
                : "Running task should appear in active list, but task " + task.id()
                + " with status " + task.status() + " was NOT found";

        // Release the task to complete
        taskRelease.countDown();
        Thread.sleep(50);
    }

    // --- Property 24: Task completion timestamp ---

    @Property(tries = 100)
    void completedTaskHasNonNullTimestamp(
            @ForAll("taskDescriptions") String description
    ) throws Exception {
        Instant before = Instant.now();

        CountDownLatch latch = new CountDownLatch(1);
        BackgroundTask task = taskQueue.submit("test", description, t -> {
            latch.countDown();
        });

        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(50);

        assert task.status() == BackgroundTask.Status.COMPLETED
                : "Task should be COMPLETED but was " + task.status();
        assert task.completedAt() != null
                : "completedAt should be non-null for completed task";
        assert !task.completedAt().isBefore(before)
                : "completedAt should be at or after the test start time";
    }

    @Property(tries = 100)
    void failedTaskHasNonNullTimestamp(
            @ForAll("taskDescriptions") String description
    ) throws Exception {
        Instant before = Instant.now();

        CountDownLatch latch = new CountDownLatch(1);
        BackgroundTask task = taskQueue.submit("test", description, t -> {
            latch.countDown();
            throw new RuntimeException("intentional failure");
        });

        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(50);

        assert task.status() == BackgroundTask.Status.FAILED
                : "Task should be FAILED but was " + task.status();
        assert task.completedAt() != null
                : "completedAt should be non-null for failed task";
        assert !task.completedAt().isBefore(before)
                : "completedAt should be at or after the test start time";
    }

    // --- Property 25: Completed-since filtering ---

    @Property(tries = 100)
    void completedSinceReturnsOnlyTasksAfterTimestamp(
            @ForAll("taskDescriptions") String desc1,
            @ForAll("taskDescriptions") String desc2
    ) throws Exception {
        // Submit a task that completes before our reference timestamp
        CountDownLatch latch1 = new CountDownLatch(1);
        BackgroundTask task1 = taskQueue.submit("test", desc1, t -> {
            latch1.countDown();
        });
        latch1.await(5, TimeUnit.SECONDS);
        Thread.sleep(50);

        // Record a timestamp after task1 completes
        Instant sinceTimestamp = Instant.now();

        // Small delay to ensure task2's completedAt is strictly after sinceTimestamp
        Thread.sleep(10);

        // Submit a task that completes after our reference timestamp
        CountDownLatch latch2 = new CountDownLatch(1);
        BackgroundTask task2 = taskQueue.submit("test", desc2, t -> {
            latch2.countDown();
        });
        latch2.await(5, TimeUnit.SECONDS);
        Thread.sleep(50);

        // getCompletedSince should return task2 but NOT task1
        List<BackgroundTask> result = taskQueue.getCompletedSince(sinceTimestamp.toString());

        boolean hasTask2 = result.stream().anyMatch(t -> t.id().equals(task2.id()));
        boolean hasTask1 = result.stream().anyMatch(t -> t.id().equals(task1.id()));

        assert hasTask2
                : "getCompletedSince should include task2 (completed after timestamp), but it did not";
        assert !hasTask1
                : "getCompletedSince should NOT include task1 (completed before timestamp), but it did";
    }

    @Property(tries = 100)
    void completedSinceExcludesAllTasksBeforeTimestamp(
            @ForAll("taskDescriptions") String description
    ) throws Exception {
        // Submit a task that completes
        CountDownLatch latch = new CountDownLatch(1);
        BackgroundTask task = taskQueue.submit("test", description, t -> {
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(50);

        // Use a timestamp in the future - should return nothing
        Instant futureTimestamp = Instant.now().plusSeconds(3600);
        List<BackgroundTask> result = taskQueue.getCompletedSince(futureTimestamp.toString());

        boolean hasTask = result.stream().anyMatch(t -> t.id().equals(task.id()));
        assert !hasTask
                : "getCompletedSince with future timestamp should not include any completed tasks";
    }

    // --- Generators ---

    @Provide
    Arbitrary<String> taskDescriptions() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(' ', '-', '_')
                .ofMinLength(1)
                .ofMaxLength(30);
    }
}
