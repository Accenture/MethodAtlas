# Apply Tags from CSV

The `-apply-tags-from-csv <file>` mode applies reviewed annotation decisions
back to Java source files. Instead of adding AI suggestions automatically, it
reads a MethodAtlas CSV that a human has already reviewed and edited — then
writes exactly the `@Tag` and `@DisplayName` annotations recorded in that CSV.

This mode is the recommended approach for teams that want human oversight before
touching source code.

## Typical workflow

```bash
# 1. Produce a CSV (optionally with AI suggestions)
./methodatlas -ai src/test/java > review.csv

# 2. Open review.csv in a spreadsheet or text editor.
#    Adjust the tags and display_name columns to reflect the desired state.
#    Save the file.

# 3. Apply the decisions back to source files
./methodatlas -apply-tags-from-csv review.csv src/test/java
```

After step 3, re-running MethodAtlas on the same source tree would produce a CSV
that matches the `tags` and `display_name` columns of `review.csv`.

## What the engine does

For each test method found in the source tree that has a matching row in the CSV:

| CSV column | Effect on source |
|---|---|
| `tags` (semicolon-separated list) | Removes all existing `@Tag` and `@Tags` annotations; adds one `@Tag("…")` per entry |
| `display_name` (text or empty) | Non-empty: replaces any existing `@DisplayName`; Empty: removes `@DisplayName` if present |

Required imports (`org.junit.jupiter.api.Tag`, `org.junit.jupiter.api.DisplayName`)
are added only to files where they become necessary.

All other formatting — whitespace, comments, blank lines, import order — is
preserved by JavaParser's lexical-preserving printer.

## The CSV as desired state

The CSV is a **complete desired-state specification**, not an incremental patch.
Every test method currently present in the source tree must have a corresponding
row in the CSV, and every row in the CSV must correspond to a method in the
source tree. Deviations in either direction are counted as **mismatches**.

This invariant is intentional: it prevents silent drift between the reviewed
CSV and the codebase. If a method was added to or deleted from the source tree
after the CSV was produced, the mismatch is surfaced before any source file is
touched.

## Mismatch handling

A mismatch occurs when:

- A row in the CSV has no matching method in the current source tree (method was
  deleted or renamed).
- A test method in the source tree has no matching row in the CSV (method was
  added after the CSV was produced).

The `-mismatch-limit <n>` flag controls the response:

| Setting | Behaviour |
|---|---|
| `-1` (default) | Log each mismatch as a warning; proceed with all matched methods |
| `1` | Abort without making any changes as soon as one mismatch is detected — recommended for CI |
| `n` | Abort when the mismatch count reaches or exceeds `n` |

When the limit is reached, MethodAtlas prints each mismatch and exits with code `1`:

```text
MISMATCH (in CSV, not in source): com.example.LoginTest::removedMethod
Apply-tags-from-csv aborted: 1 mismatch(es) >= limit 1. No source files were modified.
```

The mismatch count is computed before any file is written. Either all source
files are modified or none are.

## CI integration

Use a strict mismatch limit in automated pipelines to guard against a stale CSV:

```bash
./methodatlas \
  -apply-tags-from-csv reviewed.csv \
  -mismatch-limit 1 \
  src/test/java
```

A non-zero exit code fails the pipeline if the codebase has diverged from the
reviewed CSV, requiring the team to re-run the review cycle before re-applying.

## Summary output

After a run, MethodAtlas prints a summary to standard output:

```text
Apply-tags-from-csv complete: 7 change(s) in 2 file(s); 0 mismatch(es) skipped.
```

Individual modified files are listed beforehand:

```text
Modified: src/test/java/com/example/LoginTest.java (+3 change(s))
Modified: src/test/java/com/example/TokenTest.java (+4 change(s))
Apply-tags-from-csv complete: 7 change(s) in 2 file(s); 0 mismatch(es) skipped.
```

No CSV is produced. Standard output is consumed entirely by the summary.

!!! warning "Modifies source files in place"
    `-apply-tags-from-csv` edits `.java` files directly. There is no dry-run
    mode. Commit or back up your work before running.

## Reviewing changes

After write-back, inspect the diff before committing:

```bash
git diff src/test/java
```

See [CLI reference](../cli-reference.md#-apply-tags-from-csv) for the full flag
reference.
