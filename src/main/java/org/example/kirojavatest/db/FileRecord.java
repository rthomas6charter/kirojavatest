package org.example.kirojavatest.db;

/**
 * Represents a scanned file's metadata stored in the database.
 *
 * @param id           Globally unique identifier (UUID)
 * @param relativePath Path relative to the data directory root
 * @param fileName     Leaf file name
 * @param fileSize     Size in bytes
 * @param checksum     SHA-256 checksum of file content
 * @param createdAt    ISO-8601 creation timestamp
 * @param modifiedAt   ISO-8601 last modified timestamp
 * @param targetPath   Suggested YYYY/MM/DD/filename path (null if already correct)
 * @param lastScanned  ISO-8601 timestamp of the last scan that saw this file
 */
public record FileRecord(
        String id,
        String relativePath,
        String fileName,
        long fileSize,
        String checksum,
        String createdAt,
        String modifiedAt,
        String targetPath,
        String lastScanned
) {}
