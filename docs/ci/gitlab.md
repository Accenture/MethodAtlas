# GitLab CI/CD

This page describes how to integrate MethodAtlas into a GitLab CI/CD
pipeline. The techniques can be used individually or combined:

- **Merge request annotations** — findings posted as MR notes via `::notice`/`::warning` commands
- **SARIF upload to Security Dashboard** — findings appear in the GitLab MR security widget
- **AI result caching** — skips re-classification of unchanged test classes
- **Security test count gate** — pipeline fails when security test coverage drops

## Prerequisites

| Requirement | Details |
|---|---|
| Java runtime | Java 21 or later; the examples use the `eclipse-temurin:21-jdk` Docker image |
| MethodAtlas | Downloaded at job start from the GitHub release; no build step required |
| AI provider API key | Stored as a GitLab CI/CD variable (masked); not required for static inventory mode |
| GitLab tier | SARIF upload to the Security Dashboard requires GitLab Ultimate; annotation output has no tier requirement |

## Project configuration

Store your AI provider API key as a masked, protected CI/CD variable. In
the GitLab UI navigate to **Settings → CI/CD → Variables** and add:

| Variable | Value | Flags |
|---|---|---|
| `OPENAI_API_KEY` | Your OpenAI API key | Masked, Protected |

Use a different variable name and the `-ai-provider` flag if you are using
a provider other than OpenAI. See [AI Providers](../ai/providers.md).

## Minimal pipeline: static inventory

The following job requires no AI provider and produces a CSV inventory of
all test methods with their structural metadata:

```yaml
methodatlas-inventory:
  image: eclipse-temurin:21-jdk
  stage: test
  script:
    - curl -fsSL -o methodatlas.jar
        https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
    - java -jar methodatlas.jar src/test/java > inventory.csv
  artifacts:
    paths:
      - inventory.csv
    expire_in: 30 days
```

## AI-enriched scan with SARIF upload

GitLab CI supports SARIF reports as a first-class artefact type through the
`artifacts.reports.sast` key. Reports uploaded this way are parsed by GitLab
and displayed in the merge request security widget and the project Security
Dashboard (GitLab Ultimate required for the dashboard).

```yaml
methodatlas-scan:
  image: eclipse-temurin:21-jdk
  stage: test
  script:
    - curl -fsSL -o methodatlas.jar
        https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
    - |
      java -jar methodatlas.jar \
        -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
        -sarif -security-only \
        -content-hash \
        src/test/java \
        > methodatlas.sarif
  artifacts:
    reports:
      sast: methodatlas.sarif
    paths:
      - methodatlas.sarif
    expire_in: 90 days
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
```

!!! note "GitLab Ultimate"
    The `artifacts.reports.sast` integration and the Security Dashboard
    require GitLab Ultimate. On lower tiers the SARIF file is still produced
    and available as a downloadable job artefact; it can be reviewed manually
    or imported into an external code scanning tool.

## Caching AI results across runs

Use GitLab's `cache` directive to persist the MethodAtlas result file across
pipeline runs. The cache key is set per branch so that each branch maintains
its own classification state:

```yaml
methodatlas-scan:
  image: eclipse-temurin:21-jdk
  stage: test
  cache:
    key: "methodatlas-$CI_COMMIT_REF_SLUG"
    paths:
      - .methodatlas-cache.csv
    policy: pull-push
  script:
    - curl -fsSL -o methodatlas.jar
        https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
    - |
      CACHE_ARG=""
      if [ -f .methodatlas-cache.csv ]; then
        CACHE_ARG="-ai-cache .methodatlas-cache.csv"
      fi

      java -jar methodatlas.jar \
        -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
        -content-hash \
        -sarif -security-only \
        $CACHE_ARG \
        src/test/java \
        | tee .methodatlas-cache.csv > methodatlas.sarif
  artifacts:
    reports:
      sast: methodatlas.sarif
    expire_in: 90 days
```

On the first run for a branch (cache miss) every class is classified via the
AI provider. On subsequent runs only classes whose `content_hash` has changed
incur an API call.

!!! warning "Combined output stream"
    The example above uses `tee` to write to both the cache file and the
    SARIF output simultaneously. This works when `-sarif` is the only output
    mode. If you also want `-github-annotations` output, run the two modes as
    separate steps to avoid mixing annotation commands into the SARIF JSON.

