# Requirements Document: Image/Video Thumbnail Grid View (Future Feature)

## Introduction

This document captures future feature requirements for an image and video thumbnail grid view. This feature provides a visual browsing experience for media files in the managed root directory, with on-demand thumbnail generation stored in an independent datastore separate from the source files.

## Glossary

- **Thumbnail_Grid**: The main content area displaying image and video thumbnails in a responsive grid layout
- **Directory_Nav_Pane**: The left-side navigation panel showing only directories/paths from the managed root, used to select which directory's media to display
- **Thumbnail_Datastore**: An independent storage system (not the filesystem alongside source files) that persists generated thumbnail images
- **Thumbnail_Key**: The composite identifier mapping a source file to its thumbnail, consisting of file path, name, size, and checksum
- **Media_File**: Any file recognized as an image (JPEG, PNG, GIF, BMP, TIFF, WebP, etc.) or video (MP4, AVI, MOV, MKV, WebM, etc.)
- **Flattened_View**: A display mode that includes media files from all subdirectories beneath the selected directory, ignoring directory boundaries
- **Max_Flattened_Files**: A configurable limit on the number of files displayed in a flattened view before truncation
- **Bulk_Processing**: A background operation that generates thumbnails for all media files in a directory and its subdirectories without requiring the user to scroll through them

## Requirements

### Requirement 1: Grid View Layout

**User Story:** As a user, I want to browse my image and video files as a visual grid of thumbnails, so that I can quickly identify and locate media files without opening them individually.

#### Acceptance Criteria

1. THE system SHALL provide a grid view page with a directory-only navigation pane on the left and a thumbnail grid on the right
2. THE Directory_Nav_Pane SHALL display only directories and paths from the managed root directory (no individual files)
3. WHEN a directory is selected in the navigation pane, THE Thumbnail_Grid SHALL display thumbnails for all image and video files in that directory
4. THE Thumbnail_Grid SHALL arrange thumbnails in a responsive grid that adapts to the available viewport width
5. EACH thumbnail SHALL display the file name beneath the thumbnail image
6. THE system SHALL indicate video files distinctly from image files (e.g., a play icon overlay on video thumbnails)

### Requirement 2: Thumbnail Generation

**User Story:** As a user, I want thumbnails to be generated automatically when I browse a directory, so that I don't need to manually trigger thumbnail creation.

#### Acceptance Criteria

1. WHEN a directory is selected in the navigation pane, THE system SHALL process all image and video files in that directory to generate thumbnail images
2. FOR image files, THE system SHALL generate a scaled-down thumbnail preserving aspect ratio
3. FOR video files, THE system SHALL extract a representative frame (e.g., a frame from early in the video) and generate a thumbnail from it
4. THE system SHALL generate thumbnails in the background without blocking the UI, displaying a placeholder until the thumbnail is ready
5. IF a thumbnail already exists in the datastore for a file (matched by path, name, size, and checksum), THE system SHALL serve the cached thumbnail without regenerating it

### Requirement 3: Thumbnail Datastore

**User Story:** As a user, I want thumbnails stored independently from my source files, so that the managed directory remains unmodified and thumbnail data can be managed separately.

#### Acceptance Criteria

1. THE system SHALL store all generated thumbnail images in an independent datastore, NOT in the filesystem alongside the source image and video files
2. THE system SHALL NOT create any thumbnail files, metadata files, or hidden directories within the managed root directory or its subdirectories
3. EACH thumbnail in the datastore SHALL be mapped to its source file by: file path (relative to managed root), file name, file size, and file checksum (SHA-256)
4. WHEN a source file's size or checksum changes, THE system SHALL treat the existing thumbnail as stale and regenerate it on next access
5. THE system SHALL provide a mechanism to clear or rebuild the thumbnail datastore (e.g., an admin action to purge all cached thumbnails)

### Requirement 4: Thumbnail Retrieval

**User Story:** As a user, I want thumbnails to load quickly from cache when I revisit a directory, so that browsing is responsive after initial generation.

#### Acceptance Criteria

