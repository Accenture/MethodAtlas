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
(`methodatlas-discovery-dotnet`), **`typescript`**
(`methodatlas-discovery-typescript`), **`go`**
(`methodatlas-discovery-go`), **`python`**
(`methodatlas-discovery-python`), and **`powershell`**
(`methodatlas-discovery-powershell`). Third-party plugins declare their own ID
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

### Tag reading

When MethodAtlas scans a `.cs` file it reads existing category / trait
attributes and records their values in the `tags` column:

| Framework | Attribute read | Argument used |
|---|---|---|
| NUnit | `[Category("value")]` | first (and only) argument |
| xUnit | `[Trait("Tag", "value")]` or `[Trait("Category", "value")]` | second argument (first must be `"Tag"` or `"Category"`, case-insensitive) |
| MSTest | `[TestCategory("value")]` | first (and only) argument |

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

## Go

**Plugin class:** `org.egothor.methodatlas.discovery.go.GoTestDiscovery`
**Module:** `methodatlas-discovery-go`

Go test functions are discovered by parsing each `_test.go` file with a
structural ANTLR4 grammar (`GoTest.g4`) and matching function declarations
against the standard `go test` convention.

### Test function detection

A function is treated as a test if its declaration matches:

```go
func TestXxx(t *testing.T)
```

where `Xxx` starts with an upper-case letter or underscore. Benchmark functions
(`BenchmarkXxx`), example functions (`ExampleXxx`), and fuzz targets
(`FuzzXxx`) are intentionally excluded and will not appear in the output.

### Default file suffix

`_test.go` (configurable via `-file-suffix go:_test.go`).

### Tags and display names

Go has no annotation-based tag or display-name system. The `tags` column is
always empty and `display_name` is always blank. The `testMarkers` field has
no effect on this plugin and should be left empty.

### Configuration example

=== "CLI"

    ```bash
    # Default — auto-detects _test.go files
    ./methodatlas src/

    # Explicit suffix targeting (useful in mixed-language monorepos)
    ./methodatlas -file-suffix go:_test.go src/
    ```

=== "YAML"

    ```yaml
    fileSuffixes:
      - go:_test.go
    ```

### The `properties` map (Go)

The Go plugin does not use the `properties` map. Any keys present are silently
ignored.

## Python

**Plugin class:** `org.egothor.methodatlas.discovery.python.PythonTestDiscovery`
**Module:** `methodatlas-discovery-python`
**Requires:** Python 3.8 or later on the `PATH`

