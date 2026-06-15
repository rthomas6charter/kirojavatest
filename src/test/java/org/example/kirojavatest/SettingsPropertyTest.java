package org.example.kirojavatest;

// Feature: file-management-app, Property 21: Settings persistence round-trip
// **Validates: Requirements 10.6**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.db.SettingsManager;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Property tests for settings persistence.
 */
class SettingsPropertyTest {

    private Path tempDir;

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("settings-prop-test");
        System.setProperty("app.data.dir", tempDir.toAbsolutePath().toString());
        // Create the .ui-state directory so SettingsManager can write to it
        Files.createDirectories(tempDir.resolve(".ui-state"));
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
    // Property 21: Settings persistence round-trip
    //
    // For any map of settings key-value pairs, setting them via SettingsManager
    // and then reading them back SHALL return equivalent values.
    // =========================================================================

    @Property(tries = 100)
    void settingsPersistenceRoundTrip(
            @ForAll("settingsMaps") Map<String, Object> inputSettings
    ) {
        // Create a fresh SettingsManager (loads/creates settings.json)
        SettingsManager manager = new SettingsManager();

        // Set all the random settings
        manager.setAll(inputSettings);

        // Create a new SettingsManager that re-reads from disk
        SettingsManager reloaded = new SettingsManager();

        // Verify all input settings are present and equivalent after reload
        Map<String, Object> allAfterReload = reloaded.getAll();

        for (Map.Entry<String, Object> entry : inputSettings.entrySet()) {
            String key = entry.getKey();
            Object expectedValue = entry.getValue();
            Object actualValue = allAfterReload.get(key);

            // Jackson may deserialize Integer values as Integer, so compare via toString
            // for numeric types to handle int/long ambiguity
            if (expectedValue instanceof Number && actualValue instanceof Number) {
                assert ((Number) expectedValue).doubleValue() == ((Number) actualValue).doubleValue()
                        : "Setting '" + key + "' value mismatch: expected " + expectedValue + " got " + actualValue;
            } else {
                assert Objects.equals(expectedValue, actualValue)
                        : "Setting '" + key + "' value mismatch: expected '" + expectedValue + "' got '" + actualValue + "'";
            }
        }
    }

    @Property(tries = 100)
    void settingsSingleSetRoundTrip(
            @ForAll("settingsKeys") String key,
            @ForAll("settingsValues") Object value
    ) {
        // Create a fresh SettingsManager
        SettingsManager manager = new SettingsManager();

        // Set a single setting
        manager.set(key, value);

        // Create a new SettingsManager that re-reads from disk
        SettingsManager reloaded = new SettingsManager();

        // Verify the setting is persisted
        Map<String, Object> all = reloaded.getAll();
        Object actual = all.get(key);

        if (value instanceof Number && actual instanceof Number) {
            assert ((Number) value).doubleValue() == ((Number) actual).doubleValue()
                    : "Single set '" + key + "' mismatch: expected " + value + " got " + actual;
        } else {
            assert Objects.equals(value, actual)
                    : "Single set '" + key + "' mismatch: expected '" + value + "' got '" + actual + "'";
        }
    }

    @Provide
    Arbitrary<Map<String, Object>> settingsMaps() {
        Arbitrary<String> keys = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(12);

        Arbitrary<Object> values = Arbitraries.oneOf(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.integers().between(0, 10000).map(i -> (Object) i),
                Arbitraries.of(true, false).map(b -> (Object) b)
        );

        return Arbitraries.maps(keys, values)
                .ofMinSize(1)
                .ofMaxSize(10);
    }

    @Provide
    Arbitrary<String> settingsKeys() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(12);
    }

    @Provide
    Arbitrary<Object> settingsValues() {
        return Arbitraries.oneOf(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.integers().between(0, 10000).map(i -> (Object) i),
                Arbitraries.of(true, false).map(b -> (Object) b)
        );
    }
}
