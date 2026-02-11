# MethodAtlasApp

`MethodAtlasApp` is a small standalone CLI that scans Java source trees for JUnit test methods and prints per-method
statistics:

- **FQCN** (fully-qualified class name)
- **method name**
- **LOC** (lines of code, based on the AST source range)
- **@Tag values** attached to the method (supports repeated `@Tag` and `@Tags({...})`)

It supports two output modes:

- **CSV** (default)
- **Plain text** (`-plain` as the first CLI argument)

## Build & run

Assuming you have a runnable JAR (e.g. `methodatlas.jar`):

```bash
java -jar methodatlas.jar [ -plain ] <path1> [<path2> ...]
````

* If **no paths** are provided, the current directory (i.e., `.`) is scanned.
* Multiple root paths are supported.

## Output modes

### CSV (default)

* Prints a **header line**
* Each record contains **values only**
* Tags are **semicolon-separated** in the `tags` column (empty if no tags)

Example:

```text
Feb 11, 2026 1:33:35 AM org.egothor.methodatlas.MethodAtlasApp scanRoot
INFO: Scanning /tmp/junit-15560885133010516491 for JUnit files
fqcn,method,loc,tags
com.acme.tests.SampleOneTest,alpha,8,fast;crypto
com.acme.tests.SampleOneTest,beta,6,param
com.acme.tests.SampleOneTest,gamma,4,nested1;nested2
com.acme.other.AnotherTest,delta,3,
```

### Plain text (`-plain`)

* Prints one line per detected method:
    * `FQCN, method, LOC=<n>, TAGS=<tag1;tag2;...>`
* If a method has **no tags**, it prints `TAGS=-`

Example:

```text
Feb 11, 2026 1:33:35 AM org.egothor.methodatlas.MethodAtlasApp scanRoot
INFO: Scanning /tmp/junit-12139245189413750595 for JUnit files
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto
com.acme.tests.SampleOneTest, beta, LOC=6, TAGS=param
com.acme.tests.SampleOneTest, gamma, LOC=4, TAGS=nested1;nested2
com.acme.other.AnotherTest, delta, LOC=3, TAGS=-
```

## Notes

* The scanner looks for files ending with `*Test.java`.
* JUnit methods are detected by annotations such as:
    * `@Test`
    * `@ParameterizedTest`
    * `@RepeatedTest`
* Tag extraction supports:
    * `@Tag("x")` (including repeated `@Tag`)
    * `@Tags({ @Tag("x"), @Tag("y") })`
