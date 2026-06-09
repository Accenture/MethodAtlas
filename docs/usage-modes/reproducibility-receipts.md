# Reproducibility Receipts

The `-emit-receipt` flag produces a small JSON file alongside any scan that captures the SHA-256 fingerprint of every input that influenced the result. An auditor can then answer the question *"would a re-run today produce the same SARIF?"* by comparing receipts — without repeating the scan.

## When to use this mode

- Compliance evidence that two releases were classified against an identical configuration.
- Detecting silent drift in inputs that don't have version numbers (override files, taxonomy files, the AI cache).
- Demonstrating bit-exact replay when paired with [`-ai-cache`](../cli-reference.md#-ai-cache): every AI classification served from cache means the receipt's `deterministicReplay` flag is `true`.

## CLI flags

| Flag | Purpose |
|------|---------|
| `-emit-receipt` | Opt-in to receipt writing. Without it, no file is produced. |
| `-receipt-file <path>` | Override the default output path. Default: `methodatlas-receipt.json` in the current working directory. |

The receipt is always written to disk, never to standard output. A write failure logs a `WARNING` via `java.util.logging` and lets the scan exit with its original status.

## Schema reference

The receipt is a single JSON object. Field types are JSON types; "absent" means the field is omitted entirely (Jackson `NON_NULL` policy).

| Field | Type | When present | Meaning |
|-------|------|--------------|---------|
| `schemaVersion` | string | always | Currently `"2"`. Bumped on any breaking schema change; see [Schema migration: v1 → v2](#schema-migration-v1--v2). |
| `generatedUtc` | string | always | ISO-8601 instant when the receipt was written. |
| `methodAtlasVersion` | string | always | `Implementation-Version` from the JAR manifest, or `"dev"` for local builds. |
| `javaVersion` | string | always | `System.getProperty("java.version")` at receipt-write time. |
| `outputMode` | string | always | One of `SARIF`, `CSV`, `PLAIN`, `JSON`, `GITHUB_ANNOTATIONS`. |
| `scanRoots` | string array | always | Absolute paths of every scan root supplied to MethodAtlas. |
| `deterministicReplay` | boolean | always | See the next section. |
| `inputs.taxonomyFile` | object `{path,sha256}` | when `-ai-taxonomy <file>` is used | Absolute path and SHA-256 of the external taxonomy file. |
| `inputs.builtInTaxonomy` | string | when no taxonomy file is used | Name of the built-in taxonomy: `DEFAULT` or `OPTIMIZED`. |
| `inputs.overrideFile` | object `{path,sha256}` | when `-override-file` is supplied | Absolute path and SHA-256 of the classification override YAML. |
| `inputs.aiCacheFile` | object `{path,sha256}` | when `-ai-cache` is supplied | Absolute path and SHA-256 of the AI cache CSV. |
| `inputs.aiProvider` | string | when AI is enabled | Uppercase provider enum, e.g. `OPENAI`. |
| `inputs.aiModel` | string | when AI is enabled | Provider-specific model identifier. |
| `inputs.classificationPromptHash` | string | when AI is enabled | SHA-256 of the **effective** method-classification prompt template — the built-in default, or the operator's `-classification-prompt` override. Per-run data (taxonomy, methods, source) is excluded. |
| `inputs.triageAppendixPromptHash` | string | when AI is enabled | SHA-256 of the effective folded credential-triage appendix template (`-triage-prompt` override, else built-in). |
| `inputs.dedicatedTriagePromptHash` | string | when AI is enabled | SHA-256 of the effective standalone credential-triage template (`-dedicated-triage-prompt` override, else built-in). |
| `configHash` | string | always | 64-char lowercase hex SHA-256 of a canonical key=value serialisation of the input fingerprints. See below. |

The three prompt hashes record the *exact* prompt wording sent to the model. Because they cover the effective template, a custom prompt supplied with `-classification-prompt` (or the triage flags) changes the corresponding hash — an auditor sees that a non-default prompt was used, and can reproduce the checksum with `methodatlas -check-prompts -classification-prompt <file>`.

## `deterministicReplay`

`true` means a re-run with the same source files **must** produce identical output:

- AI is disabled, so no live model call is possible — the output is a function of source + static configuration only.
- *or* AI is enabled and `-ai-cache` is supplied with a hash-keyed cache that covers every class; cache hits short-circuit the provider.

`false` means a re-run could differ because at least one classification will hit a live model whose output is not bit-stable.

Out of scope for this flag: source-file modifications, JVM non-determinism in reflection-based service loading, and clock-dependent metadata fields like `generatedUtc`. The flag describes the *inputs* the scan was given, not the entire universe.

## `configHash` computation

The algorithm is deliberately simple so an auditor can re-derive it with `sha256sum` and a `printf`:

1. Populate the following ten keys with their values; absent inputs map to the empty string:
   - `aiCacheFileSha256`
   - `aiModel`
   - `aiProvider`
   - `builtInTaxonomy`
   - `classificationPromptHash`
   - `dedicatedTriagePromptHash`
   - `methodAtlasVersion`
   - `overrideFileSha256`
   - `taxonomyFileSha256`
   - `triageAppendixPromptHash`
2. Sort the keys alphabetically (a `TreeMap` enforces this implicitly).
3. Serialise as `key1=value1\nkey2=value2\n...keyN=valueN\n` using UTF-8.
4. Apply SHA-256, render lowercase hex.

The same value is reported as the receipt's `configHash` field. An auditor with two receipts can independently recompute both hashes and detect any tampering. A worked re-derivation shell snippet:

```bash
{
  printf 'aiCacheFileSha256=%s\n' "$(jq -r '.inputs.aiCacheFile.sha256 // ""' receipt.json)"
  printf 'aiModel=%s\n'           "$(jq -r '.inputs.aiModel // ""'           receipt.json)"
  printf 'aiProvider=%s\n'        "$(jq -r '.inputs.aiProvider // ""'        receipt.json)"
  printf 'builtInTaxonomy=%s\n'   "$(jq -r '.inputs.builtInTaxonomy // ""'   receipt.json)"
  printf 'classificationPromptHash=%s\n'  "$(jq -r '.inputs.classificationPromptHash // ""'  receipt.json)"
  printf 'dedicatedTriagePromptHash=%s\n' "$(jq -r '.inputs.dedicatedTriagePromptHash // ""' receipt.json)"
  printf 'methodAtlasVersion=%s\n' "$(jq -r '.methodAtlasVersion'             receipt.json)"
  printf 'overrideFileSha256=%s\n' "$(jq -r '.inputs.overrideFile.sha256 // ""' receipt.json)"
  printf 'taxonomyFileSha256=%s\n' "$(jq -r '.inputs.taxonomyFile.sha256 // ""' receipt.json)"
  printf 'triageAppendixPromptHash=%s\n' "$(jq -r '.inputs.triageAppendixPromptHash // ""' receipt.json)"
} | sha256sum
```

The output should match the `configHash` field of the receipt.

## Worked example

Two receipts produced one week apart, against the same project, with the same SARIF output:

```text
Receipt A — Monday                Receipt B — Friday
generatedUtc 2026-05-25T09:00Z    generatedUtc 2026-05-29T17:00Z
methodAtlasVersion 3.4.0          methodAtlasVersion 3.4.0
configHash 1aa7...c9d2            configHash 1aa7...c9d2          ✅ identical
deterministicReplay true          deterministicReplay true
```

Equal `configHash` + `deterministicReplay=true` is a strong claim: bytes of every SARIF output would be identical, modulo `scan_timestamp` metadata.

A second pair, after rotating the AI model:

```text
Receipt B — Friday 16:00          Receipt C — Friday 17:30
configHash 1aa7...c9d2            configHash 0f44...8e30          ❌ different
inputs.aiModel  gpt-4o            inputs.aiModel  gpt-4o-mini
inputs.aiCacheFile (present)      inputs.aiCacheFile (absent)
deterministicReplay true          deterministicReplay false
```

`configHash` divergence localises the cause: the diff between the two receipts shows exactly which inputs changed.

## Schema migration: v1 → v2

Schema **v2** (MethodAtlas 4.1.0) changed how the AI prompt is fingerprinted. This matters for anyone comparing a receipt produced by 4.1.0+ against one archived from an earlier release.

### What changed

- **Removed:** the single field `inputs.promptTemplateHash`.
- **Added:** three fields recording the *effective* (built-in or operator-overridden) prompt templates separately:
  - `inputs.classificationPromptHash`
  - `inputs.triageAppendixPromptHash`
  - `inputs.dedicatedTriagePromptHash`
- The `configHash` canonical key set grew from eight keys to ten: `promptTemplateHash` was removed and the three new keys were added.

### Why

v1 hashed only the classification prompt skeleton. The credential-triage prompts (introduced alongside credential detection) were **not** covered, so a change to triage wording was invisible to an auditor. v1 also could not represent operator-supplied custom prompts. v2 closes both gaps: every prompt the model receives has its own checksum, and a custom template changes the matching hash.

### Effect on previously archived receipts

- A v1 and a v2 receipt are **not** directly comparable by `configHash`: the canonical key sets differ, so the hashes will differ even for an otherwise identical configuration. Always compare receipts of the **same `schemaVersion`**; use `schemaVersion` as the first discriminator.
- For a like-for-like check across the boundary, re-run the older configuration with 4.1.0+ to obtain a v2 receipt, then compare v2-to-v2.
- A v1 receipt remains valid evidence for the run that produced it; it is simply expressed in the older schema. Retain it as-is — do not attempt to "upgrade" an archived v1 receipt, as that would invalidate its provenance.

### New structure at a glance

```text
v1 inputs:                          v2 inputs:
  ...                                 ...
  aiModel                             aiModel
  promptTemplateHash  ← single hash   classificationPromptHash   ← classification prompt
                                      triageAppendixPromptHash   ← folded triage appendix
                                      dedicatedTriagePromptHash  ← standalone triage prompt
```

## Known limitations

- The receipt records the inputs that *MethodAtlas* observed; it does not capture network conditions, AI provider load, or model-side updates that may affect a live-call run.
- Source files are deliberately not hashed: the goal is "do two runs of MethodAtlas with the same inputs produce the same output?", which is independent of whether the source changed. Source change is observed via the existing `-content-hash` mechanism.
- The receipt is not signed. For a signed envelope, generate the receipt first and then sign it with the ZeroEcho CLI (or another tool of your choice) as a follow-on step.
