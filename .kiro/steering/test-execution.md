# Test Execution Guidelines

## Running Maven Tests

This project uses Maven with jqwik property-based tests that can take significant time (100+ iterations with file I/O and SQLite). The standard `executeBash` tool will timeout on these runs, and `controlBashProcess` loses output after the process exits.

### Required Pattern: Tee to Workspace File

Always use `controlBashProcess` with `tee` to a file **inside the workspace root**, then read the file with `readFile`:

```
# Step 1: Start the test as a background process, tee output to workspace
controlBashProcess(action="start", command="mvn test -Dsurefire.useFile=false 2>&1 | tee mvn-test-results.txt")

# Step 2: Wait for the process to finish (check with listProcesses)

# Step 3: Read the output file
readFile(path="mvn-test-results.txt")

# Step 4: Clean up
deleteFile(targetFile="mvn-test-results.txt")
```

### What NOT to Do

- Do NOT use `executeBash` for `mvn test` — it will timeout on property-based tests.
- Do NOT write output to `/tmp/` — `readFile` can only access workspace files.
- Do NOT rely on `getProcessOutput` for completed processes — the buffer is unreliable after exit.
- Do NOT use `executeBash` with long timeouts hoping it will work — it won't return output reliably.

### Running a Specific Test Class

```
mvn test -Dtest=FileScanPropertyTest -Dsurefire.useFile=false 2>&1 | tee mvn-test-results.txt
```

### Waiting for Completion

After starting the background process, use `listProcesses` to check if the process status has changed from "running" to "stopped". For property-based tests in this project, expect ~3-10 seconds per test class.
