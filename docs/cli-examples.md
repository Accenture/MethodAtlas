# CLI Examples

Practical command-line examples grouped by use case. For the full option
reference see [CLI Reference](cli-reference.md).

## Basic scanning

```bash
# Static scan — CSV to stdout
./methodatlas /path/to/project

# Plain text output
./methodatlas -plain /path/to/project

# SARIF output
./methodatlas -sarif /path/to/project > results.sarif

# Scan with metadata header
./methodatlas -emit-metadata /path/to/project
```

## File selection

```bash
# Include integration tests alongside unit tests
./methodatlas -file-suffix Test.java -file-suffix IT.java /path/to/project

# Recognise a custom test annotation (Java/Kotlin — use annotation simple name)
./methodatlas -test-marker Test -test-marker ScenarioTest /path/to/project

# Legacy alias -test-annotation is also accepted
./methodatlas -test-annotation Test -test-annotation ScenarioTest /path/to/project

# Pass plugin-specific properties (e.g. test function names for a TypeScript plugin)
./methodatlas -property functionNames=test -property functionNames=it /path/to/project
```

## Configuration file

```bash
# Load defaults from a YAML configuration file
./methodatlas -config ./methodatlas.yaml /path/to/tests

# Override YAML output mode on the command line
# (even if methodatlas.yaml sets outputMode: plain, -sarif takes precedence)
./methodatlas -config ./methodatlas.yaml -sarif /path/to/tests
```

## AI enrichment

```bash
# AI with local Ollama
./methodatlas -ai -ai-provider ollama -ai-model qwen2.5-coder:7b /path/to/tests

# AI with confidence scoring
./methodatlas -ai -ai-confidence -ai-provider ollama /path/to/tests

# AI with OpenRouter
export OPENROUTER_API_KEY=sk-...
./methodatlas -ai \
  -ai-provider openrouter \
  -ai-api-key-env OPENROUTER_API_KEY \
  -ai-model stepfun/step-3.5-flash:free \
  /path/to/tests

# AI with Anthropic
export ANTHROPIC_API_KEY=sk-ant-...
./methodatlas -ai \
  -ai-provider anthropic \
  -ai-api-key-env ANTHROPIC_API_KEY \
  -ai-model claude-haiku-4-5-20251001 \
  /path/to/tests

# Automatic provider selection (tries Ollama first, then API-key providers)
./methodatlas -ai /path/to/tests

# Compact taxonomy — better for smaller models
./methodatlas -ai -ai-taxonomy-mode optimized /path/to/tests

# SARIF with AI enrichment
./methodatlas -ai -sarif /path/to/tests > results.sarif
```

## Filtering and hashing

```bash
# Emit content hash fingerprints
./methodatlas -content-hash /path/to/project

# Content hash with SARIF output
./methodatlas -content-hash -sarif /path/to/project > results.sarif

# Filter high-confidence findings (requires -ai-confidence)
./methodatlas -ai -ai-confidence /path/to/tests | \
  awk -F',' 'NR==1 || ($11+0) >= 0.7'
```

## Source write-back

```bash
# Apply AI annotations to source files
./methodatlas -ai -apply-tags /path/to/tests

# Apply annotations using manual responses
./methodatlas -manual-consume ./work ./responses -apply-tags /path/to/tests
```

## Manual AI workflow

```bash
# Phase 1 — write prompts
./methodatlas -manual-prepare ./work ./responses /path/to/tests

# (paste each work file's AI PROMPT block into a chat window
#  and save the response into the corresponding .response.txt file)

# Phase 2 — consume responses and emit CSV
./methodatlas -manual-consume ./work ./responses /path/to/tests
```

## Real-world output example

Running against a mix of functional and cryptographic test classes:

```bash
./methodatlas -ai \
  -ai-provider openrouter \
  -ai-api-key-env OPENROUTER_API_KEY \
  -ai-model stepfun/step-3.5-flash:free \
  path/to/tests/
```

Produces output such as:

```csv
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score
org.egothor.methodatlas.MethodAtlasAppTest,csvMode_detectsMethodsLocAndTags,22,,,false,,,Test verifies functional output format only.,0.0
zeroecho.core.alg.aes.AesGcmCrossCheckTest,aesGcm_stream_vs_jca_ctxOnly_crosscheck,52,,,true,SECURITY: crypto - cross-check AES-GCM stream encryption with JCA reference,security;crypto,Verifies custom AES-GCM matches JCA output — ensures cryptographic correctness.,0.0
zeroecho.core.alg.aes.AesLargeDataTest,aesGcmLargeData_ctxOnly,27,,,true,SECURITY: crypto - AES-GCM round-trip with context-only parameters,security;crypto,Tests encryption and decryption correctness for large data using AES-GCM.,0.0
zeroecho.core.alg.mldsa.MldsaLargeDataTest,mldsa_complete_suite_streaming_sign_verify_large_data,24,,,true,SECURITY: crypto - ML-DSA streaming signature and verification for large data,security;crypto;owasp,Validates ML-DSA signature creation and verification including tamper detection.,0.0
```

Observations:

- Functional tests are left untagged.
- Cryptographic tests are tagged consistently with `security;crypto`.
- Suggested display names are ready to use as `@DisplayName` values.
- The `ai_reason` column makes the classification defensible during review.
