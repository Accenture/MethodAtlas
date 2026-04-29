# Static Inventory

Static inventory mode scans test source files and emits structured metadata — class names, method names, line counts, and source-level annotations — without contacting an AI provider.

## When to use this mode

- You want a quick, zero-cost inventory of all test methods in a project without configuring an AI provider.
- You are setting up a CI pipeline that needs a method inventory for [delta comparison](delta.md) between builds.
- You want to generate SARIF output for a GitHub Code Scanning dashboard without AI enrichment.
- You are exploring a new project and want to understand its test structure before deciding on an AI strategy.
- You plan to run AI enrichment later; static inventory gives you a baseline CSV to diff against.

Note: without AI enrichment, the output contains no `ai_security_relevant`, `ai_tags`, or `ai_reason` columns. Security classification requires [API AI enrichment](api-ai.md) or the [Manual AI workflow](manual.md).

## Basic scan

```bash
# CSV to stdout (default output)
./methodatlas src/test/java

# Save to file
./methodatlas src/test/java > inventory.csv
```

Sample output:

```
fqcn,method,loc,tags,display_name
com.example.AuthServiceTest,loginWithValidCredentials,12,,
com.example.AuthServiceTest,loginWithExpiredToken,8,security,
com.example.PaymentTest,chargeCard,15,,
```

## Plain text output

```bash
./methodatlas -plain src/test/java
```

## SARIF output for GitHub Code Scanning

```bash
./methodatlas -sarif src/test/java > results.sarif
```

Upload with the [upload-sarif](https://github.com/github/codeql-action) action:

```yaml
- uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: results.sarif
```

Without AI enrichment, all methods receive SARIF rule `test-method` and level
`none`. Security-relevant classification requires Mode 2 or Mode 3.

## Scan with metadata header

The `-emit-metadata` flag prepends scan provenance as comment lines before the
CSV header — useful for archiving results with traceability information:

```bash
./methodatlas -emit-metadata src/test/java
```

```
# tool_version: 1.2.0
# scan_timestamp: 2025-04-09T10:15:30Z
# taxonomy: built-in/default
fqcn,method,loc,tags,...
```

## Content hash fingerprinting

```bash
./methodatlas -content-hash src/test/java
```

Appends a SHA-256 hash of each class's AST representation to every record.
When a class contains multiple test methods all of them share the same hash.
Useful for incremental scanning and correlating results across pipeline runs.

## Filtering which files are scanned

```bash
# Include integration tests alongside unit tests
./methodatlas -file-suffix Test.java -file-suffix IT.java src/test/java

# Recognise a custom test annotation (Java/Kotlin)
./methodatlas -test-marker Test -test-marker ScenarioTest src/test/java
```

The first occurrence of `-file-suffix` or `-test-marker` replaces the built-in
defaults; subsequent occurrences append to the active set. The legacy
`-test-annotation` flag is still accepted as an alias for `-test-marker`.

## Scanning multiple source roots

```bash
./methodatlas module-a/src/test/java module-b/src/test/java
```

All roots are scanned in a single pass; the output is a single CSV. For monorepos where the same fully qualified class name appears in multiple modules, see [Multi-root and monorepo scanning](multi-root.md).

## End-to-end scenario: baseline inventory before AI enrichment

A team is adopting MethodAtlas for the first time. They want a quick picture of how many test methods exist and which already carry a `security` tag — before committing to an AI provider subscription.

```bash
# Step 1: produce the baseline inventory
./methodatlas -emit-metadata -content-hash src/test/java > baseline.csv

# Step 2: count total test methods
echo "Total test methods: $(( $(wc -l < baseline.csv) - 1 ))"

# Step 3: count methods already tagged security
awk -F',' 'NR > 1 && $4 ~ /security/' baseline.csv | wc -l
```

Sample output from step 1:

```
# tool_version: 1.2.0
# scan_timestamp: 2026-04-29T09:00:00Z
# taxonomy: built-in/default
fqcn,method,loc,tags,display_name,content_hash
com.example.AuthServiceTest,loginWithValidCredentials,12,,, a3f1...
com.example.AuthServiceTest,loginWithExpiredToken,8,security,,b9c2...
com.example.PaymentTest,chargeCard,15,,,d4e5...
```

The team can see at a glance that `loginWithExpiredToken` is already tagged, while `chargeCard` is not. Later, when AI enrichment is configured, they diff the AI-enriched CSV against `baseline.csv` using the [delta report](delta.md) to see what the AI reclassified.

For flags referenced here, see the [CLI reference](../cli-reference.md).
