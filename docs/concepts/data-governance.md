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
| Java parsing (JavaParser) | Test source file content — in memory only |
| Method discovery | Parsed AST nodes — in memory only |
| Content hash computation | SHA-256 of the AST string — no content transmitted |
| CSV / SARIF / plain-text output | Result data written to stdout or a local file |
| Cache read and write | Local CSV file read and written on the scan host |
| Override file processing | Local YAML file read on the scan host |
| Delta report (`-diff`) | Two local CSV files compared in memory |

None of the above steps initiate any network connection.

## Data submitted to external AI providers

When AI enrichment is enabled with `-ai` and a non-local provider is
configured, MethodAtlas submits one HTTP request per test class to the
provider's inference API. Each request contains exactly:

1. **The taxonomy text** — either the built-in taxonomy or the content of
   the file supplied with `-ai-taxonomy`. This is configuration data
   describing tag definitions; it contains no project-specific content.

2. **The test class source file** — the full text of one Java source file
   from the scan root.

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
| File paths or directory structure | No — the prompt contains only class source |

The AI provider receives the text of one Java test class at a time. No
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

| Provider | Data residency option |
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

## DLP-compatible deployment

Organisations operating Data Loss Prevention (DLP) controls on outbound
traffic can deploy MethodAtlas in one of two DLP-compatible configurations:

**Configuration A — local inference:**
Configure `-ai-provider ollama` with `-ai-base-url` pointing to an Ollama
server on the internal network. All AI inference traffic remains within the
organisation's network boundary. No DLP rule needs to allow MethodAtlas
traffic.

**Configuration B — manual workflow:**
Use `-manual-prepare` to produce prompt files locally, carry them to an
authorised workstation outside the DLP boundary, and use `-manual-consume`
to produce output back on the controlled host. Zero outbound connections
originate from the scan host.

See [Air-Gapped and Offline Deployment](../deployment/air-gapped.md) for
complete implementation guidance for both configurations.

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

## Further reading

- [AI Providers](../ai/providers.md) — provider configuration and base URLs
- [Air-Gapped and Offline Deployment](../deployment/air-gapped.md) — zero-egress deployment
- [Manual AI Workflow](../usage-modes/manual.md) — classification without network access from scan host
- [Compliance & Standards](../compliance.md) — framework-specific evidence requirements
