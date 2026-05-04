# Requirements Document

## Introduction

This document captures the requirements for the File Management Application ("Kiro Java Test"), a Java web application that provides a browser-based interface for managing, analyzing, and reorganizing files within a configured data directory. The application supports duplicate detection, date-based file reorganization, connection management for local and remote file sources, job scheduling, archive template management, and known face metadata editing. It is built with Javalin, Handlebars templating, SQLite, and vanilla JavaScript.

## Glossary

- **File_Browser**: The expandable tree component that displays files and directories from the configured data directory
- **Data_Directory**: The root filesystem path configured via `app.data.dir` from which all file operations originate
- **Structure_Preview**: The virtual directory tree showing files reorganized into the configured target path structure
- **Summary_Panel**: The statistics display showing file counts, duplicate information, and reclaimable space
- **Connection**: A configured file source (File, SMB, or SFTP type) that can serve as an alternative root for browsing
- **Job_Template**: A reusable configuration defining source, options, and target for file management operations
- **Job**: An instance created from a Job_Template that tracks execution status and results
- **Archive_Template**: A configuration defining media capacity and output format for archival operations
- **Auth_System**: The authentication subsystem handling login, session management, and persistent cookies
- **Task_Queue**: The background task management system that tracks active and completed asynchronous operations
- **DatePathUtil**: The utility that computes YYYY/MM/DD target paths for files based on their creation date
- **FileAnalyzer**: The utility that detects duplicate files via SHA-256 checksums and computes directory statistics
- **MagicNumberUtil**: The utility that detects mismatches between a file's magic number signature and its extension
- **Settings_Manager**: The component that persists and retrieves application-wide configuration settings

## Requirements

### Requirement 1: File Browser

**User Story:** As a user, I want to browse files and directories in a tree view, so that I can see the contents of my data directory and understand its structure.

#### Acceptance Criteria

1. WHEN the File_Browser loads, THE File_Browser SHALL display the immediate children of the Data_Directory as a single-level list sorted with directories first then files alphabetically
2. WHEN a user expands a directory node, THE File_Browser SHALL lazy-load and display the immediate children of that directory
3. WHEN a user expands or collapses a directory node, THE File_Browser SHALL persist the expand/collapse state for that user across sessions
4. THE File_Browser SHALL display columns for Name, Created date, Modified date, Size, Suggested Reorg Path, Alerts, and Actions for each file entry
5. WHEN a file is a member of a duplicate group, THE File_Browser SHALL highlight that row in red and display a multi-file icon
6. WHEN a user clicks the duplicate indicator on a file, THE File_Browser SHALL display a duplicate inspector overlay showing all identical files with date deltas and filename comparison
7. WHEN the tree updates via polling, THE File_Browser SHALL preserve the current scroll position
8. WHEN a user clicks the Collapse All button, THE File_Browser SHALL collapse all expanded directory nodes
9. WHEN a connection is selected from the connection source dropdown, THE File_Browser SHALL display files from that connection's root instead of the default Data_Directory
10. WHEN a file's magic number does not match its file extension, THE File_Browser SHALL display an "fn" alert badge on that file's row
11. WHEN a file is not in its correct target path per the configured structure type, THE File_Browser SHALL display the suggested reorganization path for that file
12. IF the configured Data_Directory does not exist, THEN THE File_Browser SHALL create it and display an empty listing

### Requirement 2: Structure Preview

**User Story:** As a user, I want to see a preview of how my files would be reorganized into the configured target path structure, so that I can understand the proposed changes before executing them.

#### Acceptance Criteria

1. WHEN the Structure_Preview loads, THE Structure_Preview SHALL display a virtual directory tree with all files placed into their target paths as determined by the configured target structure type
2. WHEN a file would be moved from its current location, THE Structure_Preview SHALL visually highlight that file as moved
3. WHEN multiple files resolve to the same virtual path, THE Structure_Preview SHALL display a duplicate count badge with a tooltip showing the actual source paths
4. WHEN a user expands or collapses nodes in the Structure_Preview, THE Structure_Preview SHALL persist the state via localStorage
5. WHEN the Structure_Preview updates, THE Structure_Preview SHALL preserve the current scroll position

### Requirement 3: Summary Panel

**User Story:** As a user, I want to see summary statistics about my file collection, so that I can quickly understand the state of my data directory.

#### Acceptance Criteria

