# Installation

MethodAtlas ships as a self-contained Java application with a small set of
auxiliary native tools required only for specific scan paths. This chapter
walks through the three supported installation routes — pre-built
distribution archive, build-from-source, and standalone JAR — and explains
when to pick each. By the end you will have a working `methodatlas` binary
on your `PATH`, a verified checksum, and a first scan running against a
real test source tree.

The same archive contains both the command-line scanner and the Swing
desktop GUI. There is one shared `lib/` directory and two start scripts
(`bin/methodatlas`, `bin/methodatlas-gui`), so no separate install is
needed if you later decide to use the interactive review surface.

## Requirements

The runtime footprint is intentionally small. Apart from a modern Java
runtime there are no mandatory dependencies; everything else is opt-in.

| Requirement | Version | Required for |
|--------|-----------------|----------------|
| Java runtime | 21 or later (Temurin recommended) | All functionality |
| Node.js | 18 or later | TypeScript/JavaScript test discovery only |
| Operating system | Linux, macOS, Windows | — |
| Disk space | < 60 MB unpacked | Distribution archive + grammar bundles |
| RAM at runtime | ~256 MB JVM heap for typical scans; ~1 GB for very large monorepos | Scaled by scan size, not by AI use |

**Java 21+** is required because MethodAtlas uses sealed types, pattern
matching on records, and the modern `java.net.http.HttpClient` introduced
in long-term-support release 21. Older JDKs will refuse to launch with a
clear error message — the requirement is enforced at JVM startup. Temurin
is recommended because it is freely redistributable and ships on all
major CI runners; any other certified OpenJDK build (Microsoft, Amazon
Corretto, Oracle, Zulu) works identically.

**Node.js 18+** is consumed only by the TypeScript discovery plugin, which
spawns a small Node-based parser to walk `*.ts` / `*.tsx` ASTs. If Node
is absent or below the minimum version the plugin disables itself
silently, the scan continues for every other language, and a single
diagnostic is logged. Java, C#, Go, Python, PowerShell, ABAP, and COBOL
scans require no runtime beyond the JVM.

MethodAtlas parses source files lexically — it never compiles your code
and never resolves your project's dependencies. As a result no build tool
(Gradle, Maven, MSBuild, npm install) is required on the scan host, and
broken or partially-checked-out projects can still be inventoried.


## Option 1 — Distribution archive (recommended)

The distribution archive is the canonical install for end users, CI
runners, and air-gapped environments. It is reproducible, signed with a
SHA-256 checksum, and contains everything needed to scan in offline mode.

