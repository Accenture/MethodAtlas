# Discovery plugins

MethodAtlas discovers test methods through a plugin system. Each plugin handles
one language or test framework. Plugins are loaded automatically at startup via
`ServiceLoader` — placing a plugin JAR on the classpath is enough to activate it.

This page maps the general configuration terms to what they mean in each
supported language so you know exactly what to put in your YAML or on the
command line.

## Configuration model

Every plugin receives three pieces of configuration:

| Config knob | CLI flag | YAML key | What it does |
|---|---|---|---|
| **File suffixes** | `-file-suffix` | `fileSuffixes` | Selects which files the plugin should read. Universal — every language has files with extensions. May be prefixed with a plugin ID to target one plugin (see below). |
| **Test markers** | `-test-marker` | `testMarkers` | Names the identifiers that mark a method as a test. The *meaning* of "marker" is language-specific (see below). Empty = use plugin defaults. |
| **Properties** | `-property key=value` | `properties` | Open-ended key/value pairs for plugin-specific settings that do not fit either field above. Plugins ignore keys they do not recognise. |

### Targeting a suffix at one plugin

By default a `-file-suffix` (or `fileSuffixes:` entry) is delivered to **every**
loaded plugin. To restrict a suffix to a single plugin, prefix the value with the
plugin's ID and a colon (`:`):

```bash
# "Test.java" → java plugin only; "Test.cs" → dotnet plugin only
./methodatlas \
  -file-suffix java:Test.java \
  -file-suffix dotnet:Test.cs \
  src/
```

```yaml
fileSuffixes:
  - java:Test.java
  - dotnet:Test.cs
```

The colon character is the separator because it cannot appear in a file name on
any mainstream operating system (Windows, macOS, Linux). A value without a colon
is global and goes to every plugin, which is the correct choice for most single-
language projects.

| Example value | Delivered to |
|---|---|
| `Test.java` | all plugins |
| `java:Test.java` | `java` plugin only |
| `dotnet:BadNaming.cs` | `dotnet` plugin only |

Built-in plugin IDs: **`java`** (`methodatlas-discovery-jvm`), **`dotnet`**
(`methodatlas-discovery-dotnet`), and **`typescript`**
(`methodatlas-discovery-typescript`). Third-party plugins declare their own ID
via `TestDiscovery.pluginId()`.

If no matching suffix reaches a plugin (e.g. all entries are targeted at other
plugins), that plugin falls back to its language-specific built-in default
(`Test.java` for Java; `.cs` for .NET).

## Java and Kotlin (JVM)

**Plugin class:** `org.egothor.methodatlas.discovery.jvm.JavaTestDiscovery`

In Java and Kotlin, a test marker is an **annotation simple name**.

### Default behaviour — automatic framework detection

When `testMarkers` is empty (the default), the plugin inspects each file's
import declarations and picks the right annotation set automatically:

| Imports present | Annotation set used |
|---|---|
| `org.junit.jupiter.*` | `Test`, `ParameterizedTest`, `RepeatedTest`, `TestFactory`, `TestTemplate` |
| `org.junit.*` or `junit.framework.*` | All JUnit 5 annotations plus `Theory` |
| `org.testng.*` | `Test` |
| None of the above | JUnit 5 set (fallback) |

Detection is per-file and accumulative: a file that imports both JUnit 4 and
JUnit 5 (common during migrations) receives the union of both sets.

### Overriding the detected annotation set

Supply annotation simple names with `-test-marker` or `testMarkers`. The first
value replaces the entire default set; subsequent values append to it.

=== "CLI"

    ```bash
    # JUnit 5 only — disables auto-detection
    ./methodatlas -test-marker Test -test-marker ParameterizedTest src/test/java

    # Custom in-house annotation alongside the standard one
    ./methodatlas -test-marker Test -test-marker ScenarioTest src/test/java
    ```

=== "YAML"

    ```yaml
    testMarkers:
      - Test
      - ParameterizedTest
      - ScenarioTest
    ```

### Common marker values by framework

| Framework | Marker values to use |
|---|---|
| JUnit 5 (Jupiter) | `Test`, `ParameterizedTest`, `RepeatedTest`, `TestFactory`, `TestTemplate` |
| JUnit 4 | `Test`, `Theory` |
| TestNG | `Test` |
| Custom | The simple name of your annotation (without `@`) |
| Mixed JUnit 4 + 5 | Leave `testMarkers` empty — auto-detection handles this |

### The `properties` map (JVM)

