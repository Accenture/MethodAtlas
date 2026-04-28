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
| **File suffixes** | `-file-suffix` | `fileSuffixes` | Selects which files the plugin should read. Universal — every language has files with extensions. |
| **Test markers** | `-test-marker` | `testMarkers` | Names the identifiers that mark a method as a test. The *meaning* of "marker" is language-specific (see below). Empty = use plugin defaults. |
| **Properties** | `-property key=value` | `properties` | Open-ended key/value pairs for plugin-specific settings that don't fit either field above. Plugins ignore keys they don't recognise. |

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

## C# / .NET (planned)

> **Status:** plugin not yet released. This section documents the intended
> configuration once the plugin ships.

In C#, a test marker is an **attribute simple name** (without the surrounding
`[` `]` brackets).

| Framework | Marker values to use |
|---|---|
| xUnit | `Fact`, `Theory` |
| NUnit | `Test`, `TestCase`, `TestCaseSource` |
| MSTest | `TestMethod`, `DataTestMethod` |

=== "YAML (anticipated)"

    ```yaml
    fileSuffixes:
      - Test.cs
    testMarkers:
      - Fact
      - Theory
    ```

The `properties` map will accommodate framework-specific extras such as NUnit
trait filters once the plugin is available.

## TypeScript and JavaScript (planned)

> **Status:** plugin not yet released. This section documents the intended
> configuration once the plugin ships.

TypeScript testing frameworks (Jest, Vitest, Mocha) do **not** use annotations
or decorators to mark tests. Tests are identified by **function call names**
(`test(…)`, `it(…)`). The `testMarkers` field is not applicable here — leave it
empty and use `properties` instead.

| Property key | Meaning | Default values |
|---|---|---|
| `functionNames` | Function call names that identify a test | `test`, `it` |

=== "CLI"

    ```bash
    ./methodatlas \
      -file-suffix .test.ts \
      -file-suffix .spec.ts \
      -property functionNames=test \
      -property functionNames=it \
      src/
    ```

=== "YAML"

    ```yaml
    fileSuffixes:
      - .test.ts
      - .spec.ts
    # testMarkers is intentionally absent — not applicable
    properties:
      functionNames:
        - test
        - it
    ```

## Quick reference

| Language / framework | `fileSuffixes` | `testMarkers` | `properties` |
|---|---|---|---|
| Java — JUnit 5 | `Test.java` | *(leave empty — auto-detected)* | — |
| Java — TestNG | `Test.java` | *(leave empty — auto-detected)* | — |
| Java — custom annotation | `Test.java` | e.g. `ScenarioTest` | — |
| Kotlin — JUnit 5 | `Test.kt` | *(leave empty — auto-detected)* | — |
| C# — xUnit *(planned)* | `Test.cs` | `Fact`, `Theory` | — |
| C# — NUnit *(planned)* | `Test.cs` | `Test`, `TestCase` | — |
| TypeScript — Jest *(planned)* | `.test.ts`, `.spec.ts` | *(leave empty)* | `functionNames=test`, `functionNames=it` |
| TypeScript — Mocha *(planned)* | `.test.ts`, `.spec.ts` | *(leave empty)* | `functionNames=it` |

## See also

- [CLI Options — `-test-marker`](cli-reference.md#-test-marker-name) — full flag
  documentation including auto-detection details
- [CLI Options — `-property`](cli-reference.md#-property-keyvalue) — property
  flag reference
- [Static Inventory](usage-modes/static-inventory.md) — scanning workflow
- [CLI Examples](cli-examples.md) — practical command-line examples
