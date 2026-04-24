# Source Write-back

The `-apply-tags` modifier instructs MethodAtlas to insert AI-generated
`@DisplayName` and `@Tag` annotations directly into the scanned `.java` source
files, instead of writing a CSV report.

It can be combined with [API AI enrichment](api-ai.md) or with the
[Manual AI workflow](manual.md) consume phase.

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

```
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

See [CLI reference — -apply-tags](../cli-reference.md#-apply-tags)
for annotation placement guarantees and formatting details.
