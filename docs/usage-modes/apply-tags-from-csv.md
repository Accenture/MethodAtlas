# Apply Tags from CSV

The `-apply-tags-from-csv <file>` mode applies reviewed annotation decisions back to source files (Java and C#). It reads a MethodAtlas CSV that a human has already reviewed and edited, then writes the tag and display-name annotations recorded in that CSV.

## When to use this mode

- Your organisation requires human sign-off before any source annotation is written — the CSV is the review artefact.
- You want a permanent, version-controlled record of every annotation decision (the committed CSV serves this purpose).
- You are applying bulk annotation decisions across a large test suite and want a single review step before any file is touched.
- You want to integrate with a spreadsheet-based review workflow: export the CSV, distribute it for review, collect the approved version, apply it.

If you want AI annotations applied immediately without a separate review step, use [`-apply-tags`](apply-tags.md) instead.

This mode is the recommended approach for teams that want human oversight before touching source code.

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

| CSV column | Value | Effect on source |
|---|---|---|
| `tags` | semicolon-separated list | Removes all existing `@Tag` and `@Tags` annotations; adds one `@Tag("…")` per entry |
| `display_name` | non-empty text | Replaces any existing `@DisplayName` with the given text |
| `display_name` | empty string | Removes `@DisplayName` if present |
| `display_name` | column absent from CSV | Leaves `@DisplayName` unchanged (backward compatibility with old CSV files) |

Required imports (`org.junit.jupiter.api.Tag`, `org.junit.jupiter.api.DisplayName`)
are added only to files where they become necessary.

All other formatting — whitespace, comments, blank lines, import order — is
preserved by JavaParser's lexical-preserving printer.

### The `display_name` three-way contract

The three behaviours of the `display_name` column are illustrated by this example CSV:

```csv
fqcn,method,loc,tags,display_name
com.example.AuthTest,loginWithExpiredToken,8,security;auth,SECURITY: auth — login is rejected when the token has expired
com.example.AuthTest,loginWithValidCredentials,12,,"" 
com.example.PaymentTest,chargeCard,15,payment,
```

| Row | `display_name` value | What happens in source |
|-----|----------------------|------------------------|
| `loginWithExpiredToken` | `SECURITY: auth — login is rejected…` (non-empty) | `@DisplayName` is added with this text, or existing value is replaced |
| `loginWithValidCredentials` | `""` (empty string — note the quoted empty field) | Any existing `@DisplayName` is removed from the source |
| `chargeCard` | *(column present but cell is empty — no quotes)* | `@DisplayName` in source is left exactly as it is |

The distinction between an empty string (`""`) and an absent value (empty cell without quotes) is significant: the former is an explicit instruction to remove the annotation; the latter means "do not touch it". This allows you to produce a CSV that only specifies tags for some methods while leaving display names alone.

For flags and full format documentation, see [CLI reference — `-apply-tags-from-csv`](../cli-reference.md#-apply-tags-from-csv).

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

## End-to-end scenario: human-reviewed annotation campaign

A security team wants to annotate 40 test methods across a legacy codebase. They require sign-off on every annotation before any source file is touched.

```bash
# Step 1: produce AI suggestions as a CSV
./methodatlas \
  -ai \
  -ai-provider openai \
  -ai-api-key-env OPENAI_API_KEY \
  src/test/java > review.csv
```

The `review.csv` file now contains AI-suggested `display_name` and `ai_tags` values for every test method. The security team opens it in a spreadsheet application and:

- Copies `ai_display_name` values they agree with into the `display_name` column.
- Copies `ai_tags` values they agree with into the `tags` column (replacing `security;auth` etc. as appropriate).
- Leaves `display_name` blank (not `""`) for methods where they do not want to set a display name.
- Removes rows for methods they do not want to annotate.

After review, the security team saves the file as `approved.csv` and sends it back to the engineering team.

```bash
# Step 2: apply approved decisions — dry run with permissive mismatch limit
./methodatlas \
  -apply-tags-from-csv approved.csv \
  -mismatch-limit -1 \
  src/test/java

# Step 3: review the diff
git diff src/test/java

# Step 4: commit
git add src/test/java approved.csv
git commit -m "chore: apply security team approved annotations"
```

Committing `approved.csv` alongside the source changes preserves the full record of what was approved, by whom, and when (via git log).

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

### `-mismatch-limit` examples

The following examples illustrate the three meaningful settings:

| Command | Behaviour |
|---------|-----------|
| `./methodatlas -apply-tags-from-csv r.csv -mismatch-limit -1 src/test/java` | Apply all matched rows; log mismatches as warnings and continue. Use during initial adoption or exploratory runs. |
| `./methodatlas -apply-tags-from-csv r.csv -mismatch-limit 1 src/test/java` | Abort immediately on the first mismatch, before modifying any file. Use in CI to enforce that the CSV is always current. |
| `./methodatlas -apply-tags-from-csv r.csv -mismatch-limit 5 src/test/java` | Allow up to 4 mismatches; abort if 5 or more are detected. Use when a small number of pending method additions is acceptable during a transition period. |

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
