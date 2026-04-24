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

The new output file can be used as the cache for the next run. A typical CI setup keeps
`scan.csv` as a build artifact and passes it to the next run.

## Requirements

The cache input file must have been produced with:

- **`-content-hash`** — without this column the cache loader finds no usable entries and
  treats every class as a cache miss.
- **`-ai`** — without AI columns there is no classification to restore.

If either column is absent, the cache silently degrades to a no-op: every class is
classified by the AI provider as normal.

## CI workflow

```yaml
# GitHub Actions example
- name: Restore scan cache
  uses: actions/cache@v4
  with:
    path: scan.csv
    key: methodatlas-${{ hashFiles('src/test/java/**') }}
    restore-keys: methodatlas-

- name: Run MethodAtlas
  run: |
    CACHE_ARG=""
    [ -f scan.csv ] && CACHE_ARG="-ai-cache scan.csv"
    ./methodatlas -ai -content-hash $CACHE_ARG src/test/java > scan-new.csv

- name: Update scan cache
  run: mv scan-new.csv scan.csv
```

On the first run (cold cache) every class is classified by the AI. On subsequent runs,
only modified classes incur API calls.

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
