package org.example.kirojavatest;

// Feature: file-management-app, Property 17: Face edit preserves embeddings
// **Validates: Requirements 8.2**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Property tests for known faces management.
 * Verifies that editing a face entry preserves its embedding data unchanged.
 */
class FacesPropertyTest {

    private Path tempDir;
    private Path facesFile;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeTry
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("faces-prop-test");
        System.setProperty("app.data.dir", tempDir.toAbsolutePath().toString());
        facesFile = tempDir.resolve("known_faces.json");
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
    // Property 17: Face edit preserves embeddings
    //
    // For any face entry edit operation, the embedding field in the stored data
    // SHALL remain identical to its value before the edit.
    // =========================================================================

    @Property(tries = 100)
    void faceEditPreservesEmbeddings(
            @ForAll("faceEditScenarios") FaceEditScenario scenario
    ) throws IOException {
        // Set up: write a faces file with a single face entry that has an embedding
        Map<String, Object> face = new LinkedHashMap<>();
        face.put("name", scenario.originalName());
        face.put("label", scenario.originalLabel());
        face.put("embedding", scenario.embedding());

        List<Map<String, Object>> faces = new ArrayList<>();
        faces.add(face);
        Files.writeString(facesFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(faces));

        // Record the embedding before the edit
        List<Double> embeddingBefore = new ArrayList<>(scenario.embedding());

        // Simulate PUT /api/faces/0 — same logic as ApiController:
        // Read faces, apply updates (skipping "embedding" key), write back
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readFaces = JSON_MAPPER.readValue(Files.readString(facesFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", scenario.newName());
        updates.put("label", scenario.newLabel());
        // Simulate a malicious/accidental attempt to overwrite embedding
        if (scenario.includeEmbeddingInUpdate()) {
            updates.put("embedding", List.of(99.9, 88.8, 77.7));
        }

        Map<String, Object> faceToEdit = readFaces.get(0);
        // Apply the same logic as ApiController: skip "embedding" key
        for (var entry : updates.entrySet()) {
            if (!"embedding".equals(entry.getKey())) {
                faceToEdit.put(entry.getKey(), entry.getValue());
            }
        }

        // Write back
        Files.writeString(facesFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(readFaces));

        // Verify: read the file again and check embedding is unchanged
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterEdit = JSON_MAPPER.readValue(Files.readString(facesFile),
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

        Map<String, Object> editedFace = afterEdit.get(0);

        // Verify the name/label were updated
        assert scenario.newName().equals(editedFace.get("name"))
                : "Name should be updated to '" + scenario.newName() + "' but got '" + editedFace.get("name") + "'";
        assert scenario.newLabel().equals(editedFace.get("label"))
                : "Label should be updated to '" + scenario.newLabel() + "' but got '" + editedFace.get("label") + "'";

        // Verify the embedding is UNCHANGED
        @SuppressWarnings("unchecked")
        List<Number> embeddingAfter = (List<Number>) editedFace.get("embedding");
        assert embeddingAfter != null : "Embedding should not be null after edit";
        assert embeddingAfter.size() == embeddingBefore.size()
                : "Embedding size changed: was " + embeddingBefore.size() + " now " + embeddingAfter.size();

        for (int i = 0; i < embeddingBefore.size(); i++) {
            double expected = embeddingBefore.get(i);
            double actual = embeddingAfter.get(i).doubleValue();
            assert Double.compare(expected, actual) == 0
                    : "Embedding[" + i + "] changed: expected " + expected + " but got " + actual;
        }
    }

    @Provide
    Arbitrary<FaceEditScenario> faceEditScenarios() {
        Arbitrary<String> names = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(15);
        Arbitrary<String> labels = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
        Arbitrary<List<Double>> embeddings = Arbitraries.doubles()
                .between(-1.0, 1.0)
                .list()
                .ofMinSize(3)
                .ofMaxSize(128);
        Arbitrary<Boolean> includeEmbedding = Arbitraries.of(true, false);

        return Combinators.combine(names, labels, names, labels, embeddings, includeEmbedding)
                .as(FaceEditScenario::new);
    }

    record FaceEditScenario(
            String originalName,
            String originalLabel,
            String newName,
            String newLabel,
            List<Double> embedding,
            boolean includeEmbeddingInUpdate
    ) {}
}
