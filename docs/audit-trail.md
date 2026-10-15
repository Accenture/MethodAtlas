# Audit Trail

MethodAtlas produces several artefacts intended for downstream audit
consumption. This page documents what is written, when, and where.

## Artefacts

| Artefact | Producer | Location | Schema |
| --- | --- | --- | --- |
| Evidence CSV | GUI `AuditWriter` after each `Save All Changes` | `.methodatlas/methodatlas-YYYYMMDD-HHmmss.csv` | `DeltaReport` CSV schema |
| Override YAML | GUI `AuditWriter`, cumulative | `.methodatlas/overrides.yaml` | `ClassificationOverride` schema |
| Audit manifest | GUI `AuditWriter`, refreshed on every save | `.methodatlas/manifest.sha256` | `sha256sum` format, same as the evidence-pack manifest |
| Scan-run identity | CLI on every invocation | in-memory via `ScanRunContext`; carried into logs | `ScanRun` record |
| Structured JSON log | Optional, via `JsonLineFormatter` | wherever the configured JUL handler writes | documented below |

## GUI audit artefacts (schema version 3)

Every **Save All Changes** writes into `.methodatlas/` under the scanned root:

- **Evidence CSV** (`methodatlas-YYYYMMDD-HHmmss.csv`). The final column,
  `ai_taxonomy_version`, records the taxonomy under which the AI suggestion on
  that row was produced (`built-in/default@<revision>`; the GUI always uses
  the built-in default taxonomy). It is blank for rows without an AI
  suggestion. The `content_hash`
  column carries the same per-class SHA-256 fingerprint the CLI emits with
  `-content-hash`, computed over the class as it was scanned, before the
  save patched the file. It is blank only when the class source could not be
  read. The file is created with create-new semantics: if a file of that
  second already exists, a counter suffix is used
  (`methodatlas-YYYYMMDD-HHmmss-1.csv`, `-2`, ...), so an existing audit
  record is never overwritten.
- **Override YAML** (`overrides.yaml`), cumulative, unchanged.
- **Manifest** (`manifest.sha256`), one `<sha256>  <filename>` line per
  evidence CSV and for `overrides.yaml`, sorted by name, verifiable with
  `sha256sum -c manifest.sha256` from inside `.methodatlas/`. Digests of
  earlier evidence CSVs are carried over unchanged and never recomputed, so a
  later edit of an old CSV remains detectable; the digest of the new CSV and
  of the cumulative `overrides.yaml` is refreshed on each save. The manifest
  provides tamper evidence by comparison only: it is **not signed**, so
  someone who can rewrite the directory can rewrite the manifest too. Anchor
  it (for example in version control or an evidence pack) if that matters.

**Manifest trust model.** The manifest is hash-only. It shows that a listed
file still has the bytes recorded at its last save; it does **not cover itself**
and carries no signature, so anyone able to edit `.methodatlas/` can regenerate
it. To make tampering detectable, anchor it outside the working tree: commit it
to version control, or include it in a signed evidence pack. `overrides.yaml`
is cumulative and mutable, so before each save the writer compares its current
digest with the previous manifest entry; if the two differ (an edit made outside
MethodAtlas between saves) a `WARNING` is logged and the save proceeds, rather
than silently recording the new digest. If the override step fails after the
evidence CSV was written, the manifest is still updated so the CSV is never
orphaned outside it. The evidence CSV uses `\n` line endings on every platform,
byte-compatible with the CLI CSV.

**Determinism scope.** The reproducibility guarantee (byte-identical output for
identical inputs on every OS) covers the *structural scan output* — the scan
CSV, JSON and SARIF documents. It does not extend to the human-readable
reports (evidence report, gap report, coverage report) or to the evidence-pack
container, which may use platform line endings or embed timestamps.