## Security test count gate

Fail the pipeline when the number of security-relevant test methods drops
compared to the baseline on the default branch:

```yaml
stages:
  - build
  - test
  - gate

save-baseline:
  image: eclipse-temurin:21-jdk
  stage: test
  script:
    - curl -fsSL -o methodatlas.jar
        https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
    - |
      java -jar methodatlas.jar \
        -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
        -security-only -content-hash \
        src/test/java > baseline.csv
  artifacts:
    name: methodatlas-baseline
    paths:
      - baseline.csv
    expire_in: 90 days
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH

security-gate:
  image: eclipse-temurin:21-jdk
  stage: gate
  needs:
    - job: save-baseline
      artifacts: true
      optional: true
  script:
    - curl -fsSL -o methodatlas.jar
        https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
    - |
      java -jar methodatlas.jar \
        -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
        -security-only \
        src/test/java > current.csv

      if [ ! -f baseline.csv ]; then
        echo "No baseline available — skipping count gate."
        exit 0
      fi

      baseline=$(tail -n +2 baseline.csv | wc -l)
      current=$(tail -n +2 current.csv | wc -l)

      echo "Baseline security tests: $baseline"
      echo "Current security tests:  $current"

      if [ "$current" -lt "$baseline" ]; then
        echo "ERROR: Security test count dropped from $baseline to $current"
        exit 1
      fi
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
```

## Full pipeline

The following `.gitlab-ci.yml` combines caching, SARIF upload, and the count
gate. Adjust stage names and `needs` references to match your project's
existing pipeline structure.

```yaml
stages:
  - build
  - test

variables:
  METHODATLAS_JAR: methodatlas.jar

.methodatlas-setup: &methodatlas-setup
  - curl -fsSL -o $METHODATLAS_JAR
      https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar

methodatlas-scan:
  image: eclipse-temurin:21-jdk
  stage: test
  cache:
    key: "methodatlas-$CI_COMMIT_REF_SLUG"
    paths:
      - .methodatlas-cache.csv
    policy: pull-push
  script:
    - *methodatlas-setup
    - |
      CACHE_ARG=""
      if [ -f .methodatlas-cache.csv ]; then
        CACHE_ARG="-ai-cache .methodatlas-cache.csv"
      fi

      # Step 1: classify and update cache, emit SARIF
      java -jar $METHODATLAS_JAR \
        -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
        -sarif -security-only \
        -content-hash \
        $CACHE_ARG \
        src/test/java \
        | tee .methodatlas-cache.csv > methodatlas.sarif

      # Step 2: count gate (compare against previous run on this branch)
      if [ -f baseline.csv ]; then
        baseline=$(tail -n +2 baseline.csv | wc -l)
        current=$(tail -n +2 .methodatlas-cache.csv | wc -l)
        echo "Baseline: $baseline  Current: $current"
        if [ "$current" -lt "$baseline" ]; then
          echo "ERROR: Security test count dropped from $baseline to $current"
          exit 1
        fi
      fi

      cp .methodatlas-cache.csv baseline.csv
  artifacts:
    reports:
      sast: methodatlas.sarif
    paths:
      - methodatlas.sarif
      - baseline.csv
    expire_in: 90 days
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
```

## Using a YAML configuration file

For teams that prefer to keep MethodAtlas settings in version control rather
than in pipeline YAML, create a `methodatlas.yml` at the project root:

```yaml
# methodatlas.yml
aiProvider: openai
aiModel: gpt-4o-mini
contentHash: true
securityOnly: true
```

Reference it from the pipeline with the `-config` flag:

```bash
java -jar methodatlas.jar -config methodatlas.yml -sarif src/test/java
```

The API key must still be supplied via an environment variable at runtime;
do not store secrets in the configuration file committed to version control.

## Further reading

- [GitLab CI/CD — SAST reports](https://docs.gitlab.com/ee/user/application_security/sast/)
- [GitLab CI/CD — Caching](https://docs.gitlab.com/ee/ci/caching/)
- [GitLab CI/CD — Variables](https://docs.gitlab.com/ee/ci/variables/)
- [MethodAtlas — AI Providers](../ai/providers.md)
- [MethodAtlas — Output Formats](../output-formats.md)
