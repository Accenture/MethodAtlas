# Source Write-back

The `-apply-tags` modifier instructs MethodAtlas to insert AI-generated display names and tags directly into the scanned Java (`.java`) and C# (`.cs`) source files, instead of writing a CSV report.

## When to use this mode

- You want to apply AI-suggested annotations to test source files automatically, without manually editing each file.
- You have completed an AI enrichment run (via [API AI enrichment](api-ai.md) or the [Manual AI workflow](manual.md)) and want to persist the suggested labels as source annotations.
- You want to annotate tests so that tag-based dashboards and CI gates reflect the AI's security classification immediately, without waiting for a separate annotation step.
- You have an existing [override file](../ai/overrides.md) with reviewed classifications and want to replay those decisions as source annotations without re-running AI.

If you want human review of each annotation decision before any source file is touched, use [Apply Tags from CSV](apply-tags-from-csv.md) instead — it separates the review step from the write-back.

It can be combined with [API AI enrichment](api-ai.md) or with the [Manual AI workflow](manual.md) consume phase.

## With API AI enrichment

```bash
./methodatlas -ai -apply-tags \
  -ai-provider ollama \
  -ai-model qwen2.5-coder:7b \
  src/test/java
```

## With Manual AI workflow

```bash
./methodatlas -manual-consume ./work ./responses \
  -apply-tags src/test/java
```

## With an override file

When the codebase has been classified in a previous session and results are
captured in an override file, apply-tags can use those results directly
without re-running AI:

```bash
./methodatlas -override-file .methodatlas-overrides.yaml \
  -apply-tags src/test/java
```

This is the most efficient workflow after an initial classification: run AI
once, review and commit the override file, then use it for subsequent
annotation write-backs without additional AI calls.

## What gets written

Only test methods classified as security-relevant receive new annotations.
For each such method MethodAtlas inserts:

- `@DisplayName("...")` with the AI-suggested display name as the value.
- One or more `@Tag("...")` annotations for each taxonomy tag returned by
  the AI.

Annotations are inserted using JavaParser's lexical-preserving printer, which
preserves the original formatting of all surrounding code, including whitespace,
comments, and import ordering. Existing annotations on the same method are not
modified or removed.

## Summary output

After the run, a summary line is printed to standard output:

```text
Apply-tags complete: 12 annotation(s) added to 3 file(s)
```

No CSV is produced.

!!! warning "Modifies source files in place"
    `-apply-tags` edits `.java` files directly. There is no dry-run mode.
    Commit or back up your work before running.

## Reviewing changes

After write-back, inspect the diff before committing:

```bash
git diff src/test/java
```

AI-generated annotations are a starting point. Review the suggested `@DisplayName`
values and `@Tag` assignments for accuracy before merging them into the main
branch.

## End-to-end scenario: annotating a legacy test suite

A team has a large legacy test suite with no `@Tag` or `@DisplayName` annotations. They want to quickly annotate all security-relevant tests so that their CI security gate can filter on `@Tag("security")`.

```bash
# Step 1: run AI enrichment with write-back enabled
./methodatlas \
  -ai \
  -ai-provider ollama \
  -ai-model qwen2.5-coder:7b \
  -apply-tags \
  src/test/java
```

Output:

```text
Apply-tags complete: 23 annotation(s) added to 7 file(s)
```

```bash
# Step 2: review the changes
git diff src/test/java
```

A typical diff looks like:

```diff
+import org.junit.jupiter.api.DisplayName;
+import org.junit.jupiter.api.Tag;
+
 @Test
+@Tag("security")
+@Tag("auth")
+@DisplayName("SECURITY: auth — login is rejected when the token has expired")
 void loginWithExpiredToken() {
```

```bash
# Step 3: commit reviewed annotations
git add src/test/java
git commit -m "chore: apply MethodAtlas security annotations to legacy tests"
```

After this commit, any CI pipeline that filters on `@Tag("security")` or uses MethodAtlas with `-security-only` will include these tests in its security inventory.

See [CLI reference — `-apply-tags`](../cli-reference.md#-apply-tags)
for annotation placement guarantees and formatting details.
