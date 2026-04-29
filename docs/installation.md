# Installation

## Requirements

| Requirement | Version | Required for |
|-------------|---------|--------------|
| Java runtime | 21 or later (Temurin recommended) | All functionality |
| Node.js | 18 or later | TypeScript/JavaScript test discovery only |
| Operating system | Linux, macOS, Windows | — |

MethodAtlas parses source files without compiling them, so no project build
tool (Gradle, Maven, etc.) is required at runtime. Node.js is only needed when
scanning TypeScript or JavaScript test files — if it is absent or below version 18,
the TypeScript plugin disables itself gracefully and all other plugins continue normally.


## Option 1 — Distribution archive (recommended)

Pre-built distribution archives are published on the
[GitHub Releases page](https://github.com/Accenture/MethodAtlas/releases).
Each release ships as a ZIP and a TAR archive containing:

```
methodatlas-<version>/
├── bin/
│   ├── methodatlas          # Unix launch script
│   └── methodatlas.bat      # Windows launch script
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


## Option 2 — Build from source

```bash
git clone https://github.com/Accenture/MethodAtlas.git
cd MethodAtlas

# Build and install distribution locally
./gradlew installDist

# Run immediately from the installed location
build/install/methodatlas/bin/methodatlas src/test/java
```

To produce a portable archive:

```bash
./gradlew distZip       # → build/distributions/methodatlas-<version>.zip
./gradlew distTar       # → build/distributions/methodatlas-<version>.tar
```

Java 21 or later is required at build time. The build enforces this automatically.


## Option 3 — Single executable JAR (alternative)

If you only need the JAR without the wrapper scripts:

```bash
./gradlew jar
java -jar build/libs/methodatlas-<version>.jar src/test/java
```

!!! note
    This requires you to manage the classpath manually if you add dependencies.
    The distribution archive (Option 1 or 2) is the recommended approach.


## Quick start examples

### Static inventory (no AI)

```bash
./methodatlas src/test/java
```

Outputs CSV to stdout:

```
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

```
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

In regulated or air-gapped environments, download the archive and SBOM on an internet-connected machine, verify integrity, then transfer to the target environment.

## Next steps

- [Usage Modes](usage-modes/index.md) — overview of all operating modes and when to use each
- [CLI Reference](cli-reference.md) — full list of flags and options
- [AI Enrichment](ai/index.md) — provider setup, taxonomy, and manual workflow
- [CI/CD Setup](ci-setup.md) — GitHub Actions and Gitea pipelines
- [Regulated Environments](deployment/index.md) — PCI-DSS, ISO 27001, NIST SSDF, DORA, SOC 2, air-gapped deployment
