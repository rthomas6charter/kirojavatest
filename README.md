# Kiro Java Test

A lightweight Java web application for browsing, analyzing, and organizing files in a managed directory. Built with minimal dependencies and no commercial product lock-in.

## Tech Stack

- **Javalin 7** — lightweight web framework on Jetty 12
- **Handlebars** (jknack) — server-side HTML templating with layout/partial support
- **Jackson** — JSON serialization for REST endpoints
- **Maven** — build and dependency management, executable fat jar via shade plugin
- **Java 17+** required

No Spring, no application server, no IDE dependencies. Build and run from the command line.

## Features

### File Browser
- Lazy-loading expandable directory tree rooted at a configurable data directory
- Columns: name, created date, modified date, size, suggested reorganization path, duplicate inspector
- Folder sizes computed recursively; empty folders shown with a disabled icon
- Sticky column headers; CSS grid layout for consistent column alignment
- Expand/collapse state persisted per user across sessions and server restarts
- Polling-based auto-refresh (5s) with scroll-position stabilization — background file changes update the tree without disrupting the current view

### Duplicate File Detection
- Two-pass analysis: groups files by size, then computes SHA-256 checksums only for size-matched candidates
- Duplicate files highlighted with a red multi-file icon and red filename
- Magnifying glass button opens an overlay panel showing all copies in the duplicate group
- Overlay shows relative paths as clickable links that navigate to the file in the tree (expanding parent folders and scrolling into view)
- Columns in the overlay show filename match indicator (same/different name) and created/modified time deltas relative to the inspected file

### Date-Based Reorganization
- Computes a `YYYY/MM/DD` target directory path for each file based on its creation date
- Files not already in their correct date path show the suggested target in a dedicated column
- Downloadable shell script to reorganize files into the date-based structure using `$DATA_DIR`-relative paths

### Script Generation
- **Remove Duplicates** — generates a bash script that keeps the first copy in each duplicate group and removes the rest
- **Reorganize Files** — generates a bash script that moves files into `YYYY/MM/DD` directories based on creation date
- Both scripts use a `$DATA_DIR` variable for portability and proper shell escaping

### Summary Dashboard
- Total file count, duplicate set count, files after deduplication, reclaimable space, files needing reorganization
- Auto-refreshes every 10 seconds
- Script download buttons centered below the stats

### Authentication
- Session-based login with configurable username/password
- HMAC-SHA256 signed remember-me cookie for persistence across server restarts
- Cookie expiry configurable (default 30 days)
- Logout clears both session and cookie

### UI
- Material Design inspired layout with Google Material Icons and Roboto font (CDN, no build step)
- Collapsible sidebar navigation (Home, About, Settings, Search)
- Earth tone color scheme (configurable via CSS)
- Top app bar with app name (configurable) and user info/sign-out
- Responsive sidebar — collapses to icon-only on mobile
- Static file cache control configurable for development

### Search (Prototype)
- Search form with server-side filtering against mock data
- Results table with status chips (active/pending/inactive)

## Configuration

All settings in `src/main/resources/application.properties`:

```properties
server.port=8080
app.name=Kiro Java Test
app.data.dir=${user.home}/kirojavatest/data
app.data.poll.seconds=5
auth.username=admin
auth.password=admin
auth.cookie.secret=change-me-in-production
auth.cookie.maxage.days=30
static.cache.enabled=false
```

System properties (`-D` flags) override file values. The `${user.home}` placeholder resolves at runtime.

## Build & Run

```bash
mvn package
java -jar target/server-app-1.0.0-SNAPSHOT.jar
```

Or use the exec plugin:

```bash
mvn compile exec:java
```

### Development with Auto-Reload

Requires [entr](https://eradman.com/entrproject/) (`brew install entr`):

```bash
chmod +x dev.sh
./dev.sh
```

This watches `src/` for changes to `.java` and `.hbs` files and restarts the app automatically.

## Project Structure

```
├── pom.xml
├── dev.sh                          # Development auto-reload script
├── src/main/java/org/example/kirojavatest/
│   ├── App.java                    # Entry point, Javalin setup, Handlebars config
│   ├── AppConfig.java              # Properties loader with placeholder resolution
│   ├── api/
│   │   └── ApiController.java      # REST endpoints (files, duplicates, summary, scripts)
│   ├── fileanalyzer/
│   │   ├── FileAnalyzer.java       # Duplicate detection, directory size calculation
│   │   ├── FileInfo.java           # File data record
│   │   ├── DirectorySummary.java   # Directory stats record
│   │   ├── DuplicateGroup.java     # Duplicate group record
│   │   └── DatePathUtil.java       # YYYY/MM/DD path computation
│   └── web/
│       ├── AuthController.java     # Login, logout, session/cookie auth
│       └── HomeController.java     # Page routes, template model builder
├── src/main/resources/
│   ├── application.properties
│   ├── public/
│   │   ├── css/style.css
│   │   └── js/
│   │       ├── app.js              # Sidebar toggle
│   │       ├── file-tree.js        # Tree component, polling, duplicate overlay
│   │       └── summary.js          # Summary panel auto-refresh
│   └── templates/
│       ├── layouts/main.hbs        # Base layout with app bar, sidebar, content area
│       ├── home.hbs                # File browser page
│       ├── about.hbs
│       ├── settings.hbs
│       ├── search.hbs
│       └── login.hbs
```
