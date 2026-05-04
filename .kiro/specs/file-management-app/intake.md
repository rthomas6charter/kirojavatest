# Feature Intake

Ideas and initial analysis for features that may become part of the spec/requirements in a future version.

---

## Idea 1: Archive Media Ingestion and Comparison

**As a user**, I want to ingest the contents of an existing archive media set, with included-file dates, size, and checksum information, to compare with the contents of a data directory or remote share/connection and identify files that exist only in the archive or only in the directory/share.

### Initial Analysis

- Requires a mechanism to import file manifests (date, size, checksum) from physical archive media
- Comparison engine to diff archive contents against live data directory or remote connection
- Output should identify: files only in archive, files only in directory/share, files in both
- May integrate with existing Archive Templates for media format awareness
- Could surface results in a dedicated comparison view or as part of the existing File Browser
- Another output could be an archive job, using a selected archive template, to create an archive of only files that are "missing from the previous archive" (i.e., incremental archival of new/changed files)

### Status

Captured for future consideration. Not yet promoted to requirements.

---

## Idea 2: Preferred File Selection for Duplicate Sets

**As a user**, I want to select a preferred file from each duplicate set, so that when deduplication runs it keeps my chosen copy rather than arbitrarily keeping the first one found.

### Initial Analysis

- Each duplicate group should allow the user to mark one file as "preferred" (the keeper)
- A guided wizard/stepper UI walks through duplicate sets that don't yet have a preferred file selected
  - Shows side-by-side comparison of duplicates (path, dates, location)
  - User picks which copy to keep; selection is persisted
- The remove-duplicates script generation should respect the preferred selection (remove all except the preferred file)
- If no preferred file is selected for a group, fall back to current behavior (keep first)
- Preferred selections could be stored in the SQLite database (new column or table) or in a JSON file
- The duplicate inspector overlay (Requirement 1.6) could include a "Set as preferred" button per file
- Consider batch operations: "prefer files in shortest path" or "prefer newest/oldest"

### Status

Captured for future consideration. Not yet promoted to requirements.

---

## Idea 3: OpenAPI / Swagger Documentation Endpoint

**As a developer**, I want the application to serve auto-generated OpenAPI (Swagger) documentation at a well-known endpoint, so that I can explore and test the REST API interactively without reading source code.

### Initial Analysis

- Serve an OpenAPI 3.x spec (JSON or YAML) at `/api/docs` or `/api/openapi.json`
- Optionally bundle Swagger UI or Redoc for interactive exploration at `/api/docs/ui`
- Could use Javalin's OpenAPI plugin (`javalin-openapi`) or manually maintain a static spec file
- Should document all `/api/*` endpoints with request/response schemas, parameters, and status codes
- Authentication requirements should be documented (cookie-based session)
- Consider auto-generating from annotated route handlers vs. maintaining a hand-written spec

### Status

Captured for future consideration. Not yet promoted to requirements.
