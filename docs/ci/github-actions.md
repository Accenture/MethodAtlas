# GitHub Actions Integration

This page shows how to integrate MethodAtlas into a GitHub Actions workflow.
It covers three complementary techniques you can combine:

1. **PR annotations** — inline `::notice`/`::warning` markers on the PR diff (no licence required)
2. **SARIF upload** — findings appear in the GitHub Code Scanning tab (requires GitHub Advanced Security)
3. **Security test count gate** — pipeline fails when the security test count drops

---

## Minimal workflow: PR annotations

The simplest way to surface MethodAtlas findings is with the `-github-annotations`
flag. Each security-relevant test method emits a
[GitHub workflow command](https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/workflow-commands-for-github-actions)
that GitHub renders as an inline annotation on the PR diff:

- `::warning` when `ai_interaction_score >= 0.8` — the test only verifies that
  methods were called, not what they returned (potential placebo test).
- `::notice` otherwise — a well-formed security test worth reviewing.

```yaml
name: Security test scan

on:
  pull_request:

jobs:
  scan:
    runs-on: ubuntu-latest
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

      - name: Scan security tests (PR annotations)
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -github-annotations \
            src/test/java
```

!!! tip "No GitHub Advanced Security licence required"
    The `::notice` and `::warning` workflow commands are standard GitHub Actions
    features available on **all plan tiers** — free, Team, and Enterprise — for
    both public and private repositories.
    This is in contrast to SARIF upload via the `upload-sarif` action, which
    requires GitHub Advanced Security (an additional paid add-on for private
    repositories on GitHub Enterprise).
    If your organisation has not purchased Advanced Security, `-github-annotations`
    gives you inline PR feedback at zero additional cost.

---

## Caching AI results across runs

AI classification is the most expensive step. Use `-ai-cache` together with
`actions/cache` to skip classification for classes that have not changed since
the last scan:

```yaml
      - name: Restore MethodAtlas AI cache
        uses: actions/cache@v4
        with:
          path: .methodatlas-cache.csv
          key: methodatlas-${{ hashFiles('src/test/java/**/*.java') }}
          restore-keys: methodatlas-

      - name: Scan security tests with cache
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -content-hash \
            -github-annotations \
            ${{ hashFiles('.methodatlas-cache.csv') != '' && '-ai-cache .methodatlas-cache.csv' || '' }} \
            src/test/java \
            > .methodatlas-cache.csv
```

On the first run (cache miss) every class is classified via the API.
On subsequent runs only classes whose `content_hash` changed incur an API call;
unchanged classes are classified from the cache CSV in milliseconds.

---

## SARIF upload to Code Scanning

If your organisation has **GitHub Advanced Security**, you can upload the SARIF
output so findings appear in the *Security* → *Code scanning* tab:

```yaml
      - name: Scan and produce SARIF
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -sarif -security-only \
            src/test/java \
            > methodatlas.sarif

      - name: Upload SARIF to Code Scanning
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: methodatlas.sarif
          category: security-tests
```

!!! note
    SARIF upload requires GitHub Advanced Security, which is included in GitHub
    Enterprise Cloud and GitHub Enterprise Server, and can be purchased as an
    add-on for private repositories on GitHub Team and Free plans.
    Use `-github-annotations` instead if Advanced Security is not available.

---

## Security test count gate

Fail the pipeline if the security test count drops compared to the `main` branch
baseline. This protects against accidental or silent removal of security tests.

```yaml
      - name: Save baseline (on main)
        if: github.ref == 'refs/heads/main'
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -security-only -content-hash \
            src/test/java > baseline.csv
          
      - uses: actions/upload-artifact@v4
        if: github.ref == 'refs/heads/main'
        with:
          name: methodatlas-baseline
          path: baseline.csv

      - name: Download baseline (on PR)
        if: github.event_name == 'pull_request'
        uses: actions/download-artifact@v4
        with:
          name: methodatlas-baseline
          path: .

      - name: Count gate (on PR)
        if: github.event_name == 'pull_request'
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -security-only \
            src/test/java > current.csv

          baseline=$(tail -n +2 baseline.csv | wc -l)
          current=$(tail -n +2 current.csv | wc -l)

          echo "Baseline security tests: $baseline"
          echo "Current security tests:  $current"

          if [ "$current" -lt "$baseline" ]; then
            echo "::error::Security test count dropped from $baseline to $current"
            exit 1
          fi
```

---

## Full workflow combining all three

```yaml
name: Security test scan

on:
  push:
    branches: [main]
  pull_request:

jobs:
  scan:
    runs-on: ubuntu-latest
    permissions:
      security-events: write   # required for upload-sarif
      contents: read

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
          path: .methodatlas-cache.csv
          key: methodatlas-${{ hashFiles('src/test/java/**/*.java') }}
          restore-keys: methodatlas-

      - name: Run MethodAtlas
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          CACHE_ARG=""
          if [ -f .methodatlas-cache.csv ]; then
            CACHE_ARG="-ai-cache .methodatlas-cache.csv"
          fi

          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -content-hash -security-only \
            -github-annotations \
            -sarif \
            $CACHE_ARG \
            src/test/java \
            | tee .methodatlas-cache.csv > methodatlas.sarif

      - name: Upload SARIF
        if: always()
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: methodatlas.sarif
          category: security-tests
```

!!! warning "Note on combined output"
    The example above pipes both `::notice`/`::warning` lines and SARIF JSON to the
    same stream. In practice, run the two modes as separate steps — one with
    `-github-annotations` and one with `-sarif` — to keep the outputs clean.
