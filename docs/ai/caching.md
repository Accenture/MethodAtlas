# AI result caching

The `-ai-cache` flag lets MethodAtlas reuse stored AI classifications for test classes that have not changed since the previous scan, eliminating redundant API calls in CI pipelines where most classes remain unchanged between runs.

## When to use

Enable caching in any CI pipeline where AI enrichment is enabled and the full test suite changes only partially between runs. Without caching, every run submits every test class to the AI provider, regardless of whether the class changed. With caching, only modified or new classes incur API calls.

See [AI Providers](providers.md) for provider configuration and the [`-ai-cache`](../cli-reference.md#-ai-cache) flag description in the CLI reference.

## How it works

1. **Hash computation** — MethodAtlas computes a SHA-256 fingerprint of each class's AST string representation. This is the same value stored in the `content_hash` CSV column.
2. **Cache lookup** — before calling the AI provider for a class, MethodAtlas checks whether that fingerprint is present in the cache file.
3. **Cache hit** — the stored `AiClassSuggestion` is used directly; **no API call is made**. The stored result is written into the new output file as-is.
4. **Cache miss** — the class has changed or was not in the cache. The AI provider is called, the result is received, and it is written to the new output file.

The new output file can be used as the cache for the next run. The cache grows naturally as the codebase grows and shrinks when classes are deleted.

## Basic usage

```bash
# Day 1 – full scan; save the result as the cache
./methodatlas -ai -content-hash src/test/java > scan.csv

# Day 2, 3, … — unchanged classes come from the cache; changed ones hit the API
./methodatlas -ai -content-hash -ai-cache scan.csv src/test/java > scan-new.csv
```

## Requirements

The cache input file must have been produced with:

- **`-content-hash`** — without this column the cache loader finds no usable entries and treats every class as a cache miss.
- **`-ai`** — without AI columns there is no classification to restore.

If either column is absent, the cache silently degrades to a no-op: every class is classified by the AI provider as normal.

## Unified cache: one pass for SARIF, classifications, and credentials

The unified cache (JSON Lines) is written by [`-ai-cache-out`](../cli-reference.md#-ai-cache) and read by `-ai-cache`. Unlike a scan CSV it is independent of the output format, so a **single** run can emit SARIF *and* persist the cache — the older CSV-then-SARIF two-pass workaround is no longer needed.

Each entry is keyed by the class content hash and tagged with a *prompt-catalogue signature* (a SHA-256 over the effective prompt templates). An entry is reused only when **both** match, so editing a prompt template correctly invalidates stale answers. Crucially, one entry carries the full answer — method classifications **and** any credential-triage verdicts — so a combined `-ai -detect-secrets` run caches both: an unchanged class on the next run is served from cache for classification *and* credentials, with zero AI calls.

```bash
# One pass: classify methods, triage credentials, emit SARIF, update the cache.
# Day 1 the cache file does not exist yet (cold start). Day 2+ reuses unchanged
# classes — for both classification and credentials — at no API cost.
./methodatlas \
  -ai -ai-provider github_models -ai-api-key-env GITHUB_TOKEN \
  -content-hash -drift-detect \
  -detect-secrets -secrets-out credentials.csv \
  -ai-cache cache.json -ai-cache-out cache.json \
  -sarif src/test/java \
  > results.sarif
```

This keeps the SARIF and the cache in sync, makes AI calls only for changed classes, and keeps findings for unchanged classes present in the SARIF so GitHub Code Scanning does not close them between runs. A legacy scan CSV is still accepted by `-ai-cache` (auto-detected): its classifications are reused by content hash, but it carries no credential verdicts.

The reusable workflow `methodatlas-analysis.yml` shipped with this project implements exactly this single-pass pattern. See [GitHub Actions](../ci/github-actions.md) for the full workflow and [CI Setup](../ci-setup.md) for integration guidance.

## CI workflow examples

The cache file is stored in compressed form (`.gz`) to minimise GitHub Actions cache storage consumption; the JSON-Lines cache compresses well even for very large test suites.

### GitHub Actions example

A single cached pass classifies, emits SARIF, and refreshes the unified cache:

```yaml
- name: MethodAtlas AI cache
  uses: actions/cache@v4        # restores before the run, saves after
  with:
    path: .methodatlas-cache.json.gz
    key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}
    restore-keys: |
      methodatlas-ai-${{ github.ref_name }}-
      methodatlas-ai-

- name: Run MethodAtlas (cached, single pass)
  run: |
    [ -f .methodatlas-cache.json.gz ] && gunzip -k .methodatlas-cache.json.gz
    CACHE_ARGS=()
    [ -f .methodatlas-cache.json ] && CACHE_ARGS=("-ai-cache" ".methodatlas-cache.json")
    ./methodatlas -ai -content-hash \
      "${CACHE_ARGS[@]}" \
      -ai-cache-out .methodatlas-cache.json \
      -sarif \
      src/test/java > results.sarif
    gzip -c .methodatlas-cache.json > .methodatlas-cache.json.gz
```

On the first run (cold cache) every class is classified by the AI. On subsequent runs only modified classes incur API calls; swap `-sarif` for CSV or `-github-annotations` as needed — the caching is identical.

## Combining with `-diff`

The cache output is a valid MethodAtlas CSV that can be diffed against a baseline:

```bash
./methodatlas -ai -content-hash -ai-cache last-release.csv src/test/java > current.csv
./methodatlas -diff last-release.csv current.csv
```

This shows exactly which security test classifications changed — useful for release notes and compliance evidence.

## Cache staleness

The cache is keyed by content hash, not by class name. If a class is renamed without changing its body the hash changes and the cache misses. If the class body changes the hash changes. The cache never returns stale results: a hash match is a cryptographic guarantee that the source is identical to when the classification was produced.
