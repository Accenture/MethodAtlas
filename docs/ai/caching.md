# AI Result Caching

## Overview

Every AI-enriched scan submits each test class to an AI provider and waits for a response.
In a CI pipeline that runs on every commit this cost is often unnecessary: most test classes
do not change between runs, so their classifications are already known.

The `-ai-cache <file>` flag addresses this. It accepts a MethodAtlas CSV output from a
previous scan — produced with `-content-hash -ai` — and reuses the stored AI
classification for any class whose `content_hash` matches an entry in the cache. Classes
whose source has changed (different hash) are classified normally by calling the AI
provider.

```bash
# Day 1 – full scan; save the result as the cache
./methodatlas -ai -content-hash src/test/java > scan.csv

# Day 2, 3, … — unchanged classes come from the cache; changed ones hit the API
./methodatlas -ai -content-hash -ai-cache scan.csv src/test/java > scan-new.csv
```

## How it works

1. **Hash computation** — MethodAtlas computes a SHA-256 fingerprint of each class's AST
   string representation. This is the same value stored in the `content_hash` CSV column.
2. **Cache lookup** — before calling the AI provider for a class, MethodAtlas checks
   whether that fingerprint is present in the cache file.
3. **Hit** — the stored `AiClassSuggestion` is used directly; no API call is made.
4. **Miss** — the class has changed (or was not in the cache); the AI provider is called
   and the result is written to the new output file.

The new output file can be used as the cache for the next run.

## Requirements

The cache input file must have been produced with:

- **`-content-hash`** — without this column the cache loader finds no usable entries and
  treats every class as a cache miss.
- **`-ai`** — without AI columns there is no classification to restore.

If either column is absent, the cache silently degrades to a no-op: every class is
classified by the AI provider as normal.

## Two-pass pattern for SARIF output

SARIF output does not carry the `content_hash` column, so a SARIF file cannot itself serve
as a cache for the next run. When you need both a persistent cache **and** SARIF output,
use two consecutive MethodAtlas invocations in the same CI job:

```bash
# Pass 1: CSV — calls AI only for classes that changed since the cached run.
#          Produces an up-to-date cache for the next run.
./methodatlas \
  -ai -ai-provider github_models -ai-api-key-env GITHUB_TOKEN \
  -content-hash \
  -ai-cache prev-cache.csv \   # omit on the very first run
  src/test/java \
  > current-cache.csv

# Pass 2: SARIF — reads exclusively from the cache produced in pass 1.
#          Makes zero AI calls because every class is now in the cache.
./methodatlas \
  -ai -ai-provider github_models -ai-api-key-env GITHUB_TOKEN \
  -content-hash \
  -ai-cache current-cache.csv \
  -sarif \
  src/test/java \
  > results.sarif
```

The two-pass approach guarantees:

- The SARIF findings and the stored cache are always in sync.
- No AI calls are made during the SARIF pass, eliminating rate-limit exposure for that
  pass entirely.
- All findings from unchanged classes remain present in the SARIF output, so GitHub Code
  Scanning does not close them as resolved between runs.

The reusable workflow `methodatlas-analysis.yml` shipped with this project implements this
pattern automatically. See [GitHub Actions](../ci/github-actions.md) for the full workflow
and [CI Setup](../ci-setup.md) for integration guidance.

## CI workflow

The following examples show how to wire the cache into a GitHub Actions workflow using
`actions/cache`.

The cache file is stored in compressed form (`.gz`) to minimise GitHub Actions
cache storage consumption. CSV text typically compresses to 10–20 % of its
original size, making the cache practical even for very large test suites.

### CSV output with caching

```yaml
- name: Restore MethodAtlas AI cache
  uses: actions/cache@v4
  with:
    path: .methodatlas-cache.csv.gz
    key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}
    restore-keys: |
      methodatlas-ai-${{ github.ref_name }}-
      methodatlas-ai-

- name: Run MethodAtlas
  run: |
    [ -f .methodatlas-cache.csv.gz ] && gunzip -k .methodatlas-cache.csv.gz
    CACHE_ARGS=()
    [ -f .methodatlas-cache.csv ] && CACHE_ARGS=("-ai-cache" ".methodatlas-cache.csv")
    ./methodatlas -ai -content-hash "${CACHE_ARGS[@]}" src/test/java \
      > .methodatlas-cache-new.csv
    mv .methodatlas-cache-new.csv .methodatlas-cache.csv
    gzip -c .methodatlas-cache.csv > .methodatlas-cache.csv.gz
```

On the first run (cold cache) every class is classified by the AI. On subsequent runs,
only modified classes incur API calls.

### SARIF output with caching (two-pass)

```yaml
- name: Restore MethodAtlas AI cache
  uses: actions/cache/restore@v4
  with:
    path: .methodatlas-cache.csv.gz
    key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}
    restore-keys: |
      methodatlas-ai-${{ github.ref_name }}-
      methodatlas-ai-

- name: Run MethodAtlas — CSV pass (cache refresh)
  run: |
    [ -f .methodatlas-cache.csv.gz ] && gunzip -k .methodatlas-cache.csv.gz
    CACHE_ARGS=()
    [ -f .methodatlas-cache.csv ] && CACHE_ARGS=("-ai-cache" ".methodatlas-cache.csv")
    ./methodatlas -ai -content-hash "${CACHE_ARGS[@]}" src/test/java \
      > .methodatlas-cache-new.csv
    mv .methodatlas-cache-new.csv .methodatlas-cache.csv
    # Keep the uncompressed file for pass 2; also produce the .gz for storage.
    gzip -c .methodatlas-cache.csv > .methodatlas-cache.csv.gz

- name: Run MethodAtlas — SARIF pass (zero AI calls)
  run: |
    ./methodatlas -ai -content-hash \
      -ai-cache .methodatlas-cache.csv \
      -sarif \
      src/test/java > results.sarif

- name: Save MethodAtlas AI cache
  if: success()
  uses: actions/cache/save@v4
  with:
    path: .methodatlas-cache.csv.gz
    key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}
```

## Combining with -diff

The cache output is a valid MethodAtlas CSV that can be diffed against a baseline:

```bash
./methodatlas -ai -content-hash -ai-cache last-release.csv src/test/java > current.csv
./methodatlas -diff last-release.csv current.csv
```

This shows exactly which security test classifications changed — useful for release notes
and compliance evidence.

## Cache staleness

The cache is keyed by content hash, not by class name. If a class is renamed without
changing its body the hash changes and the cache misses. If the class body changes the
hash changes. The cache never returns stale results: a hash match is a cryptographic
guarantee that the source is identical to when the classification was produced.
