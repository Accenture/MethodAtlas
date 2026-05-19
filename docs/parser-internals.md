# Parser Internals

This page is the technical reference for MethodAtlas's six built-in discovery
plugins.  Each section describes the parser technology, what the plugin **can**
and **cannot** detect, the preconditions it requires, how the
fully-qualified class name (FQCN) is computed, and how tags are extracted.

Use this page to answer questions such as:

- *"Will my Go subtests be discovered?"*
- *"What Python syntax is too new for the scanner?"*
- *"Why does this C# file generate a parse warning?"*
- *"How is the FQCN derived for a file three directories deep?"*

## How plugins are selected

All plugins receive every file that matches their configured (or default) file
suffix.  Plugins that require an external runtime — Node.js for TypeScript,
Python for Python — perform a **lazy** availability check: the check runs only
when at least one matching file is found.  Projects with no TypeScript or Python
test files never execute `node --version` or `python3 --version`.

When a required runtime is absent, the plugin logs a `WARNING`, returns an
empty result, and sets `hadErrors() = true`.  All other plugins continue to
function normally.

## Java / Kotlin

**Plugin ID:** `java`  
**Module:** `methodatlas-discovery-jvm`  
**Parser:** [JavaParser](https://javaparser.org/) 3.28, configured to Java 21 language level  
**Runtime requirement:** none — parsing is performed entirely in the JVM

### Parser technology

JavaParser builds a full abstract syntax tree (AST) from the source file.
MethodAtlas traverses the AST with a recursive visitor that walks into every
nested and inner class.  Only method nodes annotated with one of the active
test markers are emitted.

### What is detected

| Element | Detected | Notes |
|---------|----------|-------|
| JUnit 5 `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate` | Yes | Auto-detected from `org.junit.jupiter.*` imports |
| JUnit 4 `@Test`, `@Theory` | Yes | Auto-detected from `org.junit.*` or `junit.framework.*` imports |
| TestNG `@Test` | Yes | Auto-detected from `org.testng.*` imports |
| Methods in nested / inner classes | Yes | Visitor recurses into all class-level members |
| `@Tag("value")` on the method | Yes | All values extracted as tags |
| `@DisplayName("text")` on the method | Yes | Stored in the `displayName` field |
| Custom annotation names | Yes | Via `-test-marker` or `testMarkers:` config |
| Mixed JUnit 4 + JUnit 5 in one file | Yes | Union of both marker sets is used |

### Automatic framework detection

Framework detection is per-file.  Before visiting any method, the plugin
inspects the file's import declarations:

| Imports found | Annotation set activated |
|---|---|
| `org.junit.jupiter.*` | JUnit 5 set |
| `org.junit.*` or `junit.framework.*` | JUnit 4+5 union |
| `org.testng.*` | `Test` (TestNG) |
| None of the above | JUnit 5 set (fallback) |

If `testMarkers` is non-empty, automatic detection is skipped entirely and
only the supplied names are used for every file.

### Known limits

| Limit | Detail |
|-------|--------|
| **Java 21 maximum** | JavaParser is configured to `JAVA_21`.  Source files using features introduced after Java 21 (e.g. future preview syntax) will fail to parse with a `WARNING` log; already-discovered methods in other files are unaffected. |
| **No Kotlin AST** | Kotlin files are matched by suffix but parsed as plain text; the JavaParser AST is not Kotlin-aware.  Kotlin test discovery relies on naming conventions and may miss annotation forms not present in Java. |
| **No programmatic test generation** | Tests registered via `@TestFactory` return values are not expanded; only the factory method itself is emitted. |
| **Class-level annotations ignored** | `@Test` on a class declaration has no effect; only method declarations are checked. |
| **Interface methods** | Default methods in interfaces annotated with `@Test` are detected but rarely occur in practice. |

### Preconditions

- File must be valid Java or Kotlin source that JavaParser can parse at Java 21
  language level.
- File encoding must be readable by the JVM's default charset (UTF-8 recommended).

### FQCN computation

The FQCN is the fully-qualified **class** name derived directly from the AST:

```
package.name.OuterClass.InnerClass
```

- Package name is taken from the `package` declaration in the file.
- For nested classes: each enclosing class name is appended with `.`.
- When the file has no package declaration: the simple class name is used.

The **file stem** (used in the manual AI workflow) is computed from the file
path relative to the scan root, with path separators replaced by `.` and the
`.java` extension stripped.

### Tag extraction

Tags are collected from `@Tag("value")` annotations on the method declaration.
The annotation simple name must be `Tag` (case-sensitive); the first positional
string argument is taken as the tag value.  Multiple `@Tag` annotations and
`@Tag({"a","b"})` array form are both supported.  Class-level `@Tag`
annotations are **not** propagated to individual methods.

## C# / .NET

**Plugin ID:** `dotnet`  
**Module:** `methodatlas-discovery-dotnet`  
**Parser:** ANTLR4 structural grammar (`CSharpTest.g4`) — custom, focused  
**Runtime requirement:** none — parsing is performed entirely in the JVM

### Parser technology

`CSharpTest.g4` is a hand-authored **structural** grammar.  It parses the
skeleton of a C# file — namespaces, type declarations, method declarations,
and attribute sections — while treating method bodies as opaque balanced-brace
content.  This makes the grammar fast and highly tolerant of language features
it does not model, at the cost of not understanding anything inside method
bodies.

ANTLR4's built-in error recovery is active.  When a parse error occurs,
MethodAtlas logs a `WARNING` with the file path, line number, and character
position, then continues parsing the remainder of the file.

### What is detected

| Element | Detected | Notes |
|---------|----------|-------|
| NUnit `[Test]`, `[TestCase]` | Yes | Auto-detected from `using NUnit.*` |
| xUnit `[Fact]`, `[Theory]` | Yes | Auto-detected from `using Xunit.*` |
| MSTest `[TestMethod]` | Yes | Auto-detected from `using Microsoft.VisualStudio.TestTools.*` |
| Methods in nested types | Yes | Visitor tracks the full namespace + class stack |
| `[Category("value")]` (NUnit) | Yes | First positional argument taken as tag |
| `[Trait("Tag","value")]` (xUnit) | Yes | Second positional arg taken; first must be "Tag" or "Category" |
| `[TestCategory("value")]` (MSTest) | Yes | First positional argument taken as tag |
| `[Fact(DisplayName = "text")]` (xUnit) | Yes | Named `DisplayName` argument stored |
| Custom test marker names | Yes | Via `-test-marker` or `testMarkers:` config |
| Explicit interface implementations | Yes | `IFoo.MethodName` — `IFoo.` prefix stripped from the method name |
| Generic method declarations | Yes | Generic parameters are ignored; method name extracted cleanly |

### Automatic framework detection

Framework detection is per-file based on `using` directives at the top of the
file.  Using directives that import `Xunit.*`, `NUnit.*`, or
`Microsoft.VisualStudio.TestTools.*` activate the corresponding marker set.
If none of those are present, the framework is `UNKNOWN` and the union of all
known test markers is used.

When `testMarkers` is non-empty, automatic detection is skipped; only the
supplied names are used.

### Known limits

| Limit | Detail |
|-------|--------|
| **Structural grammar only** | The grammar does not cover the full C# specification.  Exotic constructs — top-level statements, primary constructors in unusual positions, preprocessor directives that restructure the token stream — may cause parse warnings.  Error recovery keeps scanning; valid test methods before and after the problem point are still discovered. |
| **No raw string literal in attributes** | C# 11+ raw string literals (`"""..."""`) in attribute arguments are not handled by the unquote logic.  Attribute values containing raw strings will be emitted with raw delimiters intact rather than the actual string value. |
| **Attribute arguments at parse time** | The parser reads attribute positional arguments from the token stream, not from a fully-resolved expression tree.  Complex expressions (e.g. `nameof(...)`, concatenation) are returned as literal token text, not their evaluated values. |
| **No compile-time constants** | Tags and display names must be string literals; `const` references are not resolved. |

### Preconditions

- File extension must be `.cs` (or a configured suffix).
- File encoding should be UTF-8.  The ANTLR4 `CharStreams.fromPath()` call uses
  the JVM default charset.

### FQCN computation

The FQCN is the fully-qualified **class** name built from the nesting context:

```
Namespace.SubNamespace.OuterClass.InnerClass
```

- The visitor maintains a namespace stack and a class stack.
- Every `namespace` declaration pushes onto the namespace stack; every `class`
  or `struct` declaration pushes onto the class stack.
- The final FQCN is `namespace_stack.join(".") + "." + class_stack.join(".")`.
- When a file has no namespace: only the class stack is used.

The **file stem** is the file path relative to the scan root with path
separators replaced by `.` and the `.cs` extension stripped.

### Tag extraction

Tags are extracted from method-level attributes:

- **NUnit** — attribute named `Category`: first positional string argument.
- **xUnit** — attribute named `Trait`: the second positional string argument,
  but only when the first positional argument equals `"Tag"` or `"Category"`
  (case-insensitive).
- **MSTest** — attribute named `TestCategory`: first positional string argument.

Class-level `[Category]` / `[Trait]` / `[TestCategory]` annotations are **not**
propagated to individual methods.

Display names (xUnit only): `[Fact(DisplayName = "text")]` and
`[Theory(DisplayName = "text")]` — the named argument `DisplayName` is read.

## TypeScript / JavaScript

**Plugin ID:** `typescript`  
**Module:** `methodatlas-discovery-typescript`  
**Parser:** Pre-built `ts-scanner.bundle.js` running in a Node.js worker pool  
**Runtime requirement:** Node.js **18 or later** on the `PATH`

### Parser technology

MethodAtlas embeds a pre-built, esbuild-bundled Node.js script
(`ts-scanner.bundle.js`) inside the JAR.  At startup the JAR manifest entry
`TS-Scanner-Bundle-SHA256` is compared against a freshly computed SHA-256 of
the embedded file; a mismatch aborts startup to detect JAR corruption or
tampering.

The scanner bundle is extracted to a temporary directory and launched as a
long-lived subprocess (worker).  Multiple workers are maintained in a pool.
Each worker receives JSON-line requests (`{ requestId, filePath, functionNames,
scanRoot }`) and returns JSON-line responses with discovered method descriptors.

The bundle uses the TypeScript compiler API for full AST-based parsing, which
means all valid TypeScript and JavaScript syntax is handled correctly.

### What is detected

| Element | Detected | Notes |
|---------|----------|-------|
| `test("name", ...)` calls | Yes | Function name configurable via `functionNames` property |
| `it("name", ...)` calls | Yes | Default; configurable |
| `describe("name", ...)` wrappers | Yes | Name prepended to method name with ` > ` separator |
| `context(...)` and `suite(...)` wrappers | Yes | Same as `describe` |
| Method chains: `test.each`, `test.skip`, `test.only`, `it.each`, etc. | Yes | Base name extracted from the member expression |
| Arrow functions and regular function expressions | Yes | Both forms detected |
| `describe.each(...)` | Yes | |
| Async test functions | Yes | `async (...)` callbacks are transparent |

### What is not detected

| Element | Not detected | Reason |
|---------|-------------|--------|
| Tests registered via `registerSuite`, `runner.add`, or custom DSLs | No | Only the configured `functionNames` are recognised |
| Dynamic test names (template literals, variables) | Partial | The literal text is extracted; evaluated result not available |
| Tags / annotations | No | TypeScript/JavaScript test frameworks do not use annotation-based tags |
| Display name separate from test name | No | The test-call string argument is used as both |

### Filesystem sandboxing

| Node.js version | Permission flag |
|-----------------|-----------------|
| Below 20 | No sandboxing |
| 20 – 21 | `--experimental-permission --allow-fs-read=<scan-root>` |
| 22 and above | `--permission --allow-fs-read=<scan-root>` |

Sandboxing prevents the worker process from reading files outside the directory
being scanned.

### Known limits

| Limit | Detail |
|-------|--------|
| **Node.js 18 minimum** | Versions below 18 are rejected at startup.  Node.js LTS 18 (Hydrogen) reached end-of-life in April 2025; Node.js 20 (Iron) or later is recommended. |
| **Bundle is pre-built** | The scanner is an esbuild output baked into the JAR at build time.  Custom TypeScript transformers or framework plugins that change the AST structure before compilation are not seen. |
| **`describe.skip`, `describe.only` wrappers** | Tests inside skipped or focused suites are still emitted; MethodAtlas does not model runtime-skip semantics. |
| **Template-literal test names** | If a test name is a template literal (e.g. `` `test ${i}` ``), the raw template text is captured, not the evaluated string. |

### Preconditions

- `node` must be on the `PATH` and report version ≥ 18 when `node --version` is run.
- Files must be valid TypeScript or JavaScript syntax.
- The JAR must not be modified after build (bundle SHA-256 verification).

### FQCN and file stem computation

The FQCN is the file path relative to the scan root with path separators
replaced by `.` and the file extension stripped:

```
auth/__tests__/authService.test.ts  →  auth.__tests__.authService.test
```

All test methods in the same file share the same FQCN.  The file stem is
identical to the FQCN for TypeScript (there is no class hierarchy below the
file level).

### Tag extraction

TypeScript/JavaScript test frameworks do not use annotation-based tags.  The
`tags` field is always empty.

### Circuit breaker

If a worker process restarts more than `typescript.maxConsecutiveRestarts` times
(default 5) within `typescript.restartWindowSec` seconds (default 60), the
circuit opens, the plugin is disabled for the remainder of the scan, a `WARNING`
is logged, and `hadErrors()` returns `true`.

## Go

**Plugin ID:** `go`  
**Module:** `methodatlas-discovery-go`  
**Parser:** ANTLR4 structural grammar (`GoTest.g4`) — custom, focused  
**Runtime requirement:** none — parsing is performed entirely in the JVM

### Parser technology

`GoTest.g4` is a hand-authored **structural** grammar.  It parses top-level
declarations — package declarations, import declarations, function declarations,
and method declarations — while treating function bodies as opaque balanced-brace
content.  It is not a full implementation of the Go specification.

ANTLR4 error recovery is active.  Parse errors are logged as `WARNING` with
file path, line, and column; scanning continues for the remainder of the file.

### What is detected

A function is emitted if and only if its declaration matches all three
conditions:

1. Name starts with `Test` followed by an upper-case letter or `_` (or just `Test` with no suffix).
2. First parameter type is `*testing.T` (the pointer is required; `testing.T`
   without `*` does not match).
3. The function is a **top-level function** (not a method with a receiver).

```go
// Discovered
func TestLoginValid(t *testing.T) { ... }
func Test_helperCase(t *testing.T) { ... }
func Test(t *testing.T) { ... }

// Not discovered
func BenchmarkLogin(b *testing.B) { ... }        // starts with Benchmark
func ExampleLogin() { ... }                       // starts with Example
func FuzzLogin(f *testing.F) { ... }              // starts with Fuzz
func TestHelper(t *testing.T, extra int) { ... }  // extra parameter — note: still detected; parameter check is on type only
func (s *Suite) TestMethod(t *testing.T) { ... }  // method with receiver — excluded
func testHelper(t *testing.T) { ... }             // lowercase 't' in 'test' — excluded
```

### Known limits

| Limit | Detail |
|-------|--------|
| **Methods with receivers excluded** | Only top-level functions are examined.  Test methods on a struct (`func (s *MySuite) TestXxx(...)`) are not recognised. |
| **Table-driven tests not expanded** | `t.Run("case", func(t *testing.T) {...})` sub-tests are not emitted as separate records; only the outer `TestXxx` function is discovered. |
| **`testing.T` pointer required** | The parameter must be declared as `*testing.T`.  Type aliases (`type T = testing.T`) are not resolved. |
| **Package name fallback** | When the file sits directly in the scan root with no parent directory, the FQCN falls back to the package name extracted from the `package` declaration. |
| **Structural grammar** | Exotic Go syntax constructs (type parameters on methods in older toolchain snapshots, unusual blank identifiers in parameter lists) may produce parse warnings with error recovery. |

### Preconditions

- File suffix must be `_test.go` (or a configured override).
- File encoding must be UTF-8 (ANTLR4 `CharStreams.fromPath` default).

### FQCN computation

The FQCN is derived from the **parent directory** of the source file, relative
to the scan root:

```
scan root: /project/src
file:      /project/src/internal/auth/auth_test.go
FQCN:      internal.auth
```

When the file is directly in the scan root (no parent directory), the FQCN
falls back to the **package name** extracted from the `package` declaration in
the file.

The **file stem** is the relative path of the file from the root (including the
filename), with path separators replaced by `.` and the `_test.go` suffix
stripped:

```
scan root: /project/src
file:      /project/src/internal/auth/auth_test.go
Stem:      internal.auth.auth
```

### Tag extraction

Go has no annotation-based tag system.  The `tags` field is always empty.

### Error handling

- Per-file parse errors: logged at `WARNING` with file, line, column, and error
  description; `hadErrors()` set to `true`.
- File read errors: logged at `WARNING`; file is skipped.

## Python

**Plugin ID:** `python`  
**Module:** `methodatlas-discovery-python`  
**Parser:** Python `ast` standard-library module via a worker pool  
**Runtime requirement:** Python **3.8 or later** on the `PATH`

### Parser technology

MethodAtlas embeds a Python script (`py-scanner.py`) inside the JAR as a
classpath resource.  At startup the JAR manifest entry `Py-Scanner-SHA256`
is compared against a freshly computed SHA-256 of the embedded file; a
mismatch aborts startup to detect JAR corruption or tampering.  The verified
script is then extracted to a temporary file.  A pool of long-lived Python
worker processes runs the script; each worker receives JSON-line requests
(`{ requestId, filePath }`) and returns JSON-line responses with discovered
method descriptors.

The script calls `ast.parse()` on each file.  Because the `ast` module is part
of Python's standard library and uses the same parser as the CPython interpreter,
**all valid Python 3.8+ syntax is handled correctly** — including decorated
functions, async functions, type annotations, positional-only parameters, and
all other language features up to the version of CPython that is installed.

### Python executable search

At initialisation the plugin tries `python3 --version` first.  If that is not
found or reports a version below 3.8, it falls back to `python --version`.  If
neither executable exists or meets the version requirement, the plugin is
disabled gracefully.

### What is detected

| Element | Detected | Notes |
|---------|----------|-------|
| Module-level `def test_*(...)` | Yes | Sync and `async def` both matched |
| Module-level `async def test_*(...)` | Yes | |
| Methods `def test_*(...)` inside `Test*` / `*Test` / `*Tests` classes | Yes | Class name checked with `startswith("Test")`, `endswith("Test")`, `endswith("Tests")` |
| Async methods inside test classes | Yes | |
| `@pytest.mark.<name>` decorators | Yes | Decorator name extracted as tag |
| Multiple decorators stacked | Yes | Each matching decorator contributes one tag |
| Exact begin/end line numbers | Yes | `ast.Node.lineno` and `ast.Node.end_lineno` |
| LOC (lines of code) | Yes | `end_lineno - lineno + 1` |

### File selection dual-mode

The plugin applies two independent rules:

| Rule | Active when | Can be disabled |
|------|-------------|-----------------|
| File name starts with `test_` and ends with `.py` | Always | No — always active regardless of configured suffixes |
| File name ends with a configured suffix | When `fileSuffixesFor("python")` is non-empty; defaults to `_test.py` | Yes — replaced by any configured suffix |

**Example:** With `-file-suffix python:_spec.py` set, a file named `test_auth.py`
is still scanned (prefix rule), but a file named `auth_test.py` is **not**
(suffix rule was replaced by `_spec.py`).

### Known limits

| Limit | Detail |
|-------|--------|
| **Python 3.8 minimum** | `ast.Node.end_lineno` was added in Python 3.8 and is required for line-range computation.  Earlier Python versions are rejected at startup. |
| **No nested-function discovery** | Test functions defined inside other functions (closures) are not emitted; only module-level functions and direct methods of a class are recognised. |
| **No parametrized test expansion** | `@pytest.mark.parametrize` decorated functions are emitted once as a single record; the individual parameter combinations are not expanded. |
| **Class detection by name only** | A class is considered a test class solely by its name following the `Test*` / `*Test` / `*Tests` convention.  Inheritance from `unittest.TestCase` does not change behaviour. |
| **Only `@pytest.mark.<name>` tags** | Decorators that do not match the pattern `@pytest.mark.<name>` are silently ignored.  Custom markers from plugins that use a different API shape are not extracted. |
| **File must be valid Python** | `ast.parse()` raises `SyntaxError` for invalid syntax; the worker reports an error, MethodAtlas logs a `WARNING`, and the file is skipped. |

### Preconditions

- `python3` or `python` must be on the `PATH` and report version ≥ 3.8.
- Files must be valid UTF-8 Python source.
- Files must be syntactically valid Python (verified by `ast.parse()`).
- The JAR must not be modified after build (script SHA-256 verification).

### FQCN computation

The FQCN is the **module path** — the file path relative to the scan root with
path separators replaced by `.` and the `.py` extension stripped — optionally
suffixed with the class name:

```
scan root: /project/tests
file:      /project/tests/auth/test_auth.py
class:     TestAuthService
method:    test_login_valid

Module path:  auth.test_auth
FQCN:         auth.test_auth.TestAuthService
```

For module-level functions (no enclosing class), the FQCN equals the module
path.

The **file stem** is identical to the module path.

### Tag extraction

Only `@pytest.mark.<name>` decorators are recognised.  The decorator must
follow exactly the pattern `attribute.attribute(object)` — i.e. a two-level
attribute access on the name `pytest`, then `mark`, then the tag name.

Example: `@pytest.mark.security` → tag `security`.

`@pytest.mark.parametrize(...)` is extracted as tag `parametrize` (the mark
name), not expanded into individual parameter tuples.

### Circuit breaker

If a worker process restarts more than `python.maxConsecutiveRestarts` times
(default 5) within `python.restartWindowSec` seconds (default 60), the circuit
opens, the plugin is disabled for the remainder of the scan, a `WARNING` is
logged, and `hadErrors()` returns `true`.

## PowerShell / Pester

**Plugin ID:** `powershell`  
**Module:** `methodatlas-discovery-powershell`  
**Parser:** ANTLR4 structural grammar (`PowerShellTest.g4`) — custom, focused  
**Runtime requirement:** none — parsing is performed entirely in the JVM

### Parser technology

`PowerShellTest.g4` is a hand-authored **structural** grammar.  It recognises
Pester DSL keywords (`Describe`, `Context`, `It`) and their string arguments
and `-Tag` parameters, while treating all other PowerShell content as opaque
tokens.  The grammar uses ANTLR4's `caseInsensitive = true` option, so all
keyword matches are case-insensitive.

ANTLR4 error recovery is active.  Parse errors are logged as `WARNING` with
file path, line, and column; scanning continues for the remainder of the file.

### What is detected

| Element | Detected | Notes |
|---------|----------|-------|
| `It "description" { ... }` blocks | Yes | Case-insensitive: `it`, `It`, `IT` all match |
| Single-quoted names `It 'description'` | Yes | |
| Nested `It` blocks inside script blocks | Yes | Recursive descent visits all nesting depths |
| `-Tag "value"` on the `It` line | Yes | Plain form |
| `-Tag "a", "b"` comma-separated | Yes | Multiple values collected |
| `-Tag @("a", "b")` array form | Yes | ANTLR4 grammar handles `@(` ... `)` |
| `Describe "..."` and `Context "..."` blocks | Structural | Used to determine parse structure; not emitted as separate records |

### What is not detected

| Element | Not detected | Reason |
|---------|-------------|--------|
| Tags on enclosing `Describe` / `Context` blocks | No | Only `-Tag` on the `It` line itself is read.  Pester resolves tag inheritance at runtime; MethodAtlas does not replicate that logic. |
| `It -Name "..."` (named-parameter form) | No | The grammar matches positional string argument only |
| `BeforeAll`, `AfterAll`, `BeforeEach`, `AfterEach` | No | Setup/teardown hooks are not test cases |
| Dynamically generated test names (variables, expressions) | No | Only string literal names are extracted |

### Known limits

| Limit | Detail |
|-------|--------|
| **Structural grammar** | The grammar does not model full PowerShell syntax.  Unusual constructs (e.g. `It` called via a variable alias, splat syntax on the `-Tag` parameter) may cause parse warnings with ANTLR4 error recovery engaging. |
| **No tag inheritance** | Tags declared on parent `Describe` or `Context` blocks are not propagated to child `It` blocks in the MethodAtlas output.  If your CI pipeline or test report depends on inherited tags, apply `-Tag` explicitly at the `It` level. |
| **Line range ends at closing brace** | `endLine` is the line of the `}` that closes the `It` script block.  If the closing brace is on the same line as `It "..."`, `endLine = beginLine` and `loc = 1`. |

### Preconditions

- File suffix must match `.Tests.ps1` or `.Test.ps1` (or a configured override).
- File encoding must be UTF-8 (ANTLR4 `CharStreams.fromPath` default).

### FQCN computation

The FQCN is the **directory path** of the source file relative to the scan
root, joined with `.`, followed by the **filename stem** (suffix stripped):

```
scan root: /project
file:      /project/modules/auth/Auth.Tests.ps1
Stem:      Auth          (strip .Tests.ps1)
FQCN:      modules.auth.Auth
```

Suffix stripping order: `.Tests.ps1` → `.Test.ps1` → `.ps1`.

When the file is directly in the scan root: the FQCN is the filename stem alone.

The **file stem** is the relative path from root (including the filename) with
separators replaced by `.` and the suffix stripped:

```
scan root: /project
file:      /project/modules/auth/Auth.Tests.ps1
Stem:      modules.auth.Auth
```

### Tag extraction

Tags are extracted from the `-Tag` parameter on the `It` line.  Both forms are
supported:

```powershell
It "name" -Tag "security"                       # single tag
It "name" -Tag "security", "regression"         # comma-separated
It "name" -Tag @("security", "regression") { }  # array literal
```

Only the `It` line itself is inspected.  Tags on enclosing `Describe` or
`Context` blocks are **not** propagated.

## Summary comparison

| | Java | C# | TypeScript | Go | Python | PowerShell |
|---|---|---|---|---|---|---|
| **Parser** | JavaParser AST | ANTLR4 structural | Node.js bundle (TS compiler API) | ANTLR4 structural | Python `ast` | ANTLR4 structural |
| **Runtime needed** | None | None | Node.js 18+ | None | Python 3.8+ | None |
| **Full syntax coverage** | Java 21 | Structural | Full TS/JS | Structural | Full CPython 3.8+ | Structural |
| **Framework detection** | Auto (imports) | Auto (using directives) | N/A | N/A | N/A (name-based) | N/A (DSL keyword) |
| **Tags** | `@Tag("v")` | `@Category`/`@Trait`/`@TestCategory` | None | None | `@pytest.mark.X` | `-Tag "v"` |
| **Display name** | `@DisplayName("t")` | `[Fact(DisplayName="t")]` (xUnit only) | Test name = display name | None | None | Test description |
| **Nested classes** | Yes | Yes | N/A | N/A | Direct methods only | N/A |
| **Async tests** | N/A | N/A | Yes | N/A | Yes | N/A |
| **Sub-test / parametrize expansion** | No (`@TestFactory` not expanded) | No | Partial (`test.each` detected once) | No (`t.Run` not expanded) | No (`parametrize` not expanded) | No |
| **Error recovery** | Parse failure = skip file | ANTLR4 continues | Worker timeout/crash → replaced | ANTLR4 continues | Worker error → skip file | ANTLR4 continues |
| **FQCN basis** | package + class | namespace + class | file path | parent directory | module path + class | directory + stem |

## See also

- [Discovery Plugins — configuration reference](discovery-plugins.md)
- [Troubleshooting](troubleshooting.md)
- [CLI Options](cli-reference.md)
