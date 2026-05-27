# Audit Trail

MethodAtlas produces several artefacts intended for downstream audit
consumption. This page documents what is written, when, and where.

## Artefacts

| Artefact | Producer | Location | Schema |
| --- | --- | --- | --- |
| Evidence CSV | GUI `AuditWriter` after each `Save All Changes` | `.methodatlas/methodatlas-YYYYMMDD-HHmmss.csv` | `DeltaReport` CSV schema |
| Override YAML | GUI `AuditWriter`, cumulative | `.methodatlas/overrides.yaml` | `ClassificationOverride` schema |
| Scan-run identity | CLI on every invocation | in-memory via `ScanRunContext`; carried into logs | `ScanRun` record |
| Structured JSON log | Optional, via `JsonLineFormatter` | wherever the configured JUL handler writes | documented below |

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
