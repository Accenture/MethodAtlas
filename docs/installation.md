# Installation

## Requirements

| Requirement | Version |
|-------------|---------|
| Java        | 21 or later (Temurin recommended) |
| Operating system | Linux, macOS, Windows |

MethodAtlas parses Java source files without compiling them, so no project build tool (Gradle, Maven, etc.) is needed at runtime.

## Download a pre-built JAR

Pre-built releases are published on the [GitHub Releases page](https://github.com/Accenture/MethodAtlas/releases).
Download the latest `methodatlas-<version>.jar` and place it anywhere on your PATH.

```bash
# Verify the download
java -jar methodatlas-<version>.jar --version
```

## Build from source

```bash
git clone https://github.com/Accenture/MethodAtlas.git
cd MethodAtlas
./gradlew jar
# Executable JAR is written to build/libs/
java -jar build/libs/methodatlas-*.jar --version
```

Java 21 or later is required at build time. The build automatically enforces this constraint.

---

## Quick start

### 1 — Basic scan (CSV to stdout)

```bash
java -jar methodatlas.jar -scan src/test/java
```

Example output:

```
class,method,lines,tags,aiSecurityRelevant,aiTags,aiDisplayName,aiReason
com.example.AuthServiceTest,loginWithValidCredentials,12,,,,, 
com.example.AuthServiceTest,loginWithExpiredToken,8,,,,,
```

### 2 — AI enrichment with Ollama

Run [Ollama](https://ollama.com) locally, then:

```bash
java -jar methodatlas.jar \
  -scan src/test/java \
  -ai-provider ollama \
  -ai-model llama3 \
  -output results.csv
```

Example enriched output:

```
class,method,lines,tags,aiSecurityRelevant,aiTags,aiDisplayName,aiReason
com.example.AuthServiceTest,loginWithValidCredentials,12,,true,"authentication,access-control","Valid credentials grant access","Tests the happy-path login flow — directly relevant to authentication security."
com.example.AuthServiceTest,loginWithExpiredToken,8,,true,"authentication,session-management","Expired token is rejected","Verifies that stale tokens cannot be reused — a key session-fixation countermeasure."
```

### 3 — SARIF output for GitHub Code Scanning

```bash
java -jar methodatlas.jar \
  -scan src/test/java \
  -format sarif \
  -output methodatlas.sarif
```

Upload the result with the [upload-sarif](https://github.com/github/codeql-action) action:

```yaml
- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: methodatlas.sarif
```

### 4 — YAML configuration file

Create `methodatlas.yml` to avoid repeating flags:

```yaml
scan:
  roots:
    - src/test/java
ai:
  provider: openrouter
  model: google/gemini-flash-1.5
  apiKeyEnv: OPENROUTER_API_KEY
output:
  format: csv
  file: results.csv
```

Then run without any flags:

```bash
java -jar methodatlas.jar -config methodatlas.yml
```

Command-line flags always override values from the configuration file.

### 5 — Source write-back (`-apply-tags`)

After reviewing AI suggestions, apply `@DisplayName` and `@Tag` annotations directly to your source files:

```bash
java -jar methodatlas.jar \
  -scan src/test/java \
  -ai-provider ollama \
  -ai-model llama3 \
  -apply-tags
```

!!! warning "Modifies source files"
    This mode edits `.java` files in place. Commit or back up your work before running.

---

## Next steps

- [CLI Reference](cli-reference.md) — full list of flags and options
- [Output Formats](output-formats.md) — CSV, plain text, and SARIF schemas
- [AI Enrichment](ai-guide.md) — provider setup and manual workflow
- [CI/CD Setup](ci-setup.md) — GitHub Actions and Gitea pipelines
