# Static Inventory

Scan test source files and emit structured metadata without contacting an AI provider.

**When to use:** CI pipelines that need a method inventory for diffing, SARIF
generation for code scanning dashboards, or initial project exploration before
deciding on an AI strategy.

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

# Recognise a custom test annotation
./methodatlas -test-annotation Test -test-annotation ScenarioTest src/test/java
```

The first occurrence of `-file-suffix` or `-test-annotation` replaces the built-in
defaults; subsequent occurrences append to the active set.

## Scanning multiple source roots

```bash
./methodatlas module-a/src/test/java module-b/src/test/java
```

All roots are scanned in a single pass; the output is a single CSV.
