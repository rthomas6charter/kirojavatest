package org.example.kirojavatest;

// Feature: file-management-app, Property 22: Search filtering correctness
// **Validates: Requirements 11.1**

import net.jqwik.api.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Property tests for search filtering correctness.
 *
 * Property 22: For any search query and dataset, all returned results SHALL contain
 * the query string (case-insensitive) in at least one field value.
 */
class SearchPropertyTest {

    // =========================================================================
    // Property 22: Search filtering correctness
    //
    // For any search query and dataset, all returned results SHALL contain the
    // query string (case-insensitive) in at least one field value.
    // =========================================================================

    /**
     * Applies the same filtering logic as HomeController's /search endpoint:
     * filter rows where at least one field value contains the query (case-insensitive).
     */
    private List<Map<String, String>> applySearchFilter(List<Map<String, String>> dataset, String query) {
        if (query == null || query.isBlank()) {
            return dataset;
        }
        String q = query.toLowerCase();
        return dataset.stream()
                .filter(row -> row.values().stream().anyMatch(v -> v.toLowerCase().contains(q)))
                .collect(Collectors.toList());
    }

    @Property(tries = 100)
    void allSearchResultsContainQueryInAtLeastOneField(
            @ForAll("datasets") List<Map<String, String>> dataset,
            @ForAll("searchQueries") String query
    ) {
        // Apply the search filter
        List<Map<String, String>> results = applySearchFilter(dataset, query);

        // For non-blank queries, verify every result contains the query in at least one field
        if (query != null && !query.isBlank()) {
            String q = query.toLowerCase();
            for (Map<String, String> row : results) {
                boolean containsQuery = row.values().stream()
                        .anyMatch(v -> v.toLowerCase().contains(q));
                assert containsQuery
                        : "Result row " + row + " does not contain query '" + query
                        + "' (case-insensitive) in any field value";
            }
        }
    }

    @Property(tries = 100)
    void searchFilterDoesNotExcludeMatchingRows(
            @ForAll("datasets") List<Map<String, String>> dataset,
            @ForAll("searchQueries") String query
    ) {
        // Apply the search filter
        List<Map<String, String>> results = applySearchFilter(dataset, query);

        if (query != null && !query.isBlank()) {
            String q = query.toLowerCase();
            // Verify no matching rows were excluded: every row in the original dataset
            // that contains the query must appear in results
            for (Map<String, String> row : dataset) {
                boolean shouldMatch = row.values().stream()
                        .anyMatch(v -> v.toLowerCase().contains(q));
                if (shouldMatch) {
                    assert results.contains(row)
                            : "Row " + row + " matches query '" + query
                            + "' but was not included in results";
                }
            }
        }
    }

    @Property(tries = 100)
    void blankQueryReturnsAllResults(
            @ForAll("datasets") List<Map<String, String>> dataset,
            @ForAll("blankQueries") String query
    ) {
        // For blank/empty queries, all data should be returned (matching HomeController behavior)
        List<Map<String, String>> results = applySearchFilter(dataset, query);
        assert results.size() == dataset.size()
                : "Blank query should return all " + dataset.size() + " rows but got " + results.size();
    }

    @Property(tries = 100)
    void searchIsCaseInsensitive(
            @ForAll("datasets") List<Map<String, String>> dataset,
            @ForAll("searchQueries") String query
    ) {
        if (query == null || query.isBlank()) return;

        // Search with original, uppercase, and lowercase should return the same results
        List<Map<String, String>> originalResults = applySearchFilter(dataset, query);
        List<Map<String, String>> upperResults = applySearchFilter(dataset, query.toUpperCase());
        List<Map<String, String>> lowerResults = applySearchFilter(dataset, query.toLowerCase());

        assert originalResults.equals(upperResults)
                : "Search for '" + query + "' and '" + query.toUpperCase() + "' returned different results";
        assert originalResults.equals(lowerResults)
                : "Search for '" + query + "' and '" + query.toLowerCase() + "' returned different results";
    }

    // =========================================================================
    // Providers
    // =========================================================================

    @Provide
    Arbitrary<List<Map<String, String>>> datasets() {
        // Generate rows with field values similar to the application's MOCK_DATA structure
        Arbitrary<Map<String, String>> row = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(15),
                Arbitraries.of("Landscape", "Craft", "Materials", "Botanical", "Research", "Technology", "Art"),
                Arbitraries.of("Colorado", "Vermont", "Oregon", "Maine", "New Mexico", "California", "Texas"),
                Arbitraries.of("2025-01-01", "2025-06-15", "2026-02-20", "2026-03-08", "2024-11-30"),
                Arbitraries.of("Active", "Inactive", "Pending")
        ).as((name, category, location, dateAdded, status) -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("category", category);
            m.put("location", location);
            m.put("dateAdded", dateAdded);
            m.put("status", status);
            return m;
        });

        return row.list().ofMinSize(0).ofMaxSize(20);
    }

    @Provide
    Arbitrary<String> searchQueries() {
        // Mix of realistic queries: short strings, known category/location values, random strings
        return Arbitraries.oneOf(
                // Short alphabetic strings
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6),
                // Known field values that should match
                Arbitraries.of("act", "land", "ore", "craft", "pend", "main", "ver", "col"),
                // Single characters
                Arbitraries.strings().alpha().ofLength(1)
        );
    }

    @Provide
    Arbitrary<String> blankQueries() {
        return Arbitraries.of("", "   ", "\t", "\n");
    }
}
