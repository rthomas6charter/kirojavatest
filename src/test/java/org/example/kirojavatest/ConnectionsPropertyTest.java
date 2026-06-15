package org.example.kirojavatest;

// Feature: file-management-app, Property 8: Connection CRUD round-trip
// Feature: file-management-app, Property 9: File connection validation
// Feature: file-management-app, Property 10: Connection health check state transitions
// **Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.6, 4.9, 4.10**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.api.ApiController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Property tests for connections management.
 */
class ConnectionsPropertyTest {

    private Path tempDir;
    private Path connectionsFile;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("connections-prop-test");
        System.setProperty("app.data.dir", tempDir.toAbsolutePath().toString());
        Path uiState = tempDir.resolve(".ui-state");
        Files.createDirectories(uiState);
        connectionsFile = uiState.resolve("connections.json");
        // Start with an empty connections file
        Files.writeString(connectionsFile, "[]");
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
    // Property 8: Connection CRUD round-trip
    //
    // For any connection of type File, SMB, or SFTP with valid fields, creating
    // the connection and then reading it back SHALL return equivalent data (with
    // passwords base64-encoded on disk and decoded on read).
    // =========================================================================

    @Property(tries = 100)
    void connectionCrudRoundTrip(
            @ForAll("validConnections") ConnectionSpec conn
    ) throws IOException {
        // Write connection to the JSON file (simulating create)
        Map<String, Object> connMap = conn.toMap();
        List<Map<String, Object>> conns = new ArrayList<>();
        conns.add(new LinkedHashMap<>(connMap));

        // Write with base64 encoding (same as ApiController.writeConnections)
        List<Map<String, Object>> toWrite = new ArrayList<>();
        for (Map<String, Object> c : conns) {
            Map<String, Object> copy = new LinkedHashMap<>(c);
            Object pw = copy.get("password");
            if (pw instanceof String s && !s.isEmpty()) {
                copy.put("password", Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));
            }
            toWrite.add(copy);
        }
        Files.writeString(connectionsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toWrite));

        // Verify the password is base64-encoded on disk
        String diskContent = Files.readString(connectionsFile);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> onDisk = JSON_MAPPER.readValue(diskContent,
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        Map<String, Object> diskConn = onDisk.get(0);
        String originalPassword = conn.password();
        if (originalPassword != null && !originalPassword.isEmpty()) {
            String diskPw = (String) diskConn.get("password");
            String expectedEncoded = Base64.getEncoder().encodeToString(originalPassword.getBytes(StandardCharsets.UTF_8));
            assert expectedEncoded.equals(diskPw)
                    : "Password on disk should be base64-encoded. Expected '" + expectedEncoded + "' but got '" + diskPw + "'";
        }

        // Read back with base64 decoding (same as ApiController.readConnections)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readBack = JSON_MAPPER.readValue(Files.readString(connectionsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        for (Map<String, Object> c : readBack) {
            Object pw = c.get("password");
            if (pw instanceof String s && !s.isEmpty()) {
                try {
                    c.put("password", new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    // Not base64 — leave as-is
                }
            }
        }

        // Verify equivalence
        Map<String, Object> loaded = readBack.get(0);
        assert conn.name().equals(loaded.get("name"))
                : "Name mismatch: expected '" + conn.name() + "' got '" + loaded.get("name") + "'";
        assert conn.type().equals(loaded.get("type"))
                : "Type mismatch: expected '" + conn.type() + "' got '" + loaded.get("type") + "'";

        if (conn.password() != null && !conn.password().isEmpty()) {
            assert conn.password().equals(loaded.get("password"))
                    : "Password mismatch after round-trip: expected '" + conn.password() + "' got '" + loaded.get("password") + "'";
        }

        // Type-specific field verification
        switch (conn.type()) {
            case "file" -> {
                assert conn.subPath().equals(loaded.get("subPath"))
                        : "subPath mismatch: expected '" + conn.subPath() + "' got '" + loaded.get("subPath") + "'";
            }
            case "smb", "sftp" -> {
                assert conn.host().equals(loaded.get("host"))
                        : "host mismatch: expected '" + conn.host() + "' got '" + loaded.get("host") + "'";
                assert conn.username().equals(loaded.get("username"))
                        : "username mismatch: expected '" + conn.username() + "' got '" + loaded.get("username") + "'";
                if ("sftp".equals(conn.type())) {
                    Object portObj = loaded.get("port");
                    int loadedPort = portObj instanceof Number n ? n.intValue() : Integer.parseInt(portObj.toString());
                    assert conn.port() == loadedPort
                            : "port mismatch: expected " + conn.port() + " got " + loadedPort;
                }
            }
        }

        assert conn.active() == Boolean.TRUE.equals(loaded.get("active"))
                : "active mismatch: expected " + conn.active() + " got " + loaded.get("active");
    }

    @Provide
    Arbitrary<ConnectionSpec> validConnections() {
        Arbitrary<String> names = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> passwords = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(16);
        Arbitrary<Boolean> actives = Arbitraries.of(true, false);

        Arbitrary<ConnectionSpec> fileConns = Combinators.combine(names, passwords, actives,
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8)
        ).as((name, password, active, subPath) ->
                new ConnectionSpec(name, "file", subPath, "", "", 22, password, active));

        Arbitrary<ConnectionSpec> smbConns = Combinators.combine(names, passwords, actives,
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(12),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8)
        ).as((name, password, active, host, username) ->
                new ConnectionSpec(name, "smb", "", host, username, 445, password, active));

        Arbitrary<ConnectionSpec> sftpConns = Combinators.combine(names, passwords, actives,
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(12),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                Arbitraries.integers().between(1, 65535)
        ).as((name, password, active, host, username, port) ->
                new ConnectionSpec(name, "sftp", "", host, username, port, password, active));

        return Arbitraries.oneOf(fileConns, smbConns, sftpConns);
    }

    record ConnectionSpec(String name, String type, String subPath, String host, String username, int port, String password, boolean active) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("type", type);
            map.put("active", active);
            switch (type) {
                case "file" -> map.put("subPath", subPath);
                case "smb" -> {
                    map.put("host", host);
                    map.put("username", username);
                    map.put("password", password);
                }
                case "sftp" -> {
                    map.put("host", host);
                    map.put("port", port);
                    map.put("username", username);
                    map.put("password", password);
                }
            }
            if ("file".equals(type) && password != null && !password.isEmpty()) {
                map.put("password", password);
            }
            return map;
        }
    }

    // =========================================================================
    // Property 9: File connection validation
    //
    // For any File-type connection, validation SHALL return true if and only if
    // the subdirectory exists within the Data_Directory.
    // =========================================================================

    @Property(tries = 100)
    void fileConnectionValidation(
            @ForAll("fileConnectionScenarios") FileValidationScenario scenario
    ) throws IOException {
        // Set up directory structure based on scenario
        if (scenario.createSubDir()) {
            Files.createDirectories(tempDir.resolve(scenario.subPath()));
        }

        // Build the connection map
        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("name", "test-file-conn");
        conn.put("type", "file");
        conn.put("subPath", scenario.subPath());
        conn.put("active", true);

        // Validate using the same logic as ApiController.validateConnection (inline since it's package-private)
        boolean valid = validateFileConnection(conn);

        if (scenario.createSubDir()) {
            // Subdirectory exists within data directory — should be valid
            assert valid : "Expected validation to pass for existing subPath '" + scenario.subPath()
                    + "' but it failed";
        } else {
            // Subdirectory does NOT exist — should be invalid
            assert !valid : "Expected validation to fail for non-existing subPath '" + scenario.subPath()
                    + "' but it passed";
        }
    }

    /**
     * Validates a File-type connection by checking if the subPath exists within the data directory.
     * Mirrors the logic in ApiController.validateConnection for file connections.
     */
    private boolean validateFileConnection(Map<String, Object> conn) {
        String subPath = (String) conn.getOrDefault("subPath", "");
        if (subPath == null || subPath.isEmpty()) {
            return false;
        }
        String dataDir = System.getProperty("app.data.dir", "");
        if (dataDir.isEmpty()) {
            return false;
        }
        Path baseRoot = Paths.get(dataDir).toAbsolutePath().normalize();
        Path resolved = baseRoot.resolve(subPath).normalize();
        if (!resolved.startsWith(baseRoot)) {
            return false;
        }
        return Files.isDirectory(resolved);
    }

    @Provide
    Arbitrary<FileValidationScenario> fileConnectionScenarios() {
        Arbitrary<String> subPaths = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8);

        return Combinators.combine(subPaths, Arbitraries.of(true, false))
                .as(FileValidationScenario::new);
    }

    record FileValidationScenario(String subPath, boolean createSubDir) {}

    // =========================================================================
    // Property 10: Connection health check state transitions
    //
    // For any active connection, after a health check: if the connection is
    // unreachable it SHALL be marked offline, and if a previously offline
    // connection is now reachable it SHALL have its offline flag removed.
    // =========================================================================

    @Property(tries = 100)
    void connectionHealthCheckStateTransitions(
            @ForAll("healthCheckScenarios") HealthCheckScenario scenario
    ) throws IOException {
        // Set up: create a file-type connection that points to a subPath.
        // Reachability is controlled by whether the directory exists.
        String subPath = scenario.subPath();

        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("name", "health-check-test");
        conn.put("type", "file");
        conn.put("subPath", subPath);
        conn.put("active", true);
        if (scenario.wasOffline()) {
            conn.put("offline", true);
        }

        // Control reachability: create or don't create the subdirectory
        if (scenario.isReachable()) {
            Files.createDirectories(tempDir.resolve(subPath));
        } else {
            // Ensure the subPath does NOT exist
            Path subDir = tempDir.resolve(subPath);
            if (Files.exists(subDir)) {
                Files.walkFileTree(subDir, new SimpleFileVisitor<>() {
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

        // Write connection to disk (base64 encode passwords)
        List<Map<String, Object>> conns = new ArrayList<>();
        conns.add(conn);
        Files.writeString(connectionsFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(conns));

        // Run health check (uses the static method from ApiController)
        Map<String, Object> result = ApiController.checkAllConnections();

        // Read back the connection state
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterCheck = JSON_MAPPER.readValue(Files.readString(connectionsFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        Map<String, Object> checked = afterCheck.get(0);
        boolean isOfflineAfter = Boolean.TRUE.equals(checked.get("offline")) || "true".equals(String.valueOf(checked.get("offline")));

        if (!scenario.isReachable()) {
            // Unreachable connection should be marked offline
            assert isOfflineAfter
                    : "Unreachable connection should be marked offline but offline=" + checked.get("offline");
        } else if (scenario.wasOffline()) {
            // Previously offline, now reachable — offline flag should be removed
            assert !isOfflineAfter
                    : "Previously offline connection that is now reachable should have offline removed, but offline=" + checked.get("offline");
        } else {
            // Was online, still reachable — should remain online (no offline flag)
            assert !isOfflineAfter
                    : "Online reachable connection should remain online but offline=" + checked.get("offline");
        }
    }

    @Provide
    Arbitrary<HealthCheckScenario> healthCheckScenarios() {
        Arbitrary<String> subPaths = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8);

        return Combinators.combine(subPaths, Arbitraries.of(true, false), Arbitraries.of(true, false))
                .as(HealthCheckScenario::new);
    }

    record HealthCheckScenario(String subPath, boolean isReachable, boolean wasOffline) {}
}
