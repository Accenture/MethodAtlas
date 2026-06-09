# Credential detection

MethodAtlas can detect credential candidates (provider tokens, private keys,
password and token assignments, high-entropy literals) in the source it scans
and, when AI is available, score each candidate's credibility and attribute it
to the system it authenticates against. The feature is opt-in behind
`-detect-secrets` and is designed for regulated and air-gapped environments: the
deterministic layer performs **no live verification** — it never connects to a
credential's endpoint to test it.

## Two layers

Credential detection follows the same deterministic-versus-AI split as the rest
of MethodAtlas.

### Deterministic detection (always)

A clean-room **Aho-Corasick** automaton scans each file in a single linear pass
for fixed anchors (provider prefixes such as `AKIA`, `ghp_`, `AIza`, `xoxb-`,
and credential keywords such as `password`, `secret`, `token`). Each anchor hit
triggers a precise confirm pattern from a curated rule catalog. A second,
unanchored pass flags high-entropy quoted literals using a Shannon-entropy gate.

The detector is a drop-in `CredentialDetector` plugin discovered via `ServiceLoader`,
exactly like the language discovery plugins. It depends only on the public API
module and never calls AI — identical input always yields identical candidates.

The bundled catalog ships **170+ rules** covering the credential formats of the
major cloud, CI/source-hosting, payments, messaging, AI/LLM, package-registry,
observability, database, and developer-tooling providers, plus keyword-anchored
assignment idioms and an entropy fallback.

### Why not a thousand detectors?

