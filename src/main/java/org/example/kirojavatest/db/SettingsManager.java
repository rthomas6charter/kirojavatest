package org.example.kirojavatest.db;

import org.example.kirojavatest.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages application settings stored as a JSON file in the data directory.
 * Settings persist across server restarts.
 */
public class SettingsManager {

    private static final Logger log = LoggerFactory.getLogger(SettingsManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path settingsFile;
    private Map<String, Object> settings;

    public SettingsManager() {
        String dataDir = AppConfig.get("app.data.dir", ".");
        this.settingsFile = Paths.get(dataDir).toAbsolutePath().resolve(".ui-state").resolve("settings.json");
        this.settings = load();
        applyDefaults();
        save();
    }

    /** Get a setting value, or the default if not set. */
    public Object get(String key, Object defaultValue) {
        return settings.getOrDefault(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = settings.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    public String getString(String key, String defaultValue) {
        Object val = settings.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    /** Update a single setting. */
    public void set(String key, Object value) {
        settings.put(key, value);
        save();
    }

    /** Update multiple settings at once. */
    public void setAll(Map<String, Object> updates) {
        settings.putAll(updates);
        save();
    }

    /** Get all settings as a read-only map. */
    public Map<String, Object> getAll() {
        return new LinkedHashMap<>(settings);
    }

    // --- Internals ---

    private void applyDefaults() {
        settings.putIfAbsent("liveMode", false);
        settings.putIfAbsent("directoryStructure", "reverse-date");
        settings.putIfAbsent("theme", "light");
        settings.putIfAbsent("backgroundTaskTimeout", 300);
        settings.putIfAbsent("backgroundQueueThreshold", 10);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        if (!Files.exists(settingsFile)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(Files.readString(settingsFile), LinkedHashMap.class);
        } catch (IOException e) {
            log.warn("Failed to load settings, using defaults", e);
            return new LinkedHashMap<>();
        }
    }

    private void save() {
        try {
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
        } catch (IOException e) {
            log.error("Failed to save settings", e);
        }
    }
}
