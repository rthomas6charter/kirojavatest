package org.example.kirojavatest.db;

import org.example.kirojavatest.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

/**
 * SQLite-backed database for storing scanned file metadata,
 * duplicate pairings, and reorganization targets.
 */
public class FileDatabase {

    private static final Logger log = LoggerFactory.getLogger(FileDatabase.class);
    private final String dbUrl;

    public FileDatabase() {
        String dataDir = AppConfig.get("app.data.dir", ".");
        Path dbPath = Paths.get(dataDir).toAbsolutePath().resolve(".ui-state").resolve("filedb.sqlite");
        dbPath.getParent().toFile().mkdirs();
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_info (
                    id TEXT PRIMARY KEY,
                    relative_path TEXT NOT NULL UNIQUE,
                    file_name TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    checksum TEXT,
                    created_at TEXT,
                    modified_at TEXT,
                    target_path TEXT,
                    last_scanned TEXT NOT NULL
                )
            """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS duplicate_pair (
                    file_id_1 TEXT NOT NULL,
                    file_id_2 TEXT NOT NULL,
                    PRIMARY KEY (file_id_1, file_id_2),
                    FOREIGN KEY (file_id_1) REFERENCES file_info(id),
                    FOREIGN KEY (file_id_2) REFERENCES file_info(id)
                )
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_file_checksum ON file_info(checksum)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_file_path ON file_info(relative_path)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dup_1 ON duplicate_pair(file_id_1)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dup_2 ON duplicate_pair(file_id_2)");
        } catch (SQLException e) {
            log.error("Failed to initialize database schema", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    // --- File Info CRUD ---

    public void upsertFile(FileRecord rec) {
        String sql = """
            INSERT INTO file_info (id, relative_path, file_name, file_size, checksum, created_at, modified_at, target_path, last_scanned)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(relative_path) DO UPDATE SET
                file_name = excluded.file_name,
                file_size = excluded.file_size,
                checksum = excluded.checksum,
                created_at = excluded.created_at,
                modified_at = excluded.modified_at,
                target_path = excluded.target_path,
                last_scanned = excluded.last_scanned,
                id = CASE WHEN file_info.id IS NOT NULL THEN file_info.id ELSE excluded.id END
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rec.id());
            ps.setString(2, rec.relativePath());
            ps.setString(3, rec.fileName());
            ps.setLong(4, rec.fileSize());
            ps.setString(5, rec.checksum());
            ps.setString(6, rec.createdAt());
            ps.setString(7, rec.modifiedAt());
            ps.setString(8, rec.targetPath());
            ps.setString(9, rec.lastScanned());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert file: {}", rec.relativePath(), e);
        }
    }

    public Optional<FileRecord> getByPath(String relativePath) {
        String sql = "SELECT * FROM file_info WHERE relative_path = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, relativePath);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapRecord(rs));
        } catch (SQLException e) {
            log.error("Failed to query file by path: {}", relativePath, e);
        }
        return Optional.empty();
    }

    public List<FileRecord> getAllFiles() {
        List<FileRecord> files = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM file_info ORDER BY relative_path")) {
            while (rs.next()) files.add(mapRecord(rs));
        } catch (SQLException e) {
            log.error("Failed to list all files", e);
        }
        return files;
    }

    public void removeByPath(String relativePath) {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM file_info WHERE relative_path = ?")) {
            ps.setString(1, relativePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to remove file: {}", relativePath, e);
        }
    }

    /** Remove records not seen in the latest scan. */
    public int removeStale(String scanTimestamp) {
        int removed = 0;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM file_info WHERE last_scanned < ?")) {
            ps.setString(1, scanTimestamp);
            removed = ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to remove stale files", e);
        }
        return removed;
    }

    // --- Duplicate Pairs ---

    public void clearDuplicates() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM duplicate_pair");
        } catch (SQLException e) {
            log.error("Failed to clear duplicates", e);
        }
    }

    public void addDuplicatePair(String fileId1, String fileId2) {
        // Ensure consistent ordering
        String a = fileId1.compareTo(fileId2) < 0 ? fileId1 : fileId2;
        String b = fileId1.compareTo(fileId2) < 0 ? fileId2 : fileId1;
        String sql = "INSERT OR IGNORE INTO duplicate_pair (file_id_1, file_id_2) VALUES (?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to add duplicate pair", e);
        }
    }

    public List<String[]> getAllDuplicatePairs() {
        List<String[]> pairs = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT file_id_1, file_id_2 FROM duplicate_pair")) {
            while (rs.next()) {
                pairs.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        } catch (SQLException e) {
            log.error("Failed to list duplicate pairs", e);
        }
        return pairs;
    }

    /** Get all duplicate partners for a given file ID. */
    public List<String> getDuplicatesOf(String fileId) {
        List<String> ids = new ArrayList<>();
        String sql = "SELECT file_id_2 FROM duplicate_pair WHERE file_id_1 = ? " +
                     "UNION SELECT file_id_1 FROM duplicate_pair WHERE file_id_2 = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fileId);
            ps.setString(2, fileId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getString(1));
        } catch (SQLException e) {
            log.error("Failed to get duplicates of: {}", fileId, e);
        }
        return ids;
    }

    // --- Helpers ---

    private FileRecord mapRecord(ResultSet rs) throws SQLException {
        return new FileRecord(
                rs.getString("id"),
                rs.getString("relative_path"),
                rs.getString("file_name"),
                rs.getLong("file_size"),
                rs.getString("checksum"),
                rs.getString("created_at"),
                rs.getString("modified_at"),
                rs.getString("target_path"),
                rs.getString("last_scanned")
        );
    }

    /** Get the path to the database file on disk. */
    public String getDbPath() {
        return dbUrl.replace("jdbc:sqlite:", "");
    }

    /** Gather database statistics. */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        String path = getDbPath();
        stats.put("path", path);

        // File size on disk
        java.io.File dbFile = new java.io.File(path);
        stats.put("sizeBytes", dbFile.exists() ? dbFile.length() : 0);

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Row counts
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file_info");
            if (rs.next()) stats.put("fileCount", rs.getInt(1));

            rs = stmt.executeQuery("SELECT COUNT(*) FROM duplicate_pair");
            if (rs.next()) stats.put("duplicatePairCount", rs.getInt(1));

            // Distinct checksums with duplicates
            rs = stmt.executeQuery(
                "SELECT COUNT(DISTINCT checksum) FROM file_info WHERE checksum IN " +
                "(SELECT checksum FROM file_info GROUP BY checksum HAVING COUNT(*) > 1)");
            if (rs.next()) stats.put("duplicateGroupCount", rs.getInt(1));

            // Files needing reorganization
            rs = stmt.executeQuery("SELECT COUNT(*) FROM file_info WHERE target_path IS NOT NULL AND target_path != ''");
            if (rs.next()) stats.put("needsReorgCount", rs.getInt(1));

            // Total tracked file size
            rs = stmt.executeQuery("SELECT COALESCE(SUM(file_size), 0) FROM file_info");
            if (rs.next()) stats.put("totalTrackedBytes", rs.getLong(1));

            // SQLite version
            rs = stmt.executeQuery("SELECT sqlite_version()");
            if (rs.next()) stats.put("sqliteVersion", rs.getString(1));

            // Page size and page count
            rs = stmt.executeQuery("PRAGMA page_size");
            if (rs.next()) stats.put("pageSize", rs.getInt(1));

            rs = stmt.executeQuery("PRAGMA page_count");
            if (rs.next()) stats.put("pageCount", rs.getInt(1));

            // Journal mode
            rs = stmt.executeQuery("PRAGMA journal_mode");
            if (rs.next()) stats.put("journalMode", rs.getString(1));

        } catch (SQLException e) {
            log.error("Failed to gather database stats", e);
            stats.put("error", e.getMessage());
        }
        return stats;
    }
}
