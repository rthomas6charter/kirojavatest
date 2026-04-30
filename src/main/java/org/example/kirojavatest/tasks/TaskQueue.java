package org.example.kirojavatest.tasks;

import org.example.kirojavatest.db.SettingsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Manages a queue of background tasks with timeout enforcement.
 * Tasks run on a thread pool and are tracked by ID.
 * Completed/failed tasks are retained for UI polling.
 */
public class TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);

    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;
    private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final SettingsManager settings;

    public TaskQueue(SettingsManager settings) {
        this.settings = settings;
        this.executor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                r -> { Thread t = new Thread(r, "bg-task"); t.setDaemon(true); return t; }
        );
        this.watchdog = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "task-watchdog"); t.setDaemon(true); return t; }
        );
        // Check for timed-out tasks every 5 seconds
        this.watchdog.scheduleAtFixedRate(this::enforceTimeouts, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Submit a task for background execution.
     * @param type     task type identifier (e.g. "scan", "checksum", "compare")
     * @param description human-readable description
     * @param work     the work to perform; receives the BackgroundTask for progress updates
     * @return the created task
     */
    public BackgroundTask submit(String type, String description, Consumer<BackgroundTask> work) {
        BackgroundTask task = new BackgroundTask(type, description);
        tasks.put(task.id(), task);

        Future<?> future = executor.submit(() -> {
            task.markRunning();
            log.info("Task started: {} [{}]", task.description(), task.id());
            try {
                work.accept(task);
                if (task.status() == BackgroundTask.Status.RUNNING) {
                    task.markCompleted("Done");
                }
                log.info("Task completed: {} [{}] ({}s)", task.description(), task.id(), task.elapsedSeconds());
            } catch (Exception e) {
                task.markFailed(e.getMessage());
                log.error("Task failed: {} [{}]", task.description(), task.id(), e);
            } finally {
                futures.remove(task.id());
            }
        });
        futures.put(task.id(), future);
        return task;
    }

    /** Get a task by ID. */
    public Optional<BackgroundTask> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /** Get all tasks, most recent first. */
    public List<BackgroundTask> getAll() {
        var list = new ArrayList<>(tasks.values());
        list.sort(Comparator.comparing(BackgroundTask::createdAt).reversed());
        return list;
    }

    /** Get tasks that completed or failed since a given timestamp. */
    public List<BackgroundTask> getCompletedSince(String sinceIso) {
        var since = java.time.Instant.parse(sinceIso);
        var result = new ArrayList<BackgroundTask>();
        for (var task : tasks.values()) {
            if (task.completedAt() != null && task.completedAt().isAfter(since)) {
                result.add(task);
            }
        }
        return result;
    }

    /** Get active (queued or running) tasks. */
    public List<BackgroundTask> getActive() {
        var result = new ArrayList<BackgroundTask>();
        for (var task : tasks.values()) {
            if (task.status() == BackgroundTask.Status.QUEUED || task.status() == BackgroundTask.Status.RUNNING) {
                result.add(task);
            }
        }
        return result;
    }

    /** Remove completed/failed tasks older than the given age in seconds. */
    public void purgeOlderThan(long ageSeconds) {
        var cutoff = java.time.Instant.now().minusSeconds(ageSeconds);
        tasks.entrySet().removeIf(e -> {
            var t = e.getValue();
            return t.completedAt() != null && t.completedAt().isBefore(cutoff);
        });
    }

    /** Cancel a running task. */
    public boolean cancel(String id) {
        Future<?> future = futures.get(id);
        if (future != null) {
            future.cancel(true);
            futures.remove(id);
            BackgroundTask task = tasks.get(id);
            if (task != null && task.status() == BackgroundTask.Status.RUNNING) {
                task.markFailed("Cancelled");
            }
            return true;
        }
        return false;
    }

    private void enforceTimeouts() {
        int timeoutSecs = settings.getBoolean("backgroundTaskTimeout", false)
                ? 300 : ((Number) settings.get("backgroundTaskTimeout", 300)).intValue();
        for (var entry : futures.entrySet()) {
            BackgroundTask task = tasks.get(entry.getKey());
            if (task != null && task.status() == BackgroundTask.Status.RUNNING
                    && task.elapsedSeconds() > timeoutSecs) {
                log.warn("Task timed out: {} [{}] after {}s", task.description(), task.id(), task.elapsedSeconds());
                entry.getValue().cancel(true);
                task.markTimedOut();
                futures.remove(entry.getKey());
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        watchdog.shutdownNow();
    }
}