The Python plugin discovers test functions and methods following the
[pytest](https://docs.pytest.org/) naming conventions.  Parsing is performed
by a pool of long-lived Python worker processes that run the bundled
`py-scanner.py` script using the standard-library `ast` module.  The AST-based
approach correctly handles all valid Python syntax — including decorator stacks,
type annotations, and async functions — and reports exact begin/end line numbers.

### Prerequisites

Python 3.8 or later must be installed and accessible as `python3` (or `python`)
on the system `PATH`.  Python 3.8 is the minimum because `ast.Node.end_lineno`
— used to compute per-function line ranges — was added in that release.  If
Python is absent or below version 3.8, the plugin disables itself gracefully and
logs a warning; all other plugins continue to function normally.

### File selection

Two file-naming conventions are supported by default:

| Convention | Example | Active when |
|---|---|---|
| `test_*.py` prefix | `test_auth.py` | Always — cannot be disabled via CLI |
| `*_test.py` suffix | `security_test.py` | Default when no `-file-suffix python:…` is set |

If `-file-suffix python:<suffix>` is supplied, the suffix check uses that value
instead of the default `_test.py`. The `test_` prefix check remains active
regardless.

### Test function and method detection

The plugin recognises:

- **Module-level functions** — any `def test_*()` or `async def test_*()`
  function at module scope.
- **Class methods** — `def test_*()` or `async def test_*()` methods inside a
  class whose name starts with `Test`, ends with `Test`, or ends with `Tests`
  (e.g. `TestAuth`, `AuthTest`, `AuthTests`).

The fully-qualified class name (FQCN) is the dot-separated module path for
module-level functions, or the module path suffixed with the class name for
methods (`auth.test_auth.TestAuth`).

### Tags from `@pytest.mark`

Decorator lines immediately before a `def test_*` are inspected. Any
`@pytest.mark.<name>` decorator contributes `name` to the `tags` column. All
other decorators are ignored.

```python
@pytest.mark.security
@pytest.mark.slow
def test_token_expiry():
    ...
```

emits `tags = security;slow`.

### `testMarkers` — not applicable

pytest identifies tests by function name, not by annotations. The `testMarkers`
field has no effect on this plugin and should be left empty.

### Configuration example

=== "CLI"

    ```bash
    # Default — auto-detects test_*.py and *_test.py files
    ./methodatlas src/

    # Explicit suffix targeting (useful in mixed-language monorepos)
    ./methodatlas -file-suffix python:_test.py src/
    ```

=== "YAML"

    ```yaml
    fileSuffixes:
      - python:_test.py
    ```

### Configuration properties (Python)

| Property key | Meaning | Default |
|---|---|---|
| `python.poolSize` | Number of Python worker processes | `min(2, CPU count)` |
| `python.workerTimeoutSec` | Per-file worker timeout in seconds | `30` |
| `python.maxConsecutiveRestarts` | Circuit-breaker restart limit | `5` |
| `python.restartWindowSec` | Circuit-breaker sliding window in seconds | `60` |

=== "CLI"

    ```bash
    # Default — auto-detects test_*.py and *_test.py
    ./methodatlas src/

    # Explicit suffix and longer timeout for large files
    ./methodatlas \
      -file-suffix python:_test.py \
      -property python.workerTimeoutSec=60 \
      src/
    ```

=== "YAML"

    ```yaml
    fileSuffixes:
      - python:_test.py
    properties:
      python.workerTimeoutSec:
        - "60"
    ```

### The `properties` map (Python)

| Key | Type | Default | Description |
|---|---|---|---|
| `python.poolSize` | `int` | `min(2, CPUs)` | Worker-pool size. |
| `python.workerTimeoutSec` | `int` | `30` | Per-file response timeout in seconds. |
| `python.maxConsecutiveRestarts` | `int` | `5` | Circuit-breaker restart limit. |
| `python.restartWindowSec` | `int` | `60` | Circuit-breaker sliding window in seconds. |

## PowerShell / Pester

**Plugin class:** `org.egothor.methodatlas.discovery.powershell.PowerShellTestDiscovery`
**Module:** `methodatlas-discovery-powershell`

The PowerShell plugin discovers Pester test cases from `It "…" { … }` blocks
in PowerShell test scripts. No PowerShell runtime is required — parsing is
performed entirely in Java using a structural ANTLR4 grammar
(`PowerShellTest.g4`) that covers `Describe`, `Context`, and `It` blocks while
treating all other PowerShell content as opaque tokens.

### File selection

Default suffixes (both are active unless overridden):

| Suffix | Example |
|---|---|
| `.Tests.ps1` | `Auth.Tests.ps1` |
| `.Test.ps1` | `Auth.Test.ps1` |

Override with `-file-suffix powershell:<suffix>` to use a different convention
(e.g. `.ps1` alone for projects that do not follow the Pester suffix convention).

### Test case detection

Every `It "description" { … }` block is emitted as one discovered method,
regardless of nesting level. `It` is matched case-insensitively to support
style variants.

`Describe "…"` and `Context "…"` blocks are not emitted as separate records —
they are used only to derive the FQCN (directory + file stem joined with `.`).

### Tags from `-Tag`

The `-Tag` parameter on a Pester `Describe`, `Context`, or `It` block is read
and its values are collected into the `tags` column:

```powershell
Describe "Auth Module" -Tag "security", "auth" {
    It "rejects expired tokens" -Tag "regression" {
        ...
    }
}
```

Tags are collected at the `It` line only. Tags on enclosing `Describe`/`Context`
blocks are **not** propagated to child `It` entries — Pester itself handles tag
inheritance at runtime.

### `testMarkers` — not applicable

Pester uses the `It` keyword, not annotations or attributes. The `testMarkers`
field has no effect on this plugin and should be left empty.

### Configuration example

=== "CLI"

    ```bash
    # Default — auto-detects *.Tests.ps1 and *.Test.ps1 files
    ./methodatlas src/

    # Explicit suffix targeting
    ./methodatlas -file-suffix powershell:.Tests.ps1 \
                  -file-suffix powershell:.Test.ps1 \
                  src/
    ```

=== "YAML"

    ```yaml
    fileSuffixes:
      - powershell:.Tests.ps1
      - powershell:.Test.ps1
    ```

### The `properties` map (PowerShell)

The PowerShell plugin does not use the `properties` map. Any keys present are
silently ignored.

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
| Go — testing package | `go:_test.go` | *(not applicable)* | — |
| Python — pytest functions | `python:_test.py` | *(leave empty — name-based)* | — |
| Python — pytest classes | `python:_test.py` | *(leave empty — name-based)* | — |
| PowerShell — Pester | `powershell:.Tests.ps1`, `powershell:.Test.ps1` | *(not applicable)* | — |

## See also

- [CLI Options — `-test-marker`](cli-reference.md#-test-marker-name) — full flag
  documentation including auto-detection details
- [CLI Options — `-property`](cli-reference.md#-property-keyvalue) — property
  flag reference
- [Static Inventory](usage-modes/static-inventory.md) — scanning workflow
- [CLI Examples](cli-examples.md) — practical command-line examples
