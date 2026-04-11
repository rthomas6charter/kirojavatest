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

# find src -name '*.java' -o -name '*.hbs'
#   Recursively lists all Java source files and Handlebars templates under src/.
#   These are the files we want to monitor for changes.
#
# | entr -r mvn compile exec:java
#   Pipes that file list into entr, which watches them for modifications.
#
#   -r  Tells entr to terminate the running process (the server) and restart
#       the entire command when a file changes. Without -r, entr would wait
#       for the previous run to finish before restarting.
#
#   mvn compile exec:java
#       compile    — Incrementally compiles changed source files into target/.
#       exec:java  — Runs the application's main class (configured in pom.xml)
#                    in the same JVM process as Maven, so no separate jar step
#                    is needed during development.

echo "Watching src/ for changes... (press q to quit)"
find src -name '*.java' -o -name '*.hbs' | entr -r mvn compile exec:java