Schema version 3 (previously 2) appends the `ai_taxonomy_version` column as
the last column of the evidence CSV. Schema version 2 (previously 1) populated
`content_hash`, added the manifest and made file names collision-proof. Existing
column order and `ClassificationOverride` fields are unchanged across all
versions. The CLI scan CSV, JSON and SARIF outputs carry the same
taxonomy-version stamp (see [Taxonomy](ai/taxonomy.md#taxonomy-version-stamp));
the CLI CSV schema has no version constant of its own, so the addition is
recorded in the [Migration Guide](migration.md).

## Scan-run identity

Every CLI invocation creates a `ScanRun` record at the top of
`MethodAtlasApp.run` and stores it on a thread-local through
`ScanRunContext`. The record is the audit-trail anchor for the rest of
the run.

```java
public record ScanRun(
    String runId,             // 16-character lowercase hex correlation id
    Instant startedAt,        // wall-clock time at run construction
    String toolVersion,       // JAR manifest version, or "dev"
    String configFingerprint  // SHA-256 of canonical CliConfig text
) { }
```

Two invocations of MethodAtlas with byte-identical configuration produce
the same `configFingerprint` even on different machines, which lets
operators correlate runs without sharing the full configuration.

## Structured JSON log layout

Activate `JsonLineFormatter` to emit one JSON object per JUL log record.
Each object carries the correlation id from `ScanRunContext`, so log
lines from a single run can be filtered out of a shared CI log stream.

### Schema

```json
{
  "timestamp": "2026-05-27T13:45:06Z",
  "level":     "INFO",
  "logger":    "org.egothor.methodatlas.ai.AiSuggestionEngineImpl",
  "thread":    "main",
  "message":   "Querying AI for com.acme.AuthTest (5 methods)",
  "runId":     "a1b2c3d4e5f60718",
  "thrown":    "java.lang.IllegalStateException: ...\n\tat ..."
}
```

| Field | Always present | Notes |
| --- | --- | --- |
| `timestamp` | yes | ISO-8601 instant derived from `LogRecord.getMillis()` |
| `level` | yes | JUL level name (`FINE`, `INFO`, `WARNING`, `SEVERE`) |
| `logger` | yes | Full logger name; empty string when unset |
| `thread` | yes | Name of the emitting thread |
| `message` | yes | Formatted message with parameters interpolated |
| `runId` | only when set | Omitted when no `ScanRun` is present on the current thread |
| `thrown` | only when set | Full stack trace as a single string (newlines escaped) |

### Activation

The formatter is not installed automatically. It is available as a
public class and activated through standard `java.util.logging`
mechanisms.

#### Activate programmatically

```java
Handler handler = new ConsoleHandler();
handler.setFormatter(new JsonLineFormatter());
Logger root = Logger.getLogger("org.egothor.methodatlas");
root.addHandler(handler);
root.setUseParentHandlers(false);
```

#### Activate via `logging.properties`

```properties
handlers = java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.formatter = org.egothor.methodatlas.JsonLineFormatter
org.egothor.methodatlas.level = INFO
```

Invoke with `-Djava.util.logging.config.file=path/to/logging.properties`.

### Encoding and escape handling

The formatter produces UTF-8 single-line output. Embedded characters are
escaped per RFC 8259:

- `"` &rarr; `\"`
- `\` &rarr; `\\`
- `\n`, `\r`, `\t`, `\b`, `\f` &rarr; their `\x` escapes
- Other control characters (`< 0x20`) &rarr; `\uXXXX`

Each record terminates with a single `\n`, so consumers can read the
stream as newline-delimited JSON (NDJSON).

## Schema stability

The CSV (`DeltaReport`) and YAML (`ClassificationOverride`) schemas are
load-bearing for downstream compliance tooling. Field names, types, and
column ordering are stable. Any change requires a schema version bump
plus a synchronised update to this document, `docs/output-formats.md`,
and `README.md`. See `CONTRIBUTING.md` &rarr; "Audit output schema" for
the policy.

A pinned-schema regression guard,
`SchemaCompatibilityGoldenTest` in the `methodatlas-emit` test tree,
hard-codes the exact ordered CSV column list and the exact
`ClassificationOverride` YAML field set. Any silent rename, reorder,
retype, or removal fails the build. A deliberate schema change therefore
updates the golden constants in that test in the same commit as the
version bump and the documentation above.
