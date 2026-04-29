# Multi-root and monorepo scanning

Multi-root scanning allows MethodAtlas to scan test sources across multiple directories in a single pass, producing a unified output. The [`-emit-source-root`](../cli-reference.md#-emit-source-root) flag adds a `source_root` column so that each row can be attributed to the correct module.

## When to use this mode

- Your project is a Maven or Gradle multi-module build and test sources are spread across several `src/test/java` directories.
- You are scanning a monorepo where multiple microservices or libraries each have their own test tree.
- You want a single consolidated security inventory across all modules, with per-module attribution.
- You are running per-module CI gates (e.g. "module-a must have at least 10 security tests") from a single scan output.

## Why the same FQCN can appear in multiple source roots

In Java, a fully qualified class name (FQCN) combines the package name and the class name, for example `com.acme.auth.AuthTest`. The FQCN does not encode the module or the directory it came from. This is intentional in single-module projects — every FQCN in a project must be unique. In a monorepo, however, this uniqueness requirement applies only within each module's classpath, not across all modules simultaneously.

Two common patterns produce duplicate FQCNs across modules:

### Pattern 1: shared package conventions

Large organisations often adopt a single Java package namespace (e.g. `com.acme`) across all teams. When each team independently names their authentication test class `AuthTest` and places it in `com.acme.auth`, the result is:

```
my-monorepo/
  payments-service/
    src/test/java/
      com/acme/auth/AuthTest.java      ← verifies payments service auth
  identity-service/
    src/test/java/
      com/acme/auth/AuthTest.java      ← verifies identity service auth
```

Both files compile correctly because each module's classpath is independent. But both produce the FQCN `com.acme.auth.AuthTest` in the MethodAtlas output.

### Pattern 2: shared test utilities and test modules

Some Gradle builds extract common test infrastructure into a separate `:test-utils` module that other modules depend on. If the test utility module shares the same base package as the modules that use it, and both are passed to MethodAtlas as scan roots, the same FQCN can appear from multiple paths:

```
my-monorepo/
  test-utils/
    src/test/java/
      com/acme/security/BaseSecurityTest.java
  module-a/
    src/test/java/
      com/acme/security/BaseSecurityTest.java  ← copied or customised variant
```

In both patterns, MethodAtlas cannot distinguish between the two classes without knowing the source root.

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
