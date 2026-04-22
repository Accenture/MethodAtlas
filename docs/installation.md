# Installation

## Requirements

| Requirement | Version |
|-------------|---------|
| Java runtime | 21 or later (Temurin recommended) |
| Operating system | Linux, macOS, Windows |

MethodAtlas parses Java source files without compiling them, so no project build
tool (Gradle, Maven, etc.) is required at runtime.


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
fqcn,method,loc,tags
com.example.AuthServiceTest,loginWithValidCredentials,12,
com.example.AuthServiceTest,loginWithExpiredToken,8,
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
fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason
com.example.AuthServiceTest,loginWithValidCredentials,12,,true,"SECURITY: Valid credentials grant access","authentication;access-control","Tests the happy-path login flow — directly relevant to authentication security."
com.example.AuthServiceTest,loginWithExpiredToken,8,,true,"SECURITY: Expired token is rejected","authentication;session-management","Verifies that stale tokens cannot be reused — a key session-fixation countermeasure."
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


## Next steps

- [Usage Modes](usage-modes/index.md) — overview of all operating modes and when to use each
- [CLI Reference](cli-reference.md) — full list of flags and options
- [AI Enrichment](ai-guide.md) — provider setup, taxonomy, and manual workflow
- [CI/CD Setup](ci-setup.md) — GitHub Actions and Gitea pipelines