Tools such as TruffleHog advertise hundreds of "detectors," but the bulk of
those are **live verifiers** — they recognise a loosely-shaped string and then
call the vendor's API to confirm it is active. MethodAtlas deliberately performs
**no live verification** (its premise is air-gapped, regulated, source-stays-local
operation), so it can only rely on a credential's *intrinsic* shape. The set of
credentials with a distinctive, self-identifying format is the natural ceiling
for deterministic detection, and the bundled catalog targets that set. Breadth
beyond it comes from two places that are already in the box: the keyword and
high-entropy fallback rules, which catch unrecognised secrets by idiom and
randomness, and the [custom catalog](#supplying-your-own-rule-catalog) below.

### LLM triage (optional)

When `-ai` is enabled, MethodAtlas triages each candidate. By default the triage
is **folded into the same per-class call as method classification**, so the class
source is sent to the provider only once for both tasks. The model receives the
class source plus the exact list of detected candidate spans and returns, for
each candidate:

- a **credibility score** in `0.0–1.0` (1.0 = almost certainly a genuine, live
  credential; 0.0 = almost certainly a placeholder, example, or false positive),
- the **endpoint or system** the credential authenticates against — resolved
  even when the credential and its URL are written separately, or the credential
  is passed into a login or connect method, and
- a short **rationale**.

The candidate list is the closed, explicit input: the model scores only the
spans the deterministic engine found and can neither invent nor omit a
credential. A failed or malformed triage response degrades gracefully to
unverified candidates.

A **dedicated** triage call (separate from classification) is used instead when
classification is not running, when a `-secrets-include` glob widens the scan
beyond the discovered test classes, or when `-secrets-separate-llm` is set.

## What gets scanned

Credential detection scans exactly one of two file sets, never a union of both.

**Default — the discovered test classes.** With no `-secrets-include`, the scan
covers the same files the test discovery found (each carrying its fully qualified
class name). Findings are attributed, best-effort, to the enclosing test method.

**`-secrets-include <glob>` — an independent file walk that *replaces* the
default set.** This flag does **not** add to the discovered test classes; it
switches the file selection entirely:

- MethodAtlas walks the scan roots and selects **only** the files whose path
  matches `<glob>` (for example `**/*.java`). Discovered test classes are scanned
  only insofar as they happen to match the glob; nothing is included automatically.
- The glob **overrides** the `-file-suffix` mask — while a glob is active the
  test-file suffixes are ignored, so the glob alone decides what is scanned. This
  is how the scan can reach production source outside the test surface.
- Selected files are matched by path, not by the language discovery plugins, so a
  finding is tied to its **file path** rather than to a discovered FQCN, and triage
  always runs as a **dedicated per-file LLM call** (the folded single-prompt path
  applies only to the default test-class set).

To scan both your test classes *and* extra files, run MethodAtlas twice (once with
the default set, once with `-secrets-include`) and combine the two CSVs, or supply a
glob broad enough to cover every file you want, e.g. `**/*.{java,cs,ts}`.

## Output

`-detect-secrets` always logs a one-line summary plus one line per finding, and
writes a dedicated **secrets CSV** (`-secrets-out`, default
`methodatlas-credentials.csv`). When SARIF output is active, findings are added as
results in the same SARIF document, under a `secret/<rule-id>` rule.

SARIF severity is derived from the credibility score, with the score embedded in
the result **message text** (GitHub Code Scanning does not render the SARIF
`properties` bag):

| Credibility score | SARIF level |
| --- | --- |
| `>= 0.8` | `error` |
| `0.4 – 0.8` | `warning` |
| `< 0.4` | `note` |
| no score (deterministic-only run) | `warning` |

An unverified candidate therefore never drops below review threshold.

## Masking

Secret values are **masked by default** in every output (log, CSV, SARIF):
length is preserved and only the first and last few characters are shown, e.g.
`AKIA••••••••••••MPLE`. `-secrets-show-values` opts into printing raw values for
local triage; the raw value never reaches any output unless that flag is set.

## Privacy and the AI trust boundary

LLM triage transmits the **detected secret value and the surrounding class
source** to the configured AI provider. For a tool whose premise includes
keeping source out of external services, this is a deliberate trust boundary:

- The deterministic layer needs no AI at all — run without `-ai` to keep
  everything local and still get masked candidates.
- When you do want triage, prefer a **local Ollama** model so the secrets and
  source never leave your network.
- Output masking applies regardless of provider, so the report artifacts are not
  themselves a leak vector.

## Supplying your own rule catalog

The bundled catalog ships inside the detector module, but you can replace it
entirely with your own file:

```bash
./methodatlas -detect-secrets -secrets-rules my-rules.yaml /path/to/project
```

or set `secretsRules: my-rules.yaml` in a [YAML config file](../cli-reference.md#-config-file)
and pass it with `-config <file>`. (MethodAtlas only reads a config file when you
name it with `-config`; it does **not** auto-discover a `.methodatlas` file in the
current directory or your home directory.) When `-secrets-rules` is given, the
bundled catalog is **not** loaded — your file is the complete rule set, so copy the
rules you still want.

A catalog is a YAML document with a top-level `rules` array. Each rule:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Unique rule identifier (becomes the SARIF `secret/<id>` rule and the CSV `rule_id`) |
| `category` | yes | One of `PROVIDER_TOKEN`, `PRIVATE_KEY`, `PASSWORD_ASSIGNMENT`, `CONNECTION_STRING`, `HIGH_ENTROPY`, `OTHER` |
| `anchors` | yes (may be empty) | Fixed substrings fed to the Aho-Corasick prefilter; a hit triggers the confirm `pattern`. An **empty** list makes the rule *unanchored* — it runs in the entropy pass instead |
| `pattern` | yes | Java regular expression that confirms the match |
| `entropyMin` | no | Shannon-entropy floor (bits/char) for unanchored rules; `0` uses the detector default |
| `description` | no | Human description |
| `provenance` | no | Note recording where the pattern's format came from |

Two conventions keep extraction correct:

- **Value group.** A rule has **at most one capturing group**, and that group is
  the extracted secret value. A rule with **no** capturing group treats the whole
  match as the value. Use **non-capturing** groups `(?:...)` for alternations you
  do not want extracted — e.g. `A(?:KIA|SIA)[0-9A-Z]{16}` extracts the full key,
  whereas `A(KIA|SIA)…` would wrongly extract just `KIA`.
- **Anchors must be literal substrings of the pattern.** The prefilter only runs
  the confirm regex near an anchor hit, so each anchor must be a fixed string the
  token actually contains (typically its prefix). Anchors are matched
  case-insensitively.

Minimal example:

```yaml
rules:
  - id: acme-deploy-key
    category: PROVIDER_TOKEN
    anchors: ['acme_dk_']
    pattern: 'acme_dk_[0-9A-Za-z]{32}'
    description: 'ACME deploy key'
    provenance: 'ACME docs; transcribed independently'
  - id: company-password-field
    category: PASSWORD_ASSIGNMENT
    anchors: ['dbPassword']
    pattern: 'dbPassword\s*[:=]\s*["'']([^"'']{6,})["'']'
```

Patterns are single-quoted YAML scalars so backslashes stay literal; write a
literal single quote inside one by doubling it (`''`). Every pattern is
compiled at load time, so a malformed regex fails fast with a clear error.

## Customising the LLM prompts

Both the classification prompt and the two credential-triage prompts are templates you
can override with your own files. This is an advanced, audit-sensitive feature: the
SHA-256 of the *effective* template (built-in or overridden) is recorded in the
[reproducibility receipt](../usage-modes/reproducibility-receipts.md), so a custom prompt
is always traceable to the run that used it.

| Flag | YAML key (under `ai:`) | Overrides |
| --- | --- | --- |
| `-classification-prompt <file>` | `classificationPrompt` | the method-classification prompt |
| `-triage-prompt <file>` | `triagePrompt` | the folded credential-triage appendix (combined mode) |
| `-dedicated-triage-prompt <file>` | `dedicatedTriagePrompt` | the standalone credential-triage prompt (separate mode) |

A template is plain text with `{token}` placeholders that MethodAtlas substitutes with
deterministically-derived data before sending the prompt. Substitution is single-pass,
so a value that itself contains a `{token}`-shaped substring (for example a token literal
in the scanned source) is never re-interpreted as a placeholder.

| Template | Required tokens | Also allowed | Must retain |
| --- | --- | --- | --- |
| classification | `{taxonomy}`, `{methods}`, `{expectedMethodNames}`, `{classSource}` | `{fqcn}`, `{confidenceRules}`, `{confidenceField}` | the JSON key `"methods"` |
| triage appendix | `{candidates}` | — | the word `secrets` |
| dedicated triage | `{candidates}`, `{classSource}` | `{fqcn}` | the JSON key `"secrets"` |

Validation checks placeholder correctness (no unknown tokens, every required token
present) and that the JSON anchor the response parser depends on still appears. It
**cannot** verify that a model will understand or follow your wording — that remains the
template author's responsibility.

### Validate before you run

`-check-prompts` validates the templates — the built-in defaults, or any overrides you
pass alongside it — prints each one's SHA-256, and exits. It returns a non-zero status if
any template is invalid, which makes it a useful CI pre-flight gate. (A normal scan applies
the same validation fail-fast at startup, so a malformed template never reaches a provider.)

```bash
methodatlas -check-prompts -classification-prompt my-classification.txt
```

```text
CLASSIFICATION [my-classification.txt]: PASS
  sha256: 6f1c0b9e…
TRIAGE_APPENDIX [built-in default]: PASS
  sha256: 9ab2f4d1…
DEDICATED_TRIAGE [built-in default]: PASS
  sha256: 0d77ac53…

All prompt templates are valid.
```

### Minimal dedicated-triage template

```text
Triage each detected credential candidate as genuine or placeholder.

CANDIDATES
{candidates}

Return JSON only, with a top-level "secrets" array: one entry per candidateIndex,
each {"candidateIndex": <int>, "credibilityScore": <0.0-1.0>, "endpoint": <string-or-null>, "rationale": <string>}.

CLASS {fqcn}
SOURCE
{classSource}
```

Run it as `-detect-secrets -secrets-separate-llm -dedicated-triage-prompt my-triage.txt`.

## Licensing and provenance

The detection patterns encode vendor-published credential formats (for example
the AWS access-key-id shape) transcribed independently; each catalog rule records
its provenance. The Aho-Corasick engine is a clean-room implementation. No
third-party detector catalog or matcher code is copied, so the feature carries no
copyleft obligation and ships under the project's Apache-2.0 licence. Supply your
own catalog with `-secrets-rules <file>`.

## Current limitations

- **Manual-workflow triage** (`-manual-prepare` / `-manual-consume`) is not yet
  wired for credential triage: in manual mode the deterministic candidates are
  still emitted, but without credibility scores. Use local Ollama for scored
  triage in air-gapped settings.