The JVM plugin does not use the `properties` map. Any keys present are silently
ignored.

## C# / .NET

**Plugin class:** `org.egothor.methodatlas.discovery.dotnet.DotNetTestDiscovery`

In C#, a test marker is an **attribute simple name** (without the surrounding
`[` `]` brackets). Framework detection is automatic from `using` directives —
leave `testMarkers` empty unless you need to override it.

| Framework | Detected by | Default markers |
|---|---|---|
| xUnit | `using Xunit;` | `Fact`, `Theory` |
| NUnit | `using NUnit.Framework;` | `Test`, `TestCase`, `TestCaseSource` |
| MSTest | `using Microsoft.VisualStudio.TestTools.UnitTesting;` | `TestMethod`, `DataTestMethod` |

### Tag and display-name write-back

The `.NET` plugin also ships a `SourcePatcher`
(`DotNetSourcePatcher`) that writes annotation changes back into `.cs` files:

| Framework | Tag written as | Display name |
|---|---|---|
| NUnit | `[Category("value")]` | not supported |
| xUnit | `[Trait("Tag", "value")]` | `[Fact(DisplayName = "text")]` |
| MSTest | `[TestCategory("value")]` | not supported |

### Configuration example

=== "CLI"

    ```bash
    # Auto-detection — no markers needed
    ./methodatlas -file-suffix dotnet:Test.cs src/

    # Override to xUnit only
    ./methodatlas -file-suffix dotnet:Test.cs \
      -test-marker Fact -test-marker Theory \
      src/
    ```

=== "YAML"

    ```yaml
    fileSuffixes:
      - dotnet:Test.cs
    testMarkers:
      - Fact
      - Theory
    ```

### The `properties` map (.NET)

The .NET plugin does not currently use the `properties` map. Any keys present
are silently ignored.

### Parser scope

The C# parser is built on a **structural ANTLR4 grammar** (`CSharpTest.g4`) that
focuses on namespaces, type declarations, method declarations, and attribute
sections. Method bodies are treated as opaque balanced-brace content. This covers
the overwhelming majority of real-world test files, but the grammar is not a
full implementation of the C# language specification and may not handle every
exotic syntax construct.

When the grammar cannot parse a construct it logs a `WARNING` that includes the
**file path, line number, character position, and a description of the problem**.
ANTLR4 error recovery then continues, so as many test methods as possible are
still discovered from the remainder of the file.

If you encounter a parse warning on a valid `.cs` file, please report it and
include the relevant code fragment. Grammar improvements are localised and
typically quick to deliver.

## TypeScript and JavaScript

**Plugin class:** `org.egothor.methodatlas.discovery.typescript.TypeScriptTestDiscovery`
**Module:** `methodatlas-discovery-typescript`
**Requires:** Node.js 18 or later on the `PATH`

TypeScript testing frameworks (Jest, Vitest, Mocha) do **not** use annotations
or decorators to mark tests. Tests are identified by **function call names**
(`test(…)`, `it(…)`). The `testMarkers` field is not applicable here — leave it
empty and use `properties` instead.

### Prerequisites

Node.js 18 or later must be installed and accessible as `node` on the system
`PATH`. If Node.js is absent or below version 18, the plugin disables itself
gracefully and logs a warning; all other plugins continue to function normally.

### Default file suffixes

The plugin matches files ending in `.test.ts`, `.spec.ts`, `.test.tsx`,
`.spec.tsx`, `.test.js`, or `.spec.js` by default. These defaults are
plugin-scoped — they are not delivered to the Java or .NET plugins.

### Test-method detection

`describe`, `context`, and `suite` calls are recognised as scope containers.
The container name is prepended to the test name in the output:

| Source | Method recorded as |
|---|---|
| `it('should auth')` at top level | `should auth` |
| `describe('AuthService', () => { it('should auth', ...) })` | `AuthService > should auth` |
| Nested describes | `Outer > Inner > test name` |

The `test.each`, `it.each`, `test.skip`, `test.only` variants are also
recognised — the base function name is extracted from the member expression.

### Configuration properties

| Property key | Meaning | Default values |
|---|---|---|
| `functionNames` | Function call names that identify a test | `test`, `it` |
| `typescript.poolSize` | Number of Node.js worker processes | `min(4, CPU count)` |
| `typescript.workerTimeoutSec` | Per-file worker timeout in seconds | `30` |
| `typescript.maxConsecutiveRestarts` | Circuit-breaker restart limit | `5` |
| `typescript.restartWindowSec` | Circuit-breaker sliding window in seconds | `60` |

