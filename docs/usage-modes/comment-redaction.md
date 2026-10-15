# Comment redaction (`-redact-comments`) — user guide

## What it does, in one sentence

With `-redact-comments`, MethodAtlas **removes every comment from your source before it is
sent to an AI provider** — so personal data (PII) that lives in comments (names, emails,
ticket numbers, phone numbers, "left by X on date Y", commented-out credentials, …) never
leaves your machine.

## Why you'd use it

The AI classification step sends the text of a test class to the configured AI provider for
context. Code comments frequently contain personal data that developers cannot practically
strip from the source. This flag blanks those comments **in the copy sent to the AI only**,
without changing your files.

## How it works (the important guarantees)

- **Comments become spaces.** Each comment's characters are replaced by spaces, and **every
  line break is kept**. The file stays the exact same length, so **line and column numbers
  are unchanged**.
- **Only the AI copy is affected.** Your source files on disk are **not** modified. Test
  discovery (which methods exist, on which lines) and the `-apply-tags` write-back both use
  the **original** source — so annotations still land on the correct lines and your comments
  stay intact on disk.
- **String-aware.** A `//`, `/*`, `#` etc. that appears *inside a string literal* is left
  alone — redaction uses each language's real parser, not a blind text search.
- **Fail-closed.** If any file to be sent belongs to a language MethodAtlas cannot fully
  redact, **the whole run aborts before anything is sent to the provider** (non-zero exit,
  with a message naming the file and language). You never get "most of it redacted" — you
  get all of it, or a safe stop.

## Turning it on

Command line (either spelling works):

```bash
methodatlas -ai -redact-comments /path/to/tests
methodatlas -ai -ai-redact-comments /path/to/tests     # alias
```

YAML config (shared across a team / CI):

```yaml
ai:
  redactComments: true
```

The CLI flag wins over YAML. Without `-ai` (or `-manual-prepare`) nothing is sent to a
provider, so the flag has no effect.

## Which languages are supported

| Language | `-redact-comments` |
| --- | --- |
| Java | ✅ redacted (Javadoc `/** */`, block `/* */`, line `//`, inline) |
| C# | ✅ redacted (`//`, `///`, `/* */`) |
| Go | ✅ redacted (`//`, `/* */`) |
| PowerShell | ✅ redacted (`#`, `<# #>`) |
| TypeScript / JavaScript | ✅ redacted (`//`, `/* */`, JSDoc) — **requires Node.js 18+** |
| Python | ✅ redacted (`#`) — **requires Python 3.8+** |
| SAP ABAP | ⛔ **fail-closed** (not yet supported — a run aborts) |
| COBOL | ⛔ **fail-closed** (not yet supported — a run aborts) |

> **Fail-closed means safe.** Scanning ABAP/COBOL (or any unsupported language) *with*
> `-redact-comments` deliberately **aborts** rather than risk sending comments. Scan those
> languages without the flag, or scan them separately.

## What is NOT redacted (by design)

- **String literals** (including C# verbatim/interpolated strings, PowerShell here-strings,
  TS template literals) — they're code, not comments.
- **Python docstrings** — these are string literals, not comments. If your docstrings may
  hold PII, do not rely on this flag for them.
- **Directives** — C# `#region`/`#if`/`#pragma` keywords and PowerShell `#requires` lines
  are kept as directives (their free-text *prose*, e.g. a `#error "message"`, is blanked).

## Works everywhere the source is sent

The redaction (and the fail-closed abort) applies to **all** the AI paths:

- classification (`-ai`),
- source write-back suggestions (`-apply-tags` with AI),
- the two-phase air-gapped workflow (`-manual-prepare` writes redacted prompt files),
- **credential triage** (`-detect-secrets` with AI): a secret that MethodAtlas finds *inside
  a comment* is still **reported in your local secrets report**, but its value is **not sent
  to the AI** for triage.

## One thing to know about reproducibility

When `-redact-comments` is on, the per-class **`content_hash`** is computed over the
*redacted* text (what was actually sent). Two consequences:

- a comment-only edit no longer changes the hash, so `-diff`/drift won't flag it;
- the same tree scanned **with** vs **without** the flag produces different `content_hash`
  values.

The reproducibility receipt (`-emit-receipt`) records whether the flag was on (schema v3),
so an auditor can see the AI input was comment-redacted.

## Quick examples

```bash
# Local Ollama + redaction (nothing leaves your network anyway, but comments are blanked too)
methodatlas -ai -ai-provider ollama -redact-comments src/test/java

# Hosted provider, redaction on, SARIF out
methodatlas -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -redact-comments -sarif src/test/java > results.sarif

# Apply AI-suggested tags with redaction — your comments stay on disk, annotations land correctly
methodatlas -ai -redact-comments -apply-tags src/test/java

# Air-gapped: produce redacted prompt files to carry to an AI workstation
methodatlas -manual-prepare -redact-comments src/test/java
```

## How to confirm it's working

- Put a unique marker in a comment (e.g. `// REDACT-ME-12345`) and run with
  `-emit-receipt`/your provider's request logging: the marker should not appear in what was
  sent.
- Point it at an ABAP/COBOL file with `-redact-comments` and confirm it **aborts** with a
  message naming the file — that's the fail-closed guarantee.

## Requirements recap

- Node.js 18+ on `PATH` to redact TypeScript/JavaScript; Python 3.8+ to redact Python. If the
  runtime is missing, that language becomes fail-closed (the run aborts) and a warning names
  the missing runtime.
- No API key or network is required for the redaction itself — it runs locally before
  anything is sent.
