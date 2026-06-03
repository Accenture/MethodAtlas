# Migration guide

This page documents breaking changes and required configuration updates for
each major version boundary. Use it when upgrading an existing deployment.

For a full list of all changes (features, fixes, refactoring) generated from
commit history, see the project
[CHANGELOG](https://github.com/Accenture/MethodAtlas/blob/main/CHANGELOG.md).

## Versioning policy

| Series | Description |
|---|---|
| **2.x.x** | Single built-in Java scanner; configuration tightly coupled to JVM annotation names |
| **3.x.x** | Plugin-based discovery via `ServiceLoader`; configuration generalised to support multiple languages and platforms |
| **4.x.x** | Evidence packs and post-quantum-capable manifest signing; the GUI evidence CSV aligned to the CLI drift definition and extended with reviewer tag-delta columns |

MethodAtlas follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
A major version increment means at least one breaking change that requires
action from the operator.

## Upgrading from 3.x to 4.x

### Summary of breaking changes

| Area | What changed | Action required |
|---|---|---|
| Audit CSV | The GUI evidence CSV `tag_ai_drift` column now uses the **same definition as the CLI** — agreement between a source `@Tag("security")` marker and the AI security-relevance verdict — instead of comparing the whole applied and AI tag sets | Update any tooling that reads the GUI evidence CSV `tag_ai_drift` by its old set-comparison meaning |
| Audit CSV | Two columns, `tags_added` and `tags_removed`, are appended to the drift output of both the CLI (`-ai -drift-detect`) and the GUI evidence CSV | None for name-based consumers (`DeltaReport` resolves columns by name); positional parsers must account for the two new trailing columns |

### `tag_ai_drift` is now security-classification drift everywhere

Before 4.0.0 the desktop GUI's evidence CSV computed `tag_ai_drift` by comparing the reviewer's full set of applied tags against the full set of AI-suggested tags, while the CLI compared only the `@Tag("security")` marker against the AI security-relevance flag. The two producers therefore wrote different values into the same column.

From 4.0.0 both use the CLI definition (see [Tag vs AI Drift](ai/drift-detection.md)):

- `none` — the security tag and the AI verdict agree;
- `tag-only` — `@Tag("security")` is present but the AI says not security-relevant;
- `ai-only` — the AI says security-relevant but no `@Tag("security")` is present.

The reviewer's broader tag changes — which the old GUI definition tried to fold into `tag_ai_drift` — are now reported precisely and losslessly by the new `tags_added` (applied tags the AI did not suggest) and `tags_removed` (AI tags the reviewer did not apply) fields, each a sorted, semicolon-joined list. Both are blank when no AI suggestion is available. For consistency, `tags_added` and `tags_removed` are emitted in every enrichment-bearing format — CSV, the flat JSON output, and the SARIF result `properties` bag — alongside `tag_ai_drift`.

## Upgrading from 2.x to 3.x

### Summary of breaking changes

| Area | What changed | Action required |
|---|---|---|
| CLI flag | `-test-annotation` renamed to `-test-marker` | Update scripts and CI pipelines (alias still accepted — see below) |
| YAML key | `testAnnotations:` renamed to `testMarkers:` | **Must** update config files — old key is silently ignored |
| YAML key | New `properties:` key added | No action required; key is optional |
| CLI flag | New `-property key=value` flag added | No action required; flag is optional |

### `-test-annotation` → `-test-marker`

The CLI flag has been renamed from `-test-annotation` to `-test-marker` to
reflect that the concept is language-neutral (annotations in Java, attributes
in C#, not applicable in TypeScript).

**The old flag is kept as a backward-compatible alias.** Existing pipelines
that use `-test-annotation` continue to work without modification. The alias
will remain in 3.x and any future 3.x releases. Deprecation, if it occurs,
will be announced in a minor-version changelog.

```bash
# 2.x — still works in 3.x via alias
./methodatlas -test-annotation Test -test-annotation ScenarioTest src/test/java

# 3.x — preferred form
./methodatlas -test-marker Test -test-marker ScenarioTest src/test/java
```

**Recommended action:** update scripts and CI pipeline definitions to use
`-test-marker` at your convenience. There is no deadline to do so within the
3.x series.

### `testAnnotations:` → `testMarkers:` in YAML  ⚠️ breaking

This change **requires immediate action** if you use a YAML configuration file
(`-config <file>`) with a `testAnnotations:` list.

Unlike the CLI flag, the old YAML key has **no alias**. If your config file
contains `testAnnotations:`, MethodAtlas 3.x will silently ignore the key
because the YAML parser is configured to discard unknown fields. The plugin
will fall back to automatic framework detection, which may produce different
results from what you intended.

**How to detect the problem:** run a 3.x scan with your existing config file
and compare the discovered method count with your last 2.x scan. A significant
drop (or unexpected change in which methods are found) indicates that
`testAnnotations:` is being ignored.

**How to fix it:** rename the key in every config file you maintain.

```yaml
# 2.x config — broken in 3.x
testAnnotations:
  - Test
  - ScenarioTest

# 3.x config — correct
testMarkers:
  - Test
  - ScenarioTest
```

If your 2.x config relied on automatic framework detection (no
`testAnnotations:` key present), no change is needed — automatic detection
still works and is still the default.

### New: `properties:` YAML key and `-property` CLI flag

The 3.x release adds optional support for plugin-specific key/value pairs
forwarded to each discovery plugin. No action is needed for existing Java
configurations — the Java plugin ignores unknown properties.

This is relevant for non-JVM plugins that require plugin-specific settings.
The C# (.NET) and TypeScript plugins ship with the 3.x distribution.

```yaml
# TypeScript plugin configuration
properties:
  functionNames:
    - test
    - it
```

See [Discovery Plugins](discovery-plugins.md) for the full per-language
reference.

### Checklist: upgrading from 2.x to 3.x

- [ ] Search all CI pipeline definitions for `-test-annotation`; replace with
      `-test-marker` (or leave as-is — the alias still works)
- [ ] Search all YAML config files (`-config <file>`) for `testAnnotations:`;
      rename to `testMarkers:`
- [ ] Run a validation scan after upgrading and compare the discovered method
      count with the last 2.x baseline to confirm no silent regressions
