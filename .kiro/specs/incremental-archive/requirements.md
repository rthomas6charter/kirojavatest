# Requirements Document: Incremental Archive Sets (Future Feature)

## Introduction

This document captures future feature requirements for incremental archive functionality. This feature extends the existing archive template system to support capturing archive manifests, comparing current directory state against previous archives, and creating incremental archive sets that contain only the changes since the last full or incremental archive.

## Glossary

- **Archive_Set**: A completed archive operation with a manifest recording all files included, their paths, sizes, and checksums
- **Archive_Manifest**: The metadata record of an archive set, containing file path+name (relative to archive root), file size, and checksum for every file included
- **Incremental_Archive_Set**: An archive set containing only files that are new or modified since the base archive (with all prior incrementals applied)
- **Base_Archive**: The original full archive set from which incremental archives are derived
- **Archive_Chain**: The ordered sequence of a base archive plus all its linked incremental archives, representing a complete restorable state
- **Effective_State**: The complete set of files as they would exist after applying the base archive and all incremental archives in order

## Requirements

### Requirement 1: Archive Manifest Capture

**User Story:** As a user, I want the system to record the details of files included in an archive set, so that I can later determine what has changed and create incremental archives.

#### Acceptance Criteria

1. WHEN an archive set is created, THE system SHALL capture a manifest containing the file path and name (relative to the archive root directory), file size, and SHA-256 checksum for every file included
2. THE system SHALL persist the archive manifest so it is available across application restarts
3. THE system SHALL associate each archive manifest with a unique identifier, creation timestamp, and the archive template settings used
4. THE system SHALL allow the user to view the manifest of any previously created archive set (file list, total size, file count)

### Requirement 2: Incremental Archive Creation

**User Story:** As a user, I want to select an existing archive set and create an incremental archive containing only the changes, so that I can efficiently back up new and modified files without re-archiving everything.

#### Acceptance Criteria

1. THE system SHALL allow the user to select an existing archive set as the base for an incremental archive
2. WHEN creating an incremental archive, THE system SHALL compare the current managed root directory against the effective state of the base archive (with all prior incrementals applied)
3. THE incremental archive SHALL include: files that are new (not present in the effective state), and files that are modified (present but with a different checksum or size)
4. THE incremental archive SHALL support the same options as a full archive: splitting by media capacity, output format (tar, imgburn, iso), and compression settings
5. THE system SHALL display a preview of changes (new files, modified files, deleted files, total incremental size) before the user confirms creation
6. WHEN an incremental archive references deleted files (present in effective state but no longer in the managed root), THE system SHALL record those deletions in the incremental manifest so they can be applied during restore

### Requirement 3: Archive Chain Linking

**User Story:** As a user, I want incremental archives to be linked to their base archive and prior incrementals, so that the system can track the full history and compute the effective state at any point.

#### Acceptance Criteria

1. WHEN an incremental archive set is created, THE system SHALL record its manifest and link it to the original base archive
2. THE system SHALL maintain an ordered chain: base archive → incremental 1 → incremental 2 → ... → incremental N
3. WHEN computing the effective state for a new incremental, THE system SHALL start from the base archive manifest and apply all previous incremental manifests in order (additions, modifications, and deletions)
4. THE system SHALL allow multiple incremental archives to be chained from the same base archive
5. THE system SHALL display the archive chain for any base archive, showing all linked incrementals with their creation dates and sizes

### Requirement 4: Restore Capability

**User Story:** As a user, I want to be able to use any incremental archive set (combined with its base and prior incrementals) to restore a complete set of files as they existed when that incremental was created.

#### Acceptance Criteria

1. THE system SHALL allow the user to select any archive in a chain and generate a restore plan showing the complete file set as it existed at that point
2. THE restore plan SHALL identify which files come from the base archive and which come from each incremental archive in the chain
3. FOR any incremental archive set combined with the original archive and all previous incremental archive sets in the chain, THE system SHALL be able to produce the complete set of files as they were when that incremental archive set was created
4. THE system SHALL account for file deletions recorded in incremental manifests (files present in earlier archives but intentionally excluded from the restored state)
5. THE system SHALL display the total restored file count, total size, and the number of archive volumes needed for the restore operation

