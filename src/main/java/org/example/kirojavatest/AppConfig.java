package org.example.kirojavatest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {

    private static final Properties props = new Properties();

    static {
        try (InputStream in = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }
    }

    /** Get a property value, falling back to a default. System properties (-D flags) take precedence. */
    public static String get(String key, String defaultValue) {
        String sys = System.getProperty(key);
        if (sys != null) {
            return sys;
        }
        String val = props.getProperty(key, defaultValue);
        return val != null ? resolveplaceholders(val) : null;
    }

    public static int getInt(String key, int defaultValue) {
        String val = get(key, null);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    /** Replace ${...} placeholders with system property values. */
    private static String resolveplaceholders(String value) {
        int start;
        while ((start = value.indexOf("${")) >= 0) {
            int end = value.indexOf('}', start);
            if (end < 0) break;
            String propName = value.substring(start + 2, end);
            String resolved = System.getProperty(propName, "");
            value = value.substring(0, start) + resolved + value.substring(end + 1);
        }
        return value;
    }
}
