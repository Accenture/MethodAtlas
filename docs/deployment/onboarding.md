# Onboarding a Brownfield Codebase

This page describes the recommended sequence for introducing MethodAtlas to an existing Java project — one that already has a test suite but no established classification, override file, or CI integration.

The steps are designed to be progressive: each phase produces usable value on its own, and the next phase builds on the previous one without requiring any rework.

## Phase 1 — Static inventory (day 1)

Start with a no-AI scan to understand the size and structure of the test suite. No AI provider, no configuration, no network access required.

```bash
./methodatlas src/test/java > inventory.csv
```

Review the output:
- How many test methods does the project have?
- Which classes have `@Tag` values already?
- Are any `@DisplayName` annotations already in place?

This establishes the **baseline**. Keep `inventory.csv` under version control — it will be your starting point for the delta report in Phase 4.

## Phase 2 — AI classification (first week)

Run a full AI scan with content hashing and save the result as the authoritative cache:

```bash
./methodatlas -ai -content-hash \
  -ai-provider <provider> \
  -ai-api-key-env <ENV_VAR> \
  src/test/java > scan-v1.csv
```

Review the `ai_security_relevant`, `ai_tags`, and `ai_reason` columns:
- Do the security-relevant classifications look correct?
- Are there obvious false positives (e.g. utility or formatter tests classified as security-relevant)?
- Are there known-security tests classified as not relevant?

At this stage, do not apply any annotations yet. The goal is to understand classification quality before acting on it.

## Phase 3 — Override file and human corrections

Create an override file to record corrections to the AI output:

```yaml
# .methodatlas-overrides.yaml
overrides:
  - fqcn: com.acme.util.DateFormatterTest
    method: format_returnsIso8601
    securityRelevant: false
    reason: "Date formatting only — no security property tested"
    note: "Reviewed 2026-04-25 by alice"

  - fqcn: com.acme.crypto.AesGcmTest
    method: roundTrip_encryptDecrypt
    securityRelevant: true
    tags: [security, crypto]
    reason: "Validates AES-GCM correctness — core cryptographic test"
    note: "Confirmed 2026-04-25 by security team"
```

Re-run with the override file to verify that the corrections are applied:

```bash
./methodatlas -ai -content-hash -ai-cache scan-v1.csv \
  -override-file .methodatlas-overrides.yaml \
  src/test/java > scan-v2.csv
```

Commit the override file to version control. Every future PR that changes it becomes an auditable record of a human classification decision.

See [Classification Overrides](../ai/overrides.md) for the full field reference and review cadence guidance.

## Phase 4 — Drift detection gate

Enable drift detection to surface disagreements between `@Tag("security")` in source and AI classifications:

```bash
./methodatlas -ai -content-hash -ai-cache scan-v2.csv \
  -override-file .methodatlas-overrides.yaml \
  -drift-detect \
  src/test/java > scan-v3.csv
```

Review the `tag_ai_drift` column:
- `ai-only` — AI considers it security-relevant but the source has no `@Tag("security")`; either add the tag or add an override
- `tag-only` — the source has `@Tag("security")` but AI disagrees; either remove the tag or add an override confirming human intent

Once the drift count reaches zero (or the remaining drift entries are explained), the codebase is consistent. From this point, adding drift detection to CI will catch future regressions.

## Phase 5 — Apply annotations (optional)

If the team decides to write AI-suggested annotations back to source:

**Option A — Direct AI write-back:**

```bash
./methodatlas -ai -apply-tags src/test/java
```

Only security-relevant methods are annotated. Existing annotations are not overwritten. Review the diff before committing.

**Option B — CSV-reviewed write-back:**

```bash
# 1. Produce the review CSV
./methodatlas -ai -content-hash -ai-cache scan-v3.csv \
  -override-file .methodatlas-overrides.yaml \
  src/test/java > review.csv

# 2. Open review.csv and adjust the display_name and tags columns.
#    Leave rows unchanged if no annotation change is wanted.

# 3. Apply with mismatch guard
./methodatlas -apply-tags-from-csv review.csv -mismatch-limit 1 src/test/java
```

Option B is preferred in regulated environments because it requires explicit human sign-off (editing the CSV) before any source file is touched.

See [Apply Tags from CSV](../usage-modes/apply-tags-from-csv.md) for the full workflow.

## Phase 6 — CI gate

Add MethodAtlas to the CI pipeline. The minimum recommended configuration for a regulated environment:

```bash
./methodatlas \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash -ai-cache scan-v3.csv \
  -override-file .methodatlas-overrides.yaml \
  -sarif \
  -security-only \
  -emit-metadata \
  src/test/java \
  > security-tests.sarif
```

Commit `scan-v3.csv` (the cache) and `.methodatlas-overrides.yaml` to the repository. The CI run will:
- Reuse AI results for unchanged classes (zero API calls for stable code)
- Re-classify any new or changed class
- Apply human overrides on top of AI output
- Emit a SARIF file suitable for GitHub Code Scanning or archiving as evidence

Add `-mismatch-limit 1` to the apply-tags-from-csv step in CI if annotation write-back is part of the pipeline.

See per-platform CI guides: [GitHub Actions](../ci/github-actions.md), [GitLab](../ci/gitlab.md), [Azure DevOps](../ci/azure-devops.md).

## Summary: progression at a glance

| Phase | Command additions | Gate value |
|---|---|---|
| 1 — Static inventory | *(no flags)* | Baseline count; understand existing tags |
| 2 — AI classification | `-ai -content-hash` | Security test inventory with rationale |
| 3 — Override file | `-ai-cache … -override-file …` | Human corrections; auditable decisions |
| 4 — Drift detection | `-drift-detect` | Source `@Tag` / AI agreement established |
| 5 — Annotations | `-apply-tags-from-csv` | Source annotations match agreed state |
| 6 — CI gate | `-sarif -security-only -emit-metadata` | Regression detection; evidence artefact |
