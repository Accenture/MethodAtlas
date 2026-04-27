# Multi-root and monorepo scanning

In standard Maven and Gradle projects the test sources live under a single root — typically `src/test/java/` — and every fully qualified class name (FQCN) is unique within that root. In monorepos and multi-module builds the same FQCN can appear under multiple source trees. When that happens, records from different modules become indistinguishable in the output: the `fqcn`, `method`, and `loc` columns are identical even though the classes have different implementations, owners, or security relevance.

This page explains the problem, the `-emit-source-root` flag that solves it, and how to integrate the flag into CI pipelines and downstream tools.

## The problem: duplicate FQCNs across modules

Consider a project with two modules that each define their own authentication tests:

```
my-monorepo/
  module-a/src/test/java/com/acme/auth/AuthTest.java   (12 test methods)
  module-b/src/test/java/com/acme/auth/AuthTest.java   (8 test methods)
```

Both classes share the package `com.acme.auth` and the class name `AuthTest`. Running a plain scan across both roots:

```bash
./methodatlas \
  module-a/src/test/java \
  module-b/src/test/java
```

produces a CSV where rows from `module-a` and `module-b` appear side by side with no distinguishing information:

```text
fqcn,method,loc,tags,display_name
com.acme.auth.AuthTest,testLogin,12,security,
com.acme.auth.AuthTest,testLogout,8,,
com.acme.auth.AuthTest,testLogin,5,security,
com.acme.auth.AuthTest,testLogout,3,,
```

The root that produced each row is lost. Downstream tools — spreadsheets, CI gates, dashboards, or the `-ai-cache` flag — cannot attribute a row to the correct module.

## The solution: `-emit-source-root`

Pass `-emit-source-root` to append a `source_root` column to CSV output:

```bash
./methodatlas -emit-source-root \
  module-a/src/test/java \
  module-b/src/test/java
```

The output now unambiguously identifies the origin of each row:

```text
fqcn,method,loc,tags,display_name,source_root
com.acme.auth.AuthTest,testLogin,12,security,,module-a/src/test/java/
com.acme.auth.AuthTest,testLogout,8,,,module-a/src/test/java/
com.acme.auth.AuthTest,testLogin,5,security,,module-b/src/test/java/
com.acme.auth.AuthTest,testLogout,3,,,module-b/src/test/java/
```

The `source_root` value is the CWD-relative path of the scan root with a trailing `/`. When the scan root is the working directory itself (e.g. scanning `.`), the column is empty in CSV.

In plain-text mode the token is `SRCROOT=`:

```bash
./methodatlas -plain -emit-source-root \
  module-a/src/test/java \
  module-b/src/test/java
```

```text
com.acme.auth.AuthTest, testLogin, LOC=12, TAGS=security, DISPLAY=-, SRCROOT=module-a/src/test/java/
com.acme.auth.AuthTest, testLogout, LOC=8, TAGS=-, DISPLAY=-, SRCROOT=module-a/src/test/java/
com.acme.auth.AuthTest, testLogin, LOC=5, TAGS=security, DISPLAY=-, SRCROOT=module-b/src/test/java/
com.acme.auth.AuthTest, testLogout, LOC=3, TAGS=-, DISPLAY=-, SRCROOT=module-b/src/test/java/
```

## Column position

The `source_root` column appears immediately after `display_name` and before any other optional columns. The full column ordering with all optional flags:

```text
fqcn, method, loc, tags, display_name, source_root, content_hash,
ai_security_relevant, ai_display_name, ai_tags, ai_reason, ai_interaction_score,
ai_confidence, tag_ai_drift
```

## SARIF and GitHub Annotations

This flag has no effect on SARIF or GitHub Annotations output. For those formats the source root is already embedded in the file path (`src/test/java/com/acme/auth/AuthTest.java`) and SARIF consumers (such as GitHub Code Scanning) can resolve that path to the correct module.

## Combining with AI enrichment

```bash
./methodatlas -ai -emit-source-root -content-hash \
  module-a/src/test/java \
  module-b/src/test/java \
  > scan.csv
```

The resulting CSV has:

```text
fqcn,method,loc,tags,display_name,source_root,content_hash,ai_security_relevant,...
```

With both `source_root` and `content_hash`, each row is uniquely identified by `(fqcn, method, source_root, content_hash)`. This tuple is stable across re-scans as long as the class body and the scan root path do not change.

## CI pipeline integration

A typical CI step in a monorepo:

```yaml
# .github/workflows/security-scan.yml
- name: MethodAtlas security inventory
  run: |
    ./methodatlas -ai -emit-source-root \
      module-a/src/test/java \
      module-b/src/test/java \
      > security-inventory.csv

- name: Upload inventory
  uses: actions/upload-artifact@v4
  with:
    name: security-inventory
    path: security-inventory.csv
```

### Per-module security count gate

Filter the CSV by `source_root` to enforce per-module count gates:

```bash
# Count security-relevant methods in module-a only
MODULE_A_COUNT=$(awk -F, '
  NR > 1 && $6 == "module-a/src/test/java/" && $7 == "true" { count++ }
  END { print count+0 }
' security-inventory.csv)

echo "Module A security test count: $MODULE_A_COUNT"

if [ "$MODULE_A_COUNT" -lt 10 ]; then
  echo "::error::Module A has fewer than 10 security tests"
  exit 1
fi
```

Column positions assume the default flag combination (`-ai -emit-source-root`):
- column 6 = `source_root`
- column 7 = `ai_security_relevant`

Adjust the column indices if `-content-hash`, `-ai-confidence`, or `-drift-detect` are also set.

## Using `-ai-cache` with multiple roots

The AI result cache (`-ai-cache`) is keyed by SHA-256 content hash, not by FQCN. Two classes with the same FQCN but different implementations will have different hashes and will each be classified independently — no special handling is required.

```bash
# First run: build the cache
./methodatlas -ai -content-hash -emit-source-root \
  module-a/src/test/java \
  module-b/src/test/java \
  > scan.csv

# Subsequent runs: unchanged classes are free
./methodatlas -ai -content-hash -emit-source-root \
  -ai-cache scan.csv \
  module-a/src/test/java \
  module-b/src/test/java \
  > scan-new.csv
```

## When not to use this flag

- **Single-root projects:** the flag adds an empty column that provides no value.
- **SARIF or GitHub Annotations output:** the flag is silently ignored; the file path already encodes the source root.
- **Delta reports:** the `-diff` flag compares two CSVs and ignores all other flags including `-emit-source-root`.