1. THE Summary_Panel SHALL display the total file count, files needing reorganization count, duplicate set count, files-after-dedup count, and reclaimable space in bytes
2. WHEN a user clicks the Remove Duplicates download button, THE Summary_Panel SHALL generate and download a shell script that removes duplicate files keeping the first copy in each group
3. WHEN a user clicks the Reorganize Files download button, THE Summary_Panel SHALL generate and download a shell script that moves files into the configured target path structure

### Requirement 4: Connections Management

**User Story:** As an administrator, I want to manage file source connections, so that I can browse files from local subdirectories, SMB shares, or SFTP servers.

#### Acceptance Criteria

1. THE Connection management interface SHALL support creating, reading, updating, and deleting connections of types File, SMB, and SFTP
2. WHEN a File type connection is created, THE system SHALL store a subdirectory path relative to the Data_Directory
3. WHEN an SMB type connection is created, THE system SHALL store host, port, username, and password (base64 encoded)
4. WHEN an SFTP type connection is created, THE system SHALL store host, port, username, and password (base64 encoded)
5. WHEN a user activates a connection, THE system SHALL validate it before allowing activation
6. WHEN validating a File connection, THE system SHALL verify the subdirectory exists within the Data_Directory
7. WHEN validating an SMB connection, THE system SHALL verify TCP connectivity to the host on port 445 within 5 seconds
8. WHEN validating an SFTP connection, THE system SHALL verify TCP connectivity to the host on the configured port within 5 seconds
9. WHEN a periodic health check runs, THE system SHALL mark unreachable active connections as offline
10. WHEN a previously offline connection becomes reachable, THE system SHALL restore it to online status
11. WHEN the currently selected connection source is offline, THE File_Browser SHALL display an offline overlay with a "Try Reconnect Now" option
12. THE system SHALL run connection health checks on a configurable interval defaulting to one hour

### Requirement 5: Job Templates

**User Story:** As a user, I want to create job templates, so that I can define file management operations to run in batch mode, schedule them, resume stopped jobs, and capture job output for later review.

#### Acceptance Criteria

1. THE system SHALL support creating, reading, updating, and deleting Job_Templates
2. WHEN a Job_Template is created, THE system SHALL store name, source type (Default Data Dir or From Connection), options (Reorganize, Remove Duplicates, Send Notification with email), and target (In Place or To Connection)
3. WHEN no connections exist, THE Job_Template form SHALL disable connection dropdowns and display a tooltip explaining why

### Requirement 6: Jobs

**User Story:** As a user, I want to create and track jobs from templates, so that I can execute file management operations and monitor their progress.

#### Acceptance Criteria

1. WHEN a job is created from a template, THE system SHALL snapshot the template settings at creation time into the job record
2. WHEN a job is created, THE system SHALL allow overriding the source and/or target from the template defaults
3. THE system SHALL track job status as one of: created, running, completed, or error
4. WHEN a job encounters an error, THE system SHALL store error details accessible via a popup dialog
5. THE system SHALL display jobs in a list ordered newest first, showing template name, source, options, target, status, and timestamps
6. WHEN a job is created, THE system SHALL assign it a unique UUID identifier

### Requirement 7: Archive Templates

**User Story:** As a user, I want to define archive templates, so that I can configure how files are split across physical media for archival purposes.

#### Acceptance Criteria

1. THE system SHALL support creating, reading, updating, and deleting Archive_Templates
2. WHEN an Archive_Template is created, THE system SHALL store name, media capacity in MB, and output format
3. THE system SHALL support output formats: tar File Lists, ImgBurn Project Files, and ISO Image Generation Script
4. THE system SHALL provide preset capacity buttons for CD-R (700 MB), DVD-R (4700 MB), DVD-R DL (8500 MB), BD-R (25000 MB), BD-R DL (50000 MB), BDXL 100 (100000 MB), and BDXL 128 (128000 MB)

### Requirement 8: Known Faces

**User Story:** As a user, I want to view and edit known face metadata, so that I can manage identity labels associated with face embeddings.

#### Acceptance Criteria

1. THE system SHALL display known faces from a JSON file located at `{Data_Directory}/known_faces.json`
2. WHEN a user edits a face entry, THE system SHALL update the name/value fields while preserving the embedding data unchanged
3. IF the known faces file does not exist, THEN THE system SHALL return an empty list

### Requirement 9: Authentication

**User Story:** As a user, I want to log in with credentials, so that my session is secure and persists across browser restarts.

#### Acceptance Criteria

