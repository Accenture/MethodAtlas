# Data Governance

This page describes precisely what data MethodAtlas processes, what is and
is not submitted to external AI providers, and how to configure the tool to
meet specific data governance requirements.

## Data processed locally (always)

The following operations are performed on the scan host and involve no
external communication under any configuration:

| Operation | Data involved |
|---|---|
| Source file traversal | File paths and names within the scan root |
| Source file parsing (Java, C#, TypeScript) | Test source file content — in memory only |
| Method discovery | Parsed AST nodes — in memory only |
| Content hash computation | SHA-256 of the AST string — no content transmitted |
| CSV / SARIF / plain-text output | Result data written to stdout or a local file |
| Cache read and write | Local CSV file read and written on the scan host |
| Override file processing | Local YAML file read on the scan host |
| Delta report (`-diff`) | Two local CSV files compared in memory |

None of the above steps initiate any network connection.

## Data submitted to external AI providers

When AI enrichment is enabled with [`-ai`](../cli-reference.md#-ai) and a non-local provider is
configured, MethodAtlas submits one HTTPS request per test class to the
provider's inference API. Each request contains exactly:

1. **The taxonomy text** — either the built-in taxonomy or the content of
   the file supplied with [`-ai-taxonomy`](../cli-reference.md#-ai-taxonomy). This is configuration data
   describing tag definitions; it contains no project-specific content.

2. **The list of test method names** — the exact set of JUnit methods
   discovered by the parser in that class, with their source line numbers.
   This list is included to prevent the AI from inventing or omitting
   methods; only methods the parser found are classified.

3. **The test class source file** — the full text of one test source file
   (Java, C#, or TypeScript) from the scan root, used as semantic context for
   classification. The file is truncated to the character limit set by
   [`-ai-max-class-chars`](../cli-reference.md#-ai-max-class-chars)
   (default: 40 000 characters) before transmission. The class name and all
   method names are always included; if the class body exceeds the limit, the
   trailing lines of the file are omitted.

**In concrete terms**, a single request to the AI provider contains:

- The class name (e.g. `com.example.AuthServiceTest`)
- All test method names found in that class (e.g. `loginWithExpiredToken`, `loginWithValidCredentials`)
- The full source text of that class file, up to `ai-max-class-chars` characters

Nothing else from the project is included.

**What is never submitted:**

| Data category | Included in AI request |
|---|---|
| Production source code | No |
| Build scripts (`pom.xml`, `build.gradle`) | No |
| Configuration files (`.properties`, `.yaml`, environment files) | No |
| Credentials, secrets, or API keys | No |
| Database schemas or migration scripts | No |
| Infrastructure definitions (Terraform, Kubernetes manifests) | No |
| Other test files submitted as context | No — each class is submitted independently |
| File paths or directory structure | No — only the class source text and method names are included; the absolute path on disk is not transmitted |

The AI provider receives the text of one test class (Java, C#, or TypeScript) at a time. No
information about the surrounding project structure, the production
implementation, or any other file is included.

## Provider data processing policies

Each provider processes submitted data according to its own terms of service
and data processing agreement. The following links point to the relevant
policy documents at the time of writing; verify the current version before
approving a provider for use:

| Provider | Data processing policy |
|---|---|
| OpenAI | [openai.com/policies/api-data-usage-policies](https://openai.com/policies/api-data-usage-policies) |
| Anthropic (Claude) | [anthropic.com/legal/privacy](https://www.anthropic.com/legal/privacy) |
| Azure OpenAI | [learn.microsoft.com — Azure OpenAI data privacy](https://learn.microsoft.com/en-us/legal/cognitive-services/openai/data-privacy) |
| Mistral AI | [mistral.ai/terms](https://mistral.ai/terms) |
| Groq | [groq.com/privacy-policy](https://groq.com/privacy-policy/) |
| xAI | [x.ai/legal/privacy-policy](https://x.ai/legal/privacy-policy) |
| GitHub Models | [docs.github.com — GitHub Models usage](https://docs.github.com/en/github-models) |
| Ollama (local) | No data leaves the host |

!!! warning "Policy changes"
    Provider data processing policies change over time. Review the linked
    documents and consult your legal or privacy team before approving a
    provider for scanning test code that may contain project-specific logic.

## GDPR considerations

Test source files typically do not contain personal data. However, test files
that use hard-coded real names, email addresses, or other personal data as
test fixtures do contain personal data and are subject to GDPR data minimisation
and transfer requirements when submitted to a hosted AI provider.

**Recommended practices:**

- Use randomised or obviously synthetic test data in test fixtures
  (e.g. `alice@example.com`, `1970-01-01`) rather than real personal data.
- If test files contain unavoidable personal data and must be scanned, use
  either the Ollama local provider (no data leaves the host) or the
  [Manual AI workflow](../usage-modes/manual.md) (no outbound connections
  from the scan host).
- For EU-to-US transfers: Azure OpenAI with a European region endpoint
  processes data within the EU; Mistral AI is operated from the European
  Union. Both are suitable for organisations with EU data residency requirements.

## Data residency

MethodAtlas supports three data-residency tiers. Choose the tier that matches your organisation's data governance requirements.

### Tier 1 — data never leaves the machine

Configure [`-ai-provider ollama`](../cli-reference.md#-ai-provider) with [`-ai-base-url`](../cli-reference.md#-ai-base-url) pointing to an Ollama
server on the local host or internal network. All AI inference runs on your
infrastructure. No API key is required. No outbound connection is made.

This is the appropriate choice for: air-gapped environments, strict DLP policies,
or any scenario where no test source code may leave the organisation's network.

See [Air-Gapped and Offline Deployment](../deployment/air-gapped.md) for setup instructions.

### Tier 2 — operator controls what leaves the network (manual workflow)

Use [`-manual-prepare`](../cli-reference.md#-manual-prepare) to produce prompt files on the scan host (no network required),
then carry those files to an authorised workstation with internet access, interact
with the AI chat interface there, and return the responses to the scan host for
[`-manual-consume`](../cli-reference.md#-manual-consume). The operator decides exactly which prompts — and therefore which
class sources — are submitted to the AI provider, and when.

This is the appropriate choice for: regulated environments with supervised AI
access, teams that require human sign-off before any data leaves the network, or
pipelines where the scan host has no internet connectivity at all.

See [Manual AI Workflow](../usage-modes/manual.md) for the complete procedure.

### Tier 3 — data is processed by a cloud AI provider

When a hosted provider is configured, class source files are transmitted to that
provider's inference API over HTTPS. The provider's data processing policies govern
what is retained and for how long.

| Provider | Data residency |
|---|---|
| `ollama` | Fully local — data never leaves the host |
| `azure_openai` | Customer's chosen Azure region; EU regions available |
| `mistral` | European Union |
| `openai` | OpenAI infrastructure (US) |
| `github_models` | Microsoft Azure infrastructure |
| `groq` | Groq infrastructure (US) |
| `xai` | xAI infrastructure (US) |

For organisations with strict EU data residency requirements, `ollama`,
`azure_openai` (with a EU region endpoint), and `mistral` are the appropriate
choices.

## Comment redaction (`-redact-comments`)

Source comments often carry personal data (author names, e-mail addresses, ticket references to named individuals), internal hostnames or remarks that are not needed to classify a test. `-redact-comments` is a data-minimisation control: before a class source is sent to an AI provider, or written to a `-manual-prepare` work file, every comment is replaced by spaces. Newlines and the exact character count are preserved, so line numbers and offsets are identical.

- **Fail-closed.** If a scanned file has no comment redactor for its language, or the redactor cannot guarantee redaction, the run aborts with a non-zero exit code before the first provider call of the scan root (and before any manual-prepare work file is written). With several scan roots this is per scan root: root N can abort after root N-1 already produced its redacted output, but no un-redacted source leaks. There is no partial or best-effort mode. Every redactor result is additionally verified (same length, line terminators untouched, only blanking); a redactor that throws or misbehaves aborts the run.
- **What is redacted.** Comments only. String literals, and Python docstrings (which are string literals), are not comments and are not redacted; do not rely on this control to remove sensitive data embedded in code or strings.
- **Scope.** Only the AI payload changes. Source files on disk and `-apply-tags` write-back are never modified by redaction.
- **Language support.** Java is supported (JavaParser-based: Javadoc, block and line comments, including inline and orphan comments, are blanked; string literals and text blocks are never touched). C# (single-line, XML-doc and delimited comments), Go (line and general comments) and PowerShell (line and block comments, including comment-based help) are supported by redactors built on the same ANTLR lexers that discover their tests; string literals, verbatim, interpolated, raw and here-strings are never touched. C# preprocessor directives keep their keyword and operands (`#if`, `#pragma`, `#define`, the file name of `#line`), but the free text of `#region`/`#endregion` and of `#error`/`#warning` is blanked; a `//` comment trailing a directive is blanked. The PowerShell `#requires` line is kept only if it matches the strict `#requires` grammar, otherwise it is an ordinary comment and is blanked; a `#` inside a `$( ... )` sub-expression of a PowerShell expandable string is rejected (fail closed). Each of these redactors fails closed on anything the lexer cannot classify with certainty (for example an unterminated string or block comment). TypeScript/JavaScript (`//` line, `/* */` block and JSDoc comments, plus a leading `#!` hashbang line) and Python (`#` comments) are supported by redactors that ask the scanner subprocess of the respective plugin for the comment positions: the bundled `@typescript-eslint/typescript-estree` parser for TypeScript/JavaScript and the standard-library `tokenize` module for Python (Python's `ast` discards comments). Because a real parser or tokenizer classifies the text, comment markers inside string literals, TypeScript/JavaScript template literals (including `${...}` substitutions), regular-expression literals and JSX text, and a `#` inside a Python string or f-string, are never touched. **Python docstrings are string literals, not comments, and are deliberately not redacted.** Both redactors need the same runtime as discovery (Node.js 18 or later; Python 3.8 or later): when it is missing the redactor does not claim the file and the run fails closed, exactly as for a language without a redactor. They also fail closed on source that does not parse or tokenize (for example an unterminated string, template or block comment, or an inconsistent Python dedent) and when re-parsing the result still finds a comment. When the Node.js or Python runtime is missing, a WARNING naming it is logged and the run aborts. ABAP and COBOL deliberately have no redactor and always fail closed, so a tree containing them cannot be scanned with `-redact-comments` and AI or manual-prepare enabled.
- **Credential detection.** The local credential report (CSV/SARIF, no AI) still scans the full original source and reports secrets inside comments. With `-redact-comments`, the source sent to the AI for triage is redacted, and candidates located inside comments are not sent to the AI for triage (their values never enter the prompt): secrets detected inside comments are still reported locally but are NOT sent to the AI for triage. This holds for both the folded (per-class) and the separate (`-secrets-separate-llm`/`-secrets-include`) triage calls.
- **GUI limitation.** The Swing GUI does not support `-redact-comments`: its analysis service sends class source to the AI provider without redaction, and the flag has no effect there. Use the CLI when comment redaction is required.
- **Receipt.** The reproducibility receipt records `inputs.redactComments` (schema v3) and folds it into `configHash`, so an auditor can see that the AI input was comment-redacted.
- **Content hash.** The content hash (`-content-hash`) and the AI cache key are computed on the redacted text, so the hash reflects exactly what was sent. Hashes and cache entries from runs with and without `-redact-comments` are therefore different and are not interchangeable.

See the [CLI reference](../cli-reference.md#-redact-comments).

## DLP-compatible deployment

Data Loss Prevention (DLP) controls that block or inspect outbound traffic are fully compatible with MethodAtlas. Use Tier 1 (Ollama) or Tier 2 (manual workflow) from the data residency options above — neither configuration initiates any outbound connection from the scan host.

For Tier 3 (cloud providers), AI calls can be routed through an internal proxy or HTTPS inspection point; configure the proxy via standard environment variables (`HTTPS_PROXY`, `NO_PROXY`) before invoking MethodAtlas.

See [Air-Gapped and Offline Deployment](../deployment/air-gapped.md) for
complete implementation guidance.

## Auditing outbound AI calls

In environments that log or inspect outbound HTTPS traffic, MethodAtlas
AI calls can be identified by the following characteristics:

| Characteristic | Value |
|---|---|
| Destination | The provider's API base URL (see [AI Providers](../ai/providers.md)) |
| HTTP method | `POST` |
| Request frequency | One request per test class; multiple classes may be processed in rapid succession |
| Request size | Proportional to the test class source length; bounded by `-ai-max-class-chars` (default 40 000 characters) |
| Content type | `application/json` |

Enable logging of request URLs and sizes at the network proxy or firewall
level to produce an audit trail of what was submitted and when.

## Enterprise secret management

The `-ai-api-key-env <name>` flag reads the API key from a named environment variable. This is the recommended approach for CI pipelines (where the secret is stored in the CI secret store and injected as an environment variable at runtime), but it may not satisfy security policies in environments where environment variables are prohibited as a secret delivery mechanism (e.g. some PCI-DSS or CyberArk-governed workloads).

For deployments with stricter secret management requirements, the following patterns are available:

**HashiCorp Vault / AWS Secrets Manager / Azure Key Vault:** retrieve the API key before invoking MethodAtlas and pass it via the environment variable pattern:

```bash
# Vault example (adjust for your auth method)
export MY_API_KEY=$(vault kv get -field=api_key secret/methodatlas/openai)
./methodatlas -ai -ai-provider openai -ai-api-key-env MY_API_KEY src/test/java
unset MY_API_KEY
```

The `unset` immediately after the run limits the variable's lifetime to the single invocation. In containerised CI environments the variable is scoped to the container process and is not visible outside it.

**File-based secret delivery (CyberArk Conjur, Kubernetes Secrets mounted as files):** read the key from the file and export it to the environment immediately before the MethodAtlas call. Avoid writing the key to any persistent storage.

**Ollama / Manual workflow (no API key required):** for the strictest zero-secret requirement, use local Ollama inference or the [Manual AI Workflow](../usage-modes/manual.md). Neither approach requires an API key on the scan host.

## Further reading

- [AI Providers](../ai/providers.md) — provider configuration and base URLs
- [Air-Gapped and Offline Deployment](../deployment/air-gapped.md) — zero-egress deployment
- [Manual AI Workflow](../usage-modes/manual.md) — classification without network access from scan host
- [Compliance & Standards](../compliance.md) — framework-specific evidence requirements