1. WHEN displaying a thumbnail, THE system SHALL first check the datastore for an existing thumbnail matching the file's path, name, size, and checksum
2. IF a matching thumbnail exists in the datastore, THE system SHALL serve it directly without accessing the source file
3. IF no matching thumbnail exists, THE system SHALL queue the file for thumbnail generation and display a placeholder until ready
4. THE system SHALL serve thumbnail images via an API endpoint that accepts the file identifier and returns the thumbnail binary data

### Requirement 5: Face Filtering

**User Story:** As a user, I want to filter the thumbnail grid by identified faces, so that I can quickly find all photos and videos containing a specific person.

#### Acceptance Criteria

1. THE Thumbnail_Grid SHALL provide a face filter control that allows the user to select one or more known faces
2. WHEN one or more faces are selected in the filter, THE Thumbnail_Grid SHALL display only media files that contain the selected face(s)
3. THE face filter SHALL use face identification data from the existing known faces system (known_faces.json)
4. WHEN a face filter is active, THE system SHALL indicate which filter is applied and provide a way to clear it
5. THE face filter SHALL work in combination with both single-directory and flattened subdirectory views

### Requirement 6: Flattened Subdirectory View

**User Story:** As a user, I want to view thumbnails from all subdirectories beneath a selected directory in a single grid, so that I can browse across nested folder structures without navigating each one individually.

#### Acceptance Criteria

1. THE system SHALL provide a toggle or option to include all subdirectories in the grid view (flattening the directory structure beneath the selected directory)
2. WHEN the flattened view is enabled, THE Thumbnail_Grid SHALL display media files from the selected directory and all of its subdirectories recursively
3. WHEN displaying files from multiple directories in flattened view, THE system SHALL indicate the subdirectory path for each thumbnail (e.g., as a subtitle or tooltip)
4. THE system SHALL provide a configurable setting for the maximum number of files allowed in the flattened view of a directory
5. WHEN the number of media files in the flattened view exceeds the configured maximum, THE system SHALL truncate the display at the maximum and show a visible "truncated" warning indicating how many additional files were excluded
6. THE default maximum for flattened view files SHALL be a reasonable limit (e.g., 500 or 1000 files)

### Requirement 7: Paginated Thumbnail Processing

**User Story:** As a user, I want thumbnail generation to only process files visible on the current page, so that browsing large directories is fast and doesn't waste resources processing off-screen files.

#### Acceptance Criteria

1. WHEN a directory is selected, THE system SHALL only generate thumbnails for files visible on the first page/viewport
2. AS the user scrolls to view additional files, THE system SHALL trigger thumbnail generation for newly visible files
3. THE system SHALL NOT pre-generate thumbnails for files that the user has not scrolled to view
4. WHEN thumbnails are generated via scrolling, THE system SHALL queue them in the background and display placeholders until ready

### Requirement 8: Bulk Background Processing

**User Story:** As a user, I want an option to force-process all thumbnails in a directory and its subdirectories in the background, so that I can pre-cache everything for fast future browsing.

#### Acceptance Criteria

1. THE system SHALL provide an action button to force processing of all media files in the selected directory and all its subdirectories as a background task
2. WHEN a bulk processing task is running, THE system SHALL display its progress (files processed, files remaining, estimated time)
3. WHILE a bulk background process is running for a directory, THE system SHALL prevent automatic (scroll-triggered) thumbnail processing from being triggered on that directory or any of its subdirectories
4. WHEN bulk processing completes, THE system SHALL re-enable automatic thumbnail processing for the affected directories
5. THE user SHALL be able to cancel a running bulk processing task
6. IF automatic thumbnail processing is suppressed due to a running bulk task, THE system SHALL still serve any thumbnails that have already been generated and cached in the datastore

### Requirement 9: Stale Thumbnail Detection and Update

**User Story:** As a user, I want the system to automatically detect when source files have changed and update their thumbnails, so that the thumbnail grid always reflects the current state of my media files.

#### Acceptance Criteria

