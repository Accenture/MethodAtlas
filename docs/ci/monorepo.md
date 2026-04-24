# Multi-Module and Monorepo Projects

This page describes how to integrate MethodAtlas into projects built as
multiple Maven or Gradle submodules within a single repository. It covers
per-module scanning, cache management, CSV aggregation, and cross-module
coverage analysis.

## How MethodAtlas handles multi-module projects

MethodAtlas scans one or more directory paths in a single invocation. It
does not read Maven `pom.xml` or Gradle build scripts; it traverses the
given paths looking for files whose names end with the configured suffix
(default: `Test.java`). This means multi-module support is achieved by
supplying multiple root paths to a single invocation, or by running
separate per-module invocations and aggregating the results.

Both approaches are valid; the choice depends on whether you need per-module
caching and per-module SARIF artefacts, or a single consolidated output.

## Approach 1: single invocation across all modules

Supply all test source roots as positional arguments to a single MethodAtlas
invocation. The tool scans all paths and emits one unified CSV or SARIF:

```bash
java -jar methodatlas.jar \
  -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
  -content-hash -sarif -security-only \
  module-auth/src/test/java \
  module-payment/src/test/java \
  module-reporting/src/test/java \
  > security-tests.sarif
```

This approach is the simplest to configure and produces a single output
artefact. It is well-suited to projects with a small number of modules.

**Trade-off:** with a single cache file, a change to one module's test
source invalidates the cached classifications for classes in that module,
but leaves all other modules' cached results intact. The cache still
provides savings; it does not need to be structured per-module.

## Approach 2: per-module invocations with aggregation

Run a separate MethodAtlas invocation for each module and combine the
results into a project-level CSV. This approach provides finer-grained
control over caching and allows per-module SARIF artefacts.

### Per-module scan

```bash
for module in module-auth module-payment module-reporting; do
  java -jar methodatlas.jar \
    -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
    -content-hash \
    -ai-cache .methodatlas-cache-${module}.csv \
    ${module}/src/test/java \
    > scan-${module}.csv
done
```

Each module has its own cache file (`-ai-cache .methodatlas-cache-${module}.csv`),
so a change in one module does not force re-classification of classes in
other modules.

### Aggregating CSV outputs

To combine per-module CSVs into a single project-level CSV, keep the header
from the first file and concatenate the data rows from all others:

```bash
# Write header from the first module
head -n 1 scan-module-auth.csv > security-tests-all.csv

# Append data rows from all modules (skip the header of each)
for f in scan-module-*.csv; do
  tail -n +2 "$f"
done >> security-tests-all.csv
```

The resulting `security-tests-all.csv` can be used with `-diff` for project-
level delta gating, or retained as a consolidated evidence artefact.

### Producing a project-level SARIF

Run a second pass with `-sarif` using the aggregated cache:

```bash
java -jar methodatlas.jar \
  -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
  -sarif -security-only \
  -ai-cache .methodatlas-cache-combined.csv \
  module-auth/src/test/java \
  module-payment/src/test/java \
  module-reporting/src/test/java \
  > security-tests-all.sarif
```

## GitHub Actions: per-module matrix

For projects where each module's scan should appear as a separate pipeline
job, use a matrix strategy:

```yaml
jobs:
  scan:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        module: [module-auth, module-payment, module-reporting]
      fail-fast: false

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Download MethodAtlas
        run: |
          curl -fsSL -o methodatlas.jar \
            https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar

      - name: Restore AI cache
        uses: actions/cache@v4
        with:
          path: .methodatlas-cache-${{ matrix.module }}.csv
          key: methodatlas-${{ matrix.module }}-${{ hashFiles(format('{0}/src/test/java/**/*.java', matrix.module)) }}
          restore-keys: methodatlas-${{ matrix.module }}-

      - name: Scan module
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          CACHE_ARG=""
          if [ -f .methodatlas-cache-${{ matrix.module }}.csv ]; then
            CACHE_ARG="-ai-cache .methodatlas-cache-${{ matrix.module }}.csv"
          fi

          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -content-hash -sarif -security-only \
            $CACHE_ARG \
            ${{ matrix.module }}/src/test/java \
            | tee .methodatlas-cache-${{ matrix.module }}.csv \
            > scan-${{ matrix.module }}.sarif

      - uses: actions/upload-artifact@v4
        with:
          name: scan-${{ matrix.module }}
          path: |
            scan-${{ matrix.module }}.sarif
            .methodatlas-cache-${{ matrix.module }}.csv

  aggregate:
    runs-on: ubuntu-latest
    needs: scan
    steps:
      - uses: actions/download-artifact@v4
        with:
          pattern: scan-*
          merge-multiple: true
          path: scans

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Download MethodAtlas
        run: |
          curl -fsSL -o methodatlas.jar \
            https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar

      - name: Aggregate CSVs
        run: |
          head -n 1 scans/$(ls scans/*.csv | head -1 | xargs basename) > security-tests-all.csv
          for f in scans/*.csv; do tail -n +2 "$f"; done >> security-tests-all.csv

      - uses: actions/upload-artifact@v4
        with:
          name: methodatlas-project
          path: security-tests-all.csv
          retention-days: 90
```

## Cross-module coverage analysis

A common gap in multi-module projects is that a service module contains the
production implementation of a security control, while the integration tests
for that control live in a separate test module. MethodAtlas running on the
service module may find no security tests, even though the project as a whole
tests the control adequately.

### Identifying the gap

Run the aggregated scan and filter for modules with no security-relevant
methods:

```bash
# Count security-relevant tests per module (prefix = first two components of FQCN)
awk -F',' 'NR > 1 && $5 == "true" {
  split($1, parts, ".")
  print parts[1] "." parts[2]
}' security-tests-all.csv | sort | uniq -c | sort -rn
```

A module that appears in the full inventory but not in this filtered count
has test methods but none classified as security-relevant. This warrants
review: either the module has no security responsibilities, the tests are
misclassified, or security tests have not yet been written.

### Documenting intent

If a module intentionally delegates its security testing to an integration
or end-to-end test module, document this in the override file to prevent
audit confusion:

```yaml
# methodatlas-overrides.yaml
overrides:
  # module-reporting has no security tests because its auth
  # boundary is enforced and tested at the API gateway layer
  - fqcn: com.acme.reporting.ReportExportTest
    securityRelevant: false
    note: "Auth boundary tested in module-gateway/src/test — 2026-04-25 alice"
```

## Gradle multi-project builds

For Gradle multi-project builds, you can enumerate test source roots
programmatically in the pipeline rather than listing them statically:

```bash
# Discover all test source directories in the project
TEST_ROOTS=$(find . -path '*/src/test/java' -not -path '*/build/*' | tr '\n' ' ')

java -jar methodatlas.jar \
  -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
  -content-hash -sarif -security-only \
  $TEST_ROOTS \
  > security-tests.sarif
```

This approach automatically includes new submodules without pipeline changes,
but produces a single cache file for the entire project.

## Further reading

- [AI Result Caching](../ai/caching.md) — cache file format and invalidation rules
- [Delta Report](../usage-modes/delta.md) — comparing project-level scan outputs
- [Release Gating](release-gating.md) — count gate and delta gate for aggregated outputs
- [GitHub Actions](github-actions.md) — full single-module workflow reference