=== "CLI"

    ```bash
    # Auto-detected defaults — works for Jest, Vitest, Mocha out of the box
    ./methodatlas \
      -file-suffix typescript:.test.ts \
      -file-suffix typescript:.spec.ts \
      src/

    # Custom function names (Jasmine uses 'it' only; custom frameworks vary)
    ./methodatlas \
      -file-suffix typescript:.spec.ts \
      -property functionNames=it \
      -property functionNames=specify \
      src/
    ```

=== "YAML"

    ```yaml
    fileSuffixes:
      - typescript:.test.ts
      - typescript:.spec.ts
    # testMarkers is intentionally absent — not applicable for TypeScript
    properties:
      functionNames:
        - test
        - it
      typescript.workerTimeoutSec:
        - "60"   # increase for very large files
    ```

### Security model

The TypeScript plugin runs a pre-built, integrity-verified Node.js process for
each scan. The security guarantees are:

**Bundle integrity** — the SHA-256 of the scanner bundle is computed at Gradle
build time and embedded in the JAR manifest as `TS-Scanner-Bundle-SHA256`. At
startup the plugin re-computes the hash and refuses to run if it does not match.
This detects JAR tampering and corruption before any code executes.

**No runtime npm** — the bundle (`ts-scanner.bundle.js`) is a single
self-contained file produced by esbuild at build time. No package manager runs
at runtime; the only Node.js module loaded is the bundle itself.

**File-system sandboxing** — when Node.js 20 or later is detected, workers are
started with `--experimental-permission --allow-fs-read=<scan-root>` so the
worker process can only read files under the directories being scanned.

**Audit logging** — every worker start and stop is logged at `INFO` level with
the bundle version, SHA-256 prefix, Node.js version, and OS process ID. Every
worker kill is logged with the reason. This gives audit teams a full provenance
trail for any MethodAtlas run.

**Circuit breaker** — if a worker restarts more than
`typescript.maxConsecutiveRestarts` times within `typescript.restartWindowSec`
seconds, the plugin is disabled for the remainder of the run and a `WARNING` is
emitted. This prevents runaway restart loops from consuming system resources.

### The `properties` map (TypeScript)

| Key | Type | Default | Description |
|---|---|---|---|
| `functionNames` | `List<String>` | `test`, `it` | Function call names that identify a test method. Repeated `-property functionNames=…` values accumulate into a list. |
| `typescript.poolSize` | `int` | `min(4, CPUs)` | Worker-pool size. Increase for projects with very many test files. |
| `typescript.workerTimeoutSec` | `int` | `30` | Per-file response timeout. Increase for large files or slow machines. |
| `typescript.maxConsecutiveRestarts` | `int` | `5` | Circuit-breaker restart limit. |
| `typescript.restartWindowSec` | `int` | `60` | Circuit-breaker sliding window width. |

## Quick reference

| Language / framework | `fileSuffixes` | `testMarkers` | `properties` |
|---|---|---|---|
| Java — JUnit 5 | `Test.java` | *(leave empty — auto-detected)* | — |
| Java — TestNG | `Test.java` | *(leave empty — auto-detected)* | — |
| Java — custom annotation | `Test.java` | e.g. `ScenarioTest` | — |
| Kotlin — JUnit 5 | `Test.kt` | *(leave empty — auto-detected)* | — |
| C# — xUnit | `dotnet:Test.cs` | `Fact`, `Theory` | — |
| C# — NUnit | `dotnet:Test.cs` | `Test`, `TestCase` | — |
| TypeScript — Jest | `typescript:.test.ts`, `typescript:.spec.ts` | *(leave empty)* | `functionNames=test`, `functionNames=it` |
| TypeScript — Vitest | `typescript:.test.ts`, `typescript:.spec.ts` | *(leave empty)* | `functionNames=test`, `functionNames=it` |
| TypeScript — Mocha | `typescript:.test.ts`, `typescript:.spec.ts` | *(leave empty)* | `functionNames=it` |
| JavaScript — Jest | `typescript:.test.js`, `typescript:.spec.js` | *(leave empty)* | `functionNames=test`, `functionNames=it` |

## See also

- [CLI Options — `-test-marker`](cli-reference.md#-test-marker-name) — full flag
  documentation including auto-detection details
- [CLI Options — `-property`](cli-reference.md#-property-keyvalue) — property
  flag reference
- [Static Inventory](usage-modes/static-inventory.md) — scanning workflow
- [CLI Examples](cli-examples.md) — practical command-line examples