1. THE system SHALL provide a background process that compares thumbnail key values (file path/name, size, and checksum) in the datastore against the current state of the corresponding source files
2. WHEN the background process finds a thumbnail entry whose stored size or checksum does not match the current file at that path/name, THE system SHALL queue that thumbnail for regeneration
3. THE background process SHALL operate via a queue to limit resource consumption and avoid interfering with normal browsing
4. WHEN a stale thumbnail is regenerated, THE system SHALL update the datastore entry with the new thumbnail image and the current file size and checksum
5. THE system SHALL allow the stale-detection background process to be triggered manually (e.g., an admin action) or run on a configurable schedule
6. IF a source file no longer exists at the recorded path/name, THE system SHALL remove the orphaned thumbnail entry from the datastore

### Requirement 10: Manual Thumbnail Regeneration

**User Story:** As a user, I want to manually force regeneration of thumbnails for individual files or entire directories, so that I can fix bad thumbnails or refresh them on demand without waiting for the background staleness check.

#### Acceptance Criteria

1. EACH thumbnail in the grid SHALL provide an action (e.g., a button or context menu option) to force regeneration of that file's thumbnail
2. WHEN the user triggers manual regeneration on a single file, THE system SHALL discard the existing cached thumbnail and generate a new one from the current source file
3. AFTER regeneration completes, THE system SHALL update the datastore entry with the new thumbnail image, current file size, and current checksum
4. THE system SHALL display a loading/placeholder state on the thumbnail while regeneration is in progress
5. THE Directory_Nav_Pane SHALL provide an action on each directory to force regeneration of all thumbnails for media files in that directory
6. WHEN directory-level regeneration is triggered, THE system SHALL discard and regenerate thumbnails for all media files in the selected directory (not subdirectories, unless flattened view is active)
7. WHEN directory-level regeneration is triggered with the flattened view active, THE system SHALL regenerate thumbnails for all media files in the directory and its subdirectories
8. THE directory-level regeneration SHALL run as a background task with progress indication, and the user SHALL be able to cancel it

### Requirement 11: Video Thumbnail Frame Selection

**User Story:** As a user, I want to scrub through a video file and select a specific frame to use as its thumbnail, so that I can choose the most representative or recognizable image for each video.

#### Acceptance Criteria

1. FOR video files, THE system SHALL provide a frame selector interface accessible from the thumbnail (e.g., via a context menu or click action)
2. THE frame selector SHALL allow the user to scrub through the video timeline and preview frames at different time offsets
3. WHEN the user selects a frame, THE system SHALL generate the thumbnail from that specific frame and store it in the datastore
4. THE system SHALL persist the selected time offset alongside the thumbnail entry in the datastore
5. WHEN a video thumbnail is regenerated (manually, via staleness detection, or due to a thumbnail size configuration change), THE system SHALL use the stored time offset to extract the same frame for the new thumbnail
6. IF no time offset has been selected by the user, THE system SHALL use the default frame extraction behavior (e.g., a frame from early in the video)
7. THE user SHALL be able to reset a video thumbnail to the default frame by clearing the stored time offset

### Requirement 12: Thumbnail Size Configuration and Letterboxing

**User Story:** As a user, I want to configure the thumbnail dimensions globally, so that I can control the size of thumbnails in the grid, and I want non-standard aspect ratio media to be letterboxed so all thumbnails display uniformly.

#### Acceptance Criteria

1. THE system SHALL provide a global configuration setting for thumbnail width and height in pixels
2. THE thumbnail size setting SHALL have a reasonable default (e.g., 200×150 pixels, maintaining a 4:3 aspect ratio)
3. WHEN the thumbnail size configuration is changed, THE system SHALL mark all existing thumbnails as requiring regeneration at the new size
4. WHEN generating a thumbnail for a source image or video frame that does not fit a standard 4:3 aspect ratio, THE system SHALL produce a letterboxed thumbnail that fits within the configured dimensions
5. THE letterboxed thumbnail SHALL scale the source content to fit entirely within the configured width and height (preserving aspect ratio) and fill the remaining area with a neutral background color (e.g., black or a theme-appropriate color)
6. ALL generated thumbnails SHALL have identical pixel dimensions matching the configured width and height, regardless of source aspect ratio
7. THE thumbnail size configuration SHALL be accessible from the admin settings or thumbnail grid view settings