Pre-built distribution archives are published on the
[GitHub Releases page](https://github.com/Accenture/MethodAtlas/releases).
Each release ships as a ZIP and a TAR archive containing:

```text
methodatlas-<version>/
├── bin/
│   ├── methodatlas          # Unix launch script
│   ├── methodatlas.bat      # Windows launch script
│   ├── methodatlas-gui      # Unix launch script
│   └── methodatlas-gui.bat  # Windows launch script
└── lib/
    └── methodatlas-<version>.jar  (+ dependency JARs)
```

The `bin/` scripts handle the classpath automatically. No manual `-cp` flag needed.

### Install

=== "Linux / macOS"

    ```bash
    # Download and extract
    unzip methodatlas-<version>.zip
    cd methodatlas-<version>

    # Run directly
    ./bin/methodatlas src/test/java

    # Or add bin/ to PATH for convenience
    export PATH="$PWD/bin:$PATH"
    methodatlas src/test/java
    ```

=== "Windows"

    ```bat
    # Extract the ZIP, then from the extracted folder:
    bin\methodatlas.bat src\test\java
    ```

### Verify

```bash
./methodatlas --help
```

A successful `--help` invocation prints the synopsis, the full flag list,
and the exit-code table. If the binary exits non-zero or prints a Java
launcher error, the most common cause is a JDK older than 21 on the
`PATH`; check with `java --version`.


## Option 2 — Build from source

Building from source is appropriate when you need to pin to a specific
commit, apply a downstream patch, or produce an internal distribution
that has been reviewed by a security or compliance function. The build
is fully reproducible from a clean checkout — every dependency is
declared in `gradle/libs.versions.toml`, and Gradle's wrapper pins the
build tool version.

```bash
git clone https://github.com/Accenture/MethodAtlas.git
cd MethodAtlas

# Build and install distribution locally
./gradlew installDist

# Run immediately from the installed location
build/install/methodatlas/bin/methodatlas src/test/java
```

To produce a portable archive identical in layout to the published
release artefacts:

```bash
./gradlew distZip       # → build/distributions/methodatlas-<version>.zip
./gradlew distTar       # → build/distributions/methodatlas-<version>.tar
```

Java 21 or later is required at build time. The build enforces this
automatically via `targetCompatibility` and a manual `JavaVersion` check
in the root build script; the build fails with a clear message if an
older JDK is detected.

A complete `./gradlew build` also runs every quality gate in one pass —
unit tests, PMD, SpotBugs, Error Prone, JaCoCo coverage verification, PIT
mutation testing, the dependency licence allowlist, and (when
`NVD_API_KEY` is set) OWASP Dependency-Check. The full thresholds are
documented in the [Quality gates](quality-gates.md) reference.


## Option 3 — Single executable JAR (alternative)

If you only need the JAR without the wrapper scripts:

```bash
./gradlew jar
java -jar build/libs/methodatlas-<version>.jar src/test/java
```

!!! note
    This requires you to manage the classpath manually if you add dependencies.
    The distribution archive (Option 1 or 2) is the recommended approach.

The standalone JAR omits the discovery plugins, the AI runtime, and the
output emitters that the `bin/` scripts wire onto the classpath
automatically. It is occasionally useful when embedding MethodAtlas
inside another Java application but is not the canonical end-user path.


## Quick start examples

### Static inventory (no AI)

```bash
./methodatlas src/test/java
```

Outputs CSV to stdout:

```text
fqcn,method,loc,tags,display_name
com.example.AuthServiceTest,loginWithValidCredentials,12,,
com.example.AuthServiceTest,loginWithExpiredToken,8,,
```

### AI enrichment with Ollama

Start [Ollama](https://ollama.com) locally and pull a model, then:

```bash
./methodatlas -ai \
  -ai-provider ollama \
  -ai-model qwen2.5-coder:7b \
  src/test/java
```

Example enriched output:

```text
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score
com.example.AuthServiceTest,loginWithValidCredentials,12,,,true,"SECURITY: Valid credentials grant access","authentication;access-control","Tests the happy-path login flow — directly relevant to authentication security.",0.0
com.example.AuthServiceTest,loginWithExpiredToken,8,,,true,"SECURITY: Expired token is rejected","authentication;session-management","Verifies that stale tokens cannot be reused — a key session-fixation countermeasure.",0.0
```

### SARIF output for GitHub Code Scanning

```bash
./methodatlas -sarif src/test/java > methodatlas.sarif
```

Upload the result with the [upload-sarif](https://github.com/github/codeql-action) action:

```yaml
- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: methodatlas.sarif
```

### YAML configuration file

Create `methodatlas.yml` to avoid repeating flags on every run:

```yaml
ai:
  provider: openrouter
  model: google/gemini-flash-1.5
  apiKeyEnv: OPENROUTER_API_KEY
```

```bash
./methodatlas -config methodatlas.yml src/test/java
```

CLI flags always override values from the configuration file.


## Verifying the distribution archive

Each release publishes a SHA-256 checksum file and a CycloneDX SBOM alongside the ZIP and TAR archives on the [GitHub Releases page](https://github.com/Accenture/MethodAtlas/releases).

**Verify the download integrity (Linux / macOS):**

```bash
# Download the archive and its checksum file
curl -LO https://github.com/Accenture/MethodAtlas/releases/download/v<version>/methodatlas-<version>.zip
curl -LO https://github.com/Accenture/MethodAtlas/releases/download/v<version>/methodatlas-<version>.zip.sha256

# Compare (output must be "OK")
sha256sum -c methodatlas-<version>.zip.sha256
```

**Verify on Windows (PowerShell):**

```powershell
$expected = (Get-Content methodatlas-<version>.zip.sha256).Split(' ')[0]
$actual   = (Get-FileHash methodatlas-<version>.zip -Algorithm SHA256).Hash.ToLower()
if ($actual -eq $expected) { "OK" } else { "MISMATCH" }
```

**SBOM:** the `methodatlas-<version>-sbom.json` file (CycloneDX 1.4 format) lists all runtime dependency components with their versions, hashes, and licence identifiers. Import it into your software composition analysis (SCA) platform or supply it to your legal / security team for third-party licence review.

In regulated or air-gapped environments, download the archive and SBOM on an internet-connected machine, verify integrity, then transfer to the target environment. The SBOM is signed by the release pipeline and listed as a release asset alongside the binary archives; together they form a self-contained provenance bundle suitable for software-supply-chain attestation.

## What gets installed

Whichever route you pick, the resulting installation is a single
directory containing two start scripts and a `lib/` folder. There is no
system-wide registration, no daemon, and no background service — the
binary is invoked on demand by a developer, a CI step, or the desktop
GUI. Uninstalling is a `rm -rf` (or *Move to Recycle Bin*).

| Path | Purpose |
|---|---|
| `bin/methodatlas` / `.bat` | CLI launcher; auto-resolves the classpath from `lib/` |
| `bin/methodatlas-gui` / `.bat` | Swing desktop GUI launcher; shares the same `lib/` |
| `lib/*.jar` | Application, plugins, AI runtime, and dependency JARs |

Two start scripts share one `lib/` directory by design: the CLI and the
GUI ship as a single distribution because both invoke the same scanner
engine and the same plugin set, and shipping them together keeps the
versions strictly in sync.

## Next steps

- [Usage Modes](usage-modes/index.md) — overview of all operating modes and when to use each
- [CLI Reference](cli-reference.md) — full list of flags and options
- [AI Enrichment](ai/index.md) — provider setup, taxonomy, and manual workflow
- [CI/CD Setup](ci-setup.md) — GitHub Actions and Gitea pipelines
- [Regulated Environments](deployment/index.md) — PCI-DSS, ISO 27001, NIST SSDF, DORA, SOC 2, air-gapped deployment
