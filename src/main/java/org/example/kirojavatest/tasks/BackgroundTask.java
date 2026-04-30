package org.example.kirojavatest.tasks;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a trackable background task.
 */
public class BackgroundTask {

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, TIMED_OUT }

    private final String id;
    private final String type;
    private final String description;
    private final Instant createdAt;
    private volatile Status status;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String result;
    private volatile String error;
    private volatile double progress; // 0.0 to 1.0
    private volatile String targetPath; // optional: the path/item this task relates to

    public BackgroundTask(String type, String description) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.description = description;
        this.createdAt = Instant.now();
        this.status = Status.QUEUED;
        this.progress = 0.0;
    }

    public String id() { return id; }
    public String type() { return type; }
    public String description() { return description; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public String result() { return result; }
    public String error() { return error; }
    public double progress() { return progress; }
    public String targetPath() { return targetPath; }

    public void setTargetPath(String path) { this.targetPath = path; }
    public void setProgress(double p) { this.progress = Math.max(0, Math.min(1, p)); }

    public void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
    }

    public void markCompleted(String result) {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
        this.result = result;
        this.progress = 1.0;
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
        this.error = error;
    }

    public void markTimedOut() {
        this.status = Status.TIMED_OUT;
        this.completedAt = Instant.now();
        this.error = "Task exceeded timeout";
    }

    /** Elapsed seconds since the task started running, or 0 if not started. */
    public long elapsedSeconds() {
        if (startedAt == null) return 0;
        Instant end = completedAt != null ? completedAt : Instant.now();
        return java.time.Duration.between(startedAt, end).getSeconds();
    }

    public Map<String, Object> toMap() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("type", type);
        m.put("description", description);
        m.put("status", status.name());
        m.put("progress", progress);
        m.put("createdAt", createdAt.toString());
        m.put("startedAt", startedAt != null ? startedAt.toString() : null);
        m.put("completedAt", completedAt != null ? completedAt.toString() : null);
        m.put("elapsedSeconds", elapsedSeconds());
        m.put("result", result);
        m.put("error", error);
        m.put("targetPath", targetPath);
        return m;
    }
}
