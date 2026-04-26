# Troubleshooting

This page lists the most common problems encountered when running MethodAtlas
and explains how to diagnose and resolve each one.

## Test methods not discovered

### No output rows produced

**Symptom:** MethodAtlas runs without errors but the CSV contains only the
header row.

**Causes and remedies:**

| Cause | Remedy |
|---|---|
| The scan root path does not contain files whose names end with `Test.java` | Verify the path with `find <root> -name "*Test.java" \| head` |
| Tests use a different naming convention (`IT.java`, `Tests.java`, `Spec.java`) | Add `-file-suffix IT.java` (or the appropriate suffix); the first occurrence replaces the default |
| The test source directory is not directly under the path supplied | Supply the exact parent of the package root, e.g. `src/test/java` rather than `src` |

### Tests found but none classified as security-relevant

**Symptom:** The CSV contains rows but `ai_security_relevant` is empty or
all rows have `ai_security_relevant=false`.

**Causes and remedies:**

| Cause | Remedy |
|---|---|
| `-ai` flag not supplied | Add `-ai -ai-provider <provider>` to the command |
| `auto` provider cannot find a running Ollama instance and no API key is set | Either start Ollama (`ollama serve`) or supply an API key with `-ai-api-key-env` |
| The test suite genuinely has no security-relevant methods | This is a valid outcome; review with the AI rationale column (`ai_reason`) to confirm |
| Custom annotation set via `-test-annotation` does not match the annotations in source | Verify that the annotation names supplied match those used in the test files |

### JUnit 4 or TestNG tests not discovered

**Symptom:** Tests annotated with `@Theory` or TestNG's `@Test` are absent
from the output.

**Cause:** Auto-detection derives the annotation set from import declarations
in each source file. If the file has no `import org.junit.*` or
`import org.testng.*` statement, auto-detection falls back to the JUnit 5
default set.

**Remedy:** Verify that the test file contains a recognisable import. If the
framework is imported via a wildcard at a different package prefix, override
detection explicitly:

```bash
./methodatlas -test-annotation Test -test-annotation Theory src/test/java
```

## AI enrichment issues

### AI provider returns no results

**Symptom:** All rows have empty `ai_*` columns despite `-ai` being set.

**Diagnose:** Run with a single test file and add `-ai-provider ollama` (or
the specific provider) to eliminate `auto` mode ambiguity. Observe stderr for
connection errors or authentication failures.

**Common causes:**

| Symptom in stderr | Cause | Remedy |
|---|---|---|
| `Connection refused` | Ollama not running | Run `ollama serve` or `docker start ollama` |
| `401 Unauthorized` | Wrong or missing API key | Verify the environment variable name matches `-ai-api-key-env` |
| `404 Not Found` | Wrong model name for provider | Verify the model identifier with the provider's model list |
| `Request timeout` | AI provider too slow for the configured timeout | Increase with `-ai-timeout-sec 180` |
| `Rate limit exceeded` | Too many concurrent requests or API quota exhausted | Add `-ai-max-retries 3`; consider scanning fewer files per run |

### AI classification quality is poor

**Symptom:** Known security tests are classified as not security-relevant, or
non-security tests receive security tags.

**Remedies in order of effort:**

1. Switch to a larger or more capable model for the provider in use.
2. Add a `@DisplayName("SECURITY: auth — …")` annotation to the affected
   methods; explicit intent descriptions reliably improve classification.
3. If a domain-specific taxonomy would produce more accurate tags, supply one
   with `-ai-taxonomy`; see [Custom Taxonomy](ai/custom-taxonomy.md).
4. Record corrections in an [override file](ai/overrides.md) so that correct
   classifications persist across future runs regardless of model output.

### `ai_max_class_chars` limit exceeded silently

**Symptom:** A large test class receives no AI enrichment with no error message.

**Cause:** The class source exceeds the `-ai-max-class-chars` limit (default:
40 000 characters). Classes over this limit are skipped to avoid exceeding
provider context windows.

**Remedy:** Either split the test class into smaller classes, or raise the
limit if the provider's context window supports it:

```bash
./methodatlas -ai -ai-max-class-chars 80000 src/test/java
```

