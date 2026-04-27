#!/usr/bin/env bash
#
# dev.sh — Watch source files and auto-rebuild/restart the application during development.
#
# Prerequisites:
#   - Java 17+
#   - Maven (mvn)
#   - entr (install via: brew install entr)
#

set -euo pipefail

# Check that entr is installed. entr is a file-watching utility that re-runs
# a command whenever the watched files change.
if ! command -v entr &> /dev/null; then
    echo "Error: 'entr' is not installed. Install it with: brew install entr"
    exit 1
fi

# find src -type f
#   Recursively lists ALL files under src/ — Java sources, Handlebars templates,
#   CSS, JS, properties, and any other resources. This ensures changes to any
#   file trigger a rebuild and restart.
#
# | entr -r mvn compile exec:java
#   Pipes that file list into entr, which watches them for modifications.
#
#   -r  Tells entr to terminate the running process (the server) and restart
#       the entire command when a file changes. Without -r, entr would wait
#       for the previous run to finish before restarting.
#
#   -d  Tells entr to exit when a new file is added to a watched directory,
#       so the outer while loop can re-scan and pick up the new file.
#
#   mvn compile exec:java
#       compile    — Incrementally compiles changed source files into target/
#                    and copies resources to target/classes/.
#       exec:java  — Runs the application's main class (configured in pom.xml)
#                    in the same JVM process as Maven, so no separate jar step
#                    is needed during development.
#
# The while loop restarts entr whenever it exits due to -d (new file detected),
# ensuring newly created files are also watched.

echo "Watching src/ for changes... (press q to quit)"
while true; do
    find src -type f | entr -r -d mvn compile exec:java || true
done