1. WHEN a user submits valid credentials, THE Auth_System SHALL create a session and set a signed remember cookie
2. WHEN a user submits invalid credentials, THE Auth_System SHALL display an error message and retain the entered username
3. WHEN an unauthenticated user accesses a protected page, THE Auth_System SHALL redirect to the login page
4. WHEN a user has a valid remember cookie, THE Auth_System SHALL restore the session without requiring re-login
5. WHEN a remember cookie signature is invalid or expired, THE Auth_System SHALL clear the cookie and redirect to login
6. WHEN a user logs out, THE Auth_System SHALL invalidate the session, remove the cookie, and redirect to the login page
7. THE Auth_System SHALL sign cookies using HMAC-SHA256 with a configurable secret
8. THE Auth_System SHALL use constant-time comparison for cookie signature validation

### Requirement 10: Admin Panel

**User Story:** As an administrator, I want to configure application settings, so that I can customize behavior and monitor system health.

#### Acceptance Criteria

1. THE Admin Panel SHALL provide tabs for UI Options, Database Management, Directory Structure, Connections, and Optimizations
2. WHEN a user changes the theme setting, THE system SHALL switch between light and dark themes
3. THE Database Management tab SHALL display SQLite database statistics
4. THE Directory Structure tab SHALL allow selection of the target structure type for reorganization
5. THE Optimizations tab SHALL allow configuration of background task timeout, queue threshold, and connection check interval
6. WHEN settings are updated via the Admin Panel, THE Settings_Manager SHALL persist them immediately

### Requirement 11: Search

**User Story:** As a user, I want to search across application data, so that I can quickly find relevant items.

#### Acceptance Criteria

1. WHEN a user enters a search query, THE system SHALL filter results by matching the query against all field values (case-insensitive)
2. WHEN search results are displayed, THE system SHALL show them on a dedicated search results page with a clear button
3. WHEN a search is active, THE navigation SHALL display a dynamic "Search Results" item

### Requirement 12: Background Tasks

**User Story:** As a user, I want background tasks to be tracked, so that I can monitor long-running operations.

#### Acceptance Criteria

1. THE Task_Queue SHALL track tasks with unique IDs, status, creation time, and completion time
2. WHEN a task is active, THE Task_Queue SHALL include it in the active tasks list
3. WHEN a task completes, THE Task_Queue SHALL record the completion timestamp
4. WHEN a user requests completed tasks since a given timestamp, THE Task_Queue SHALL return only tasks completed after that time
5. WHEN a user cancels a task, THE Task_Queue SHALL mark it as cancelled

### Requirement 13: Infrastructure and UI

**User Story:** As a user, I want a responsive, themed web interface with persistent navigation, so that I can efficiently use the application.

#### Acceptance Criteria

1. THE system SHALL serve the application via a configurable port defaulting to 8080
2. THE system SHALL provide a collapsible sidebar navigation with icon-only mode
3. THE system SHALL support light and dark themes using CSS custom properties with flash prevention on page load
4. THE system SHALL use Material Icons throughout the interface
5. THE system SHALL use an earth-tone color scheme
6. THE system SHALL log all HTTP requests with method, path, status, duration, and client IP
7. WHEN a request targets a polling endpoint, THE system SHALL log it to a separate polling log
8. THE system SHALL provide a Dockerfile and docker-compose.yaml for containerized deployment
9. THE system SHALL scan the Data_Directory on startup and populate the database with file metadata

### Requirement 14: Duplicate Detection

**User Story:** As a user, I want the system to detect duplicate files, so that I can identify and reclaim wasted storage space.

#### Acceptance Criteria

1. THE FileAnalyzer SHALL identify duplicate files by computing SHA-256 checksums of file contents
2. WHEN duplicates are found, THE FileAnalyzer SHALL group them and report the count and wasted bytes per group
3. THE generated remove-duplicates script SHALL keep the first file in each duplicate group and remove the rest
4. FOR ALL duplicate groups, THE FileAnalyzer SHALL provide the relative paths of all files in the group

### Requirement 15: File Reorganization

**User Story:** As a user, I want files to be reorganized into a configured target path structure, so that my files are consistently organized.

#### Acceptance Criteria

1. THE DatePathUtil SHALL compute a target path for each file based on the configured target structure type (e.g., YYYY/MM/DD/filename is one such structure)
2. WHEN a file is already in the correct target path, THE DatePathUtil SHALL report it as not needing reorganization
3. THE generated reorganize script SHALL create target directories and move files that are not in their correct target path
4. FOR ALL files processed by DatePathUtil, computing the target path and checking if the file is in the correct path SHALL be consistent (a file at its target path reports as correctly placed)
