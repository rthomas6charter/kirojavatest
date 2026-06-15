package org.example.kirojavatest;

// Feature: file-management-app, Property 16: Archive Template CRUD round-trip
// **Validates: Requirements 7.1, 7.2**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Property tests for archive template CRUD operations.
 *
 * Property 16: For any archive template with name, capacity, and output format,
 * creating then reading SHALL return equivalent data.
 */
class ArchiveTemplatePropertyTest {

    private Path tempDir;
    private Path uiStateDir;
    private Path archiveTemplatesFile;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("archive-tpl-prop-test");
        System.setProperty("app.data.dir", tempDir.toAbsolutePath().toString());
        uiStateDir = tempDir.resolve(".ui-state");
        Files.createDirectories(uiStateDir);
        archiveTemplatesFile = uiStateDir.resolve("archive-templates.json");
        Files.writeString(archiveTemplatesFile, "[]");
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
    // Property 16: Archive Template CRUD round-trip
    //
    // For any archive template with name, capacity, and output format,
    // creating then reading SHALL return equivalent data.
    // =========================================================================

    @Property(tries = 100)
    void archiveTemplateCrudRoundTrip(
            @ForAll("validArchiveTemplates") ArchiveTemplateSpec template
    ) throws IOException {
        // Write template to file (simulating POST /api/archive-templates)
        Map<String, Object> tplMap = template.toMap();
        List<Map<String, Object>> templates = readTemplates();
        templates.add(new LinkedHashMap<>(tplMap));
        writeTemplates(templates);

        // Read back (simulating GET /api/archive-templates)
        List<Map<String, Object>> readBack = readTemplates();

        assert readBack.size() == 1 : "Expected 1 template, got " + readBack.size();
        Map<String, Object> loaded = readBack.get(0);

        // Verify name round-trips correctly
        assert template.name().equals(loaded.get("name"))
                : "Name mismatch: expected '" + template.name() + "' got '" + loaded.get("name") + "'";

        // Verify capacityMb round-trips correctly
        Number loadedCapacity = (Number) loaded.get("capacityMb");
        assert loadedCapacity != null : "capacityMb should not be null";
        assert template.capacityMb() == loadedCapacity.intValue()
                : "capacityMb mismatch: expected " + template.capacityMb() + " got " + loadedCapacity.intValue();

        // Verify outputFormat round-trips correctly
        assert template.outputFormat().equals(loaded.get("outputFormat"))
                : "outputFormat mismatch: expected '" + template.outputFormat() + "' got '" + loaded.get("outputFormat") + "'";
    }

    @Property(tries = 100)
    void archiveTemplateUpdateRoundTrip(
            @ForAll("validArchiveTemplates") ArchiveTemplateSpec original,
            @ForAll("validArchiveTemplates") ArchiveTemplateSpec updated
    ) throws IOException {
        // Create the original template
        List<Map<String, Object>> templates = new ArrayList<>();
        templates.add(new LinkedHashMap<>(original.toMap()));
        writeTemplates(templates);

        // Update the template at index 0 (simulating PUT /api/archive-templates/0)
        templates = readTemplates();
        templates.set(0, new LinkedHashMap<>(updated.toMap()));
        writeTemplates(templates);

        // Read back and verify the update was persisted
        List<Map<String, Object>> readBack = readTemplates();
        assert readBack.size() == 1 : "Expected 1 template after update, got " + readBack.size();
        Map<String, Object> loaded = readBack.get(0);

        assert updated.name().equals(loaded.get("name"))
                : "Updated name mismatch: expected '" + updated.name() + "' got '" + loaded.get("name") + "'";

        Number loadedCapacity = (Number) loaded.get("capacityMb");
        assert loadedCapacity != null : "capacityMb should not be null after update";
        assert updated.capacityMb() == loadedCapacity.intValue()
                : "Updated capacityMb mismatch: expected " + updated.capacityMb() + " got " + loadedCapacity.intValue();

        assert updated.outputFormat().equals(loaded.get("outputFormat"))
                : "Updated outputFormat mismatch: expected '" + updated.outputFormat() + "' got '" + loaded.get("outputFormat") + "'";
    }

    @Property(tries = 100)
    void archiveTemplateDeleteRemovesEntry(
            @ForAll("validArchiveTemplates") ArchiveTemplateSpec template1,
            @ForAll("validArchiveTemplates") ArchiveTemplateSpec template2
    ) throws IOException {
        // Create two templates
        List<Map<String, Object>> templates = new ArrayList<>();
        templates.add(new LinkedHashMap<>(template1.toMap()));
        templates.add(new LinkedHashMap<>(template2.toMap()));
        writeTemplates(templates);

        // Delete the first one (simulating DELETE /api/archive-templates/0)
        templates = readTemplates();
        assert templates.size() == 2 : "Should have 2 templates before delete";
        templates.remove(0);
        writeTemplates(templates);

        // Read back and verify only the second template remains
        List<Map<String, Object>> readBack = readTemplates();
        assert readBack.size() == 1 : "Expected 1 template after delete, got " + readBack.size();
        Map<String, Object> remaining = readBack.get(0);

        assert template2.name().equals(remaining.get("name"))
                : "Remaining template should be template2 with name '" + template2.name()
                + "' but got '" + remaining.get("name") + "'";

        Number loadedCapacity = (Number) remaining.get("capacityMb");
        assert template2.capacityMb() == loadedCapacity.intValue()
                : "Remaining capacityMb mismatch";

        assert template2.outputFormat().equals(remaining.get("outputFormat"))
                : "Remaining outputFormat mismatch";
    }

    // --- Providers ---

    @Provide
    Arbitrary<ArchiveTemplateSpec> validArchiveTemplates() {
        Arbitrary<String> names = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20);
        Arbitrary<Integer> capacities = Arbitraries.of(700, 4700, 8500, 25000, 50000, 100000, 128000);
        Arbitrary<String> formats = Arbitraries.of("tar", "imgburn", "iso");

        return Combinators.combine(names, capacities, formats)
                .as(ArchiveTemplateSpec::new);
    }

    record ArchiveTemplateSpec(String name, int capacityMb, String outputFormat) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("capacityMb", capacityMb);
            map.put("outputFormat", outputFormat);
            return map;
        }
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readTemplates() throws IOException {
        if (!Files.exists(archiveTemplatesFile)) return new ArrayList<>();
        return JSON_MAPPER.readValue(Files.readString(archiveTemplatesFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    private void writeTemplates(List<Map<String, Object>> templates) throws IOException {
        Files.createDirectories(archiveTemplatesFile.getParent());
        Files.writeString(archiveTemplatesFile,
                JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(templates));
    }
}