## Cache behaviour

### Cache appears to have no effect (all classes re-classified on every run)

**Cause:** The cache requires both `-content-hash` and `-ai` on the run that
produced the cache file. If either flag was absent, the cache file contains
no usable entries and MethodAtlas silently falls back to full re-classification.

**Verify:** Inspect the cache CSV. It must contain a `content_hash` column and
an `ai_security_relevant` column. If either is absent, re-run the producing
scan with both flags and regenerate the cache.

**Correct usage:**

```bash
# Producing scan (both flags required)
./methodatlas -ai -content-hash src/test/java > scan.csv

# Subsequent scan
./methodatlas -ai -content-hash -ai-cache scan.csv src/test/java
```

### Cache causes stale results after a class rename

**Cause:** The cache is keyed by `content_hash`. Renaming a class without
changing its body produces a new FQCN with the same hash. The old cache entry
is never matched (different FQCN), so the renamed class is re-classified as a
cache miss. This is correct behaviour — no stale data is returned.

**Result:** One additional AI call for the renamed class on the first run
after the rename. Subsequent runs use the new cache entry normally.

## Output format issues

### SARIF file is empty or contains no results

**Symptom:** `methodatlas.sarif` is produced but contains no `results` array
entries.

**Cause:** `-security-only` is set (or implicitly active) but the scan found
no security-relevant methods. This is a valid output; SARIF with an empty
results array is well-formed.

**Verify:** Re-run without `-security-only` to see whether non-security
methods are present. If the full output is also empty, consult the "Tests not
discovered" section above.

### GitHub Actions annotations do not appear on the PR diff

**Symptom:** The workflow runs without errors but no inline annotations appear
on the pull request.

**Cause:** The `::notice` and `::warning` workflow commands are only
interpreted by GitHub Actions when the step runs inside a GitHub Actions
environment. Running the command locally produces plain output; annotations
only render in the GitHub UI.

**Verify:** Confirm the step is running in the correct job (not in a
`container:` block that suppresses workflow command parsing), and that the
output is written to stdout rather than a file.

### Combined `-github-annotations` and `-sarif` output is malformed

**Cause:** These are two distinct output modes that emit to stdout. Running
them together in a single invocation mixes annotation command lines
(`::notice file=…`) into the SARIF JSON stream, producing an unparseable
file.

**Remedy:** Run them as separate steps:

```bash
# Step 1: annotations
./methodatlas -ai -github-annotations -ai-cache scan.csv src/test/java

# Step 2: SARIF
./methodatlas -ai -sarif -ai-cache scan.csv src/test/java > scan.sarif
```

## Java parsing issues

### Parse warnings for newer Java syntax

**Symptom:** MethodAtlas emits warnings such as `[WARNING] Could not parse
<file>` for files using Java 21+ preview features or experimental syntax.

**Cause:** MethodAtlas uses JavaParser configured to Java 21 language level.
Files using syntax introduced after Java 21, or non-standard compiler
extensions, may produce parse failures.

**Effect:** The affected file is skipped; all other files in the scan are
processed normally. The scan does not abort.

**Remedy:** The file can be excluded by renaming it to a suffix not matched
by `-file-suffix`, or by reporting the specific syntax construct as an issue
so that the language level configuration can be reviewed.

### `@Nested` test classes

**Symptom:** Methods inside `@Nested` inner classes are not appearing in the
output.

**Clarification:** MethodAtlas discovers methods in all classes within each
parsed source file, including inner and nested classes. If nested methods are
absent, verify that the outer file name ends with the configured suffix and
that the methods carry a recognised test annotation.

## Getting further help

If the above steps do not resolve the issue, collect the following information
before reporting a bug:

1. The exact command line used (redact API keys).
2. The MethodAtlas version (from the distribution archive filename, e.g. `methodatlas-1.3.0.zip`).
3. The Java runtime version (`java -version`).
4. A representative sample of the test file that is not being processed
   correctly (stripped of any proprietary logic).

Report issues at [github.com/Accenture/MethodAtlas/issues](https://github.com/Accenture/MethodAtlas/issues).
