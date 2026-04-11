package org.example.kirojavatest.fileanalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Utility for computing date-based directory paths (YYYY/MM/DD) from file creation dates. */
public class DatePathUtil {

    /**
     * Returns the date-based directory path for a file: YYYY/MM/DD.
     * Based on the file's creation time.
     */
    public static String datePath(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        Instant created = attrs.creationTime().toInstant();
        ZonedDateTime zdt = created.atZone(ZoneId.systemDefault());
        return String.format("%04d/%02d/%02d", zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
    }

    /**
     * Returns the full target path (YYYY/MM/DD/filename) for a file.
     */
    public static String targetPath(Path file) throws IOException {
        return datePath(file) + "/" + file.getFileName().toString();
    }

    /**
     * Checks whether a file's current relative path already matches its date-based path.
     * @param relativePath the file's path relative to the data root
     * @param file the absolute path to the file
     * @return true if the file is already in the correct date directory
     */
    public static boolean isInCorrectDatePath(String relativePath, Path file) throws IOException {
        String expected = targetPath(file);
        return relativePath.equals(expected);
    }
}
