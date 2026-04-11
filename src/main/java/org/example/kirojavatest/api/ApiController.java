package org.example.kirojavatest.api;

import org.example.kirojavatest.AppConfig;
import org.example.kirojavatest.fileanalyzer.DatePathUtil;
import org.example.kirojavatest.fileanalyzer.DirectorySummary;
import org.example.kirojavatest.fileanalyzer.DuplicateGroup;
import org.example.kirojavatest.fileanalyzer.FileAnalyzer;
import org.example.kirojavatest.fileanalyzer.FileInfo;
import io.javalin.config.JavalinConfig;
import io.javalin.http.HttpStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApiController {

    // In-memory store of expanded paths per user (survives requests, not server restarts)
    // For persistence across restarts, this could be backed by a file in the data dir.
    private static final Map<String, List<String>> expandedState = new ConcurrentHashMap<>();

    public static void register(JavalinConfig config) {
        config.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        config.routes.get("/api/items", ctx -> ctx.json(Map.of("items", new String[]{})));

        config.routes.post("/api/items", ctx -> {
            ctx.status(HttpStatus.CREATED).json(Map.of("created", true));
        });

        // List one level of the data directory. ?path= is relative to data dir root.
        config.routes.get("/api/files", ctx -> {
            String dataDir = AppConfig.get("app.data.dir", "");
            if (dataDir.isEmpty()) {
                ctx.json(Map.of("path", "", "items", List.of()));
                return;
            }
            Path root = Paths.get(dataDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                try { Files.createDirectories(root); }
                catch (IOException e) {
                    ctx.json(Map.of("path", root.toString(), "items", List.of()));
                    return;
                }
            }

            String relPath = ctx.queryParam("path");
            Path target = root;
            if (relPath != null && !relPath.isEmpty()) {
                target = root.resolve(relPath).normalize();
                // Prevent path traversal outside data dir
                if (!target.startsWith(root)) {
                    ctx.status(HttpStatus.FORBIDDEN).json(Map.of("error", "Access denied"));
                    return;
                }
            }

            if (!Files.isDirectory(target)) {
                ctx.json(Map.of("path", relPath != null ? relPath : "", "items", List.of()));
                return;
            }

            List<Map<String, Object>> items = listOneLevel(root, target);
            ctx.json(Map.of(
                    "path", relPath != null ? relPath : "",
                    "items", items
            ));
        });

        // Save expanded state for the logged-in user
        config.routes.post("/api/files/state", ctx -> {
            String user = ctx.sessionAttribute("user");
            if (user == null) { ctx.status(HttpStatus.UNAUTHORIZED); return; }
            @SuppressWarnings("unchecked")
            List<String> paths = ctx.bodyAsClass(List.class);
            expandedState.put(user, new ArrayList<>(paths));
            saveStateToDisk(user, paths);
            ctx.json(Map.of("saved", true));
        });

        // Load expanded state for the logged-in user
        config.routes.get("/api/files/state", ctx -> {
            String user = ctx.sessionAttribute("user");
            if (user == null) { ctx.status(HttpStatus.UNAUTHORIZED); return; }
            List<String> paths = expandedState.get(user);
            if (paths == null) {
                paths = loadStateFromDisk(user);
                if (paths != null) {
                    expandedState.put(user, paths);
                }
            }
            ctx.json(paths != null ? paths : List.of());
        });

        // Return duplicate file groups with metadata
        config.routes.get("/api/files/duplicates", ctx -> {
            String dataDir = AppConfig.get("app.data.dir", "");
            if (dataDir.isEmpty()) { ctx.json(Map.of("groups", List.of(), "paths", List.of())); return; }
            Path root = Paths.get(dataDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) { ctx.json(Map.of("groups", List.of(), "paths", List.of())); return; }
            try {
                List<DuplicateGroup> groups = FileAnalyzer.findDuplicateFiles(root);
                List<String> allDupPaths = new ArrayList<>();
                List<List<Map<String, String>>> groupList = new ArrayList<>();
                for (DuplicateGroup g : groups) {
                    List<Map<String, String>> groupEntries = new ArrayList<>();
                    for (FileInfo fi : g.files()) {
                        Path absPath = fi.path().toAbsolutePath().normalize();
                        String rel = root.relativize(absPath).toString();
                        allDupPaths.add(rel);
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("path", rel);
                        entry.put("name", fi.name());
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class);
                            entry.put("created", attrs.creationTime().toInstant().toString());
                            entry.put("modified", attrs.lastModifiedTime().toInstant().toString());
                        } catch (IOException e2) {
                            entry.put("created", "");
                            entry.put("modified", "");
                        }
                        groupEntries.add(entry);
                    }
                    groupList.add(groupEntries);
                }
                ctx.json(Map.of("groups", groupList, "paths", allDupPaths));
            } catch (Exception e) {
                ctx.json(Map.of("groups", List.of(), "paths", List.of()));
            }
        });

        // Summary statistics
        config.routes.get("/api/summary", ctx -> {
            String dataDir = AppConfig.get("app.data.dir", "");
            if (dataDir.isEmpty()) { ctx.json(Map.of()); return; }
            Path root = Paths.get(dataDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) { ctx.json(Map.of()); return; }
            try {
                DirectorySummary ds = FileAnalyzer.calculateDirectoryTotalSize(root);
                List<DuplicateGroup> dups = FileAnalyzer.findDuplicateFiles(root);
                int dupGroupCount = dups.size();
                long reclaimableBytes = 0;
                int dupFileCount = 0;
                for (DuplicateGroup g : dups) {
                    reclaimableBytes += g.wastedBytes();
                    dupFileCount += g.count();
                }
                int filesAfterDedup = ds.fileCount() - (dupFileCount - dupGroupCount);

                // Count files needing reorganization
                int needsReorg = countFilesNeedingReorg(root);

                ctx.json(Map.of(
                    "totalFiles", ds.fileCount(),
                    "totalDirs", ds.directoryCount(),
                    "totalSize", ds.totalSize(),
                    "dupGroupCount", dupGroupCount,
                    "reclaimableBytes", reclaimableBytes,
                    "filesAfterDedup", filesAfterDedup,
                    "needsReorgCount", needsReorg
                ));
            } catch (Exception e) {
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        // Generate shell script to remove duplicates
        config.routes.get("/api/scripts/remove-duplicates", ctx -> {
            String dataDir = AppConfig.get("app.data.dir", "");
            Path root = Paths.get(dataDir).toAbsolutePath().normalize();
            List<DuplicateGroup> dups = FileAnalyzer.findDuplicateFiles(root);
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
            ctx.contentType("text/x-shellscript");
            ctx.header("Content-Disposition", "attachment; filename=\"remove-duplicates.sh\"");
            ctx.result(sb.toString());
        });

        // Generate shell script to reorganize files into YYYY/MM/DD structure
        config.routes.get("/api/scripts/reorganize", ctx -> {
            String dataDir = AppConfig.get("app.data.dir", "");
            Path root = Paths.get(dataDir).toAbsolutePath().normalize();
            StringBuilder sb = new StringBuilder("#!/usr/bin/env bash\n");
            sb.append("# Reorganize files into YYYY/MM/DD directory structure based on creation date\n");
            sb.append("set -euo pipefail\n\n");
            sb.append("DATA_DIR=").append(shellEscape(root.toString())).append("\n\n");
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.getFileName() != null && dir.getFileName().toString().equals(".ui-state"))
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        String rel = root.relativize(file.toAbsolutePath().normalize()).toString();
                        if (!DatePathUtil.isInCorrectDatePath(rel, file)) {
                            String target = DatePathUtil.targetPath(file);
                            String targetDir = DatePathUtil.datePath(file);
                            sb.append("mkdir -p \"$DATA_DIR/").append(targetDir).append("\"\n");
                            sb.append("mv \"$DATA_DIR/").append(rel)
                              .append("\" \"$DATA_DIR/").append(target).append("\"\n\n");
                        }
                    } catch (IOException e) { /* skip */ }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            ctx.contentType("text/x-shellscript");
            ctx.header("Content-Disposition", "attachment; filename=\"reorganize-files.sh\"");
            ctx.result(sb.toString());
        });
    }

    private static int countFilesNeedingReorg(Path root) throws IOException {
        int[] count = {0};
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".ui-state"))
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String rel = root.relativize(file.toAbsolutePath().normalize()).toString();
                    if (!DatePathUtil.isInCorrectDatePath(rel, file)) count[0]++;
                } catch (IOException e) { /* skip */ }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private static String shellEscape(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** List immediate children of target, one level only. */
    private static List<Map<String, Object>> listOneLevel(Path root, Path target) {
        List<Map<String, Object>> items = new ArrayList<>();
        File[] entries = target.toFile().listFiles();
        if (entries == null) return items;

        Arrays.sort(entries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File entry : entries) {
            // Hide internal UI state directory
            if (entry.getName().equals(".ui-state")) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getName());
            // Relative path from data dir root
            item.put("path", root.relativize(entry.toPath().toAbsolutePath().normalize()).toString());
            if (entry.isDirectory()) {
                item.put("type", "directory");
                String[] kids = entry.list();
                boolean hasKids = kids != null && kids.length > 0;
                item.put("hasChildren", hasKids);
                if (hasKids) {
                    try {
                        DirectorySummary summary = FileAnalyzer.calculateDirectoryTotalSize(entry.toPath());
                        item.put("size", summary.totalSize());
                    } catch (IOException e) {
                        item.put("size", 0);
                    }
                }
            } else {
                item.put("type", "file");
                item.put("size", entry.length());
                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry.toPath(), BasicFileAttributes.class);
                    item.put("created", attrs.creationTime().toInstant().toString());
                    item.put("modified", attrs.lastModifiedTime().toInstant().toString());
                } catch (IOException e) {
                    item.put("created", "");
                    item.put("modified", "");
                }
                // Suggested date-based path if file is not already in the correct location
                String relPath = root.relativize(entry.toPath().toAbsolutePath().normalize()).toString();
                try {
                    if (!DatePathUtil.isInCorrectDatePath(relPath, entry.toPath())) {
                        item.put("suggestedPath", DatePathUtil.targetPath(entry.toPath()));
                    }
                } catch (IOException e) {
                    // skip suggestion if we can't read attributes
                }
            }
            items.add(item);
        }
        return items;
    }

    // --- Persist expanded state to a JSON file in the data dir ---

    private static Path stateFile(String user) {
        String dataDir = AppConfig.get("app.data.dir", "");
        return Paths.get(dataDir).toAbsolutePath().resolve(".ui-state").resolve(user + ".json");
    }

    private static void saveStateToDisk(String user, List<String> paths) {
        try {
            Path file = stateFile(user);
            Files.createDirectories(file.getParent());
            // Simple JSON array serialization
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < paths.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(paths.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            }
            sb.append("]");
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            // best-effort
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> loadStateFromDisk(String user) {
        try {
            Path file = stateFile(user);
            if (!Files.exists(file)) return null;
            String json = Files.readString(file);
            // Minimal JSON array parser for string arrays
            json = json.trim();
            if (!json.startsWith("[") || !json.endsWith("]")) return null;
            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty()) return List.of();
            List<String> result = new ArrayList<>();
            for (String part : json.split(",")) {
                part = part.trim();
                if (part.startsWith("\"") && part.endsWith("\"")) {
                    result.add(part.substring(1, part.length() - 1));
                }
            }
            return result;
        } catch (IOException e) {
            return null;
        }
    }
}
