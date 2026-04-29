# AI Enrichment overview

MethodAtlas can optionally send each parsed test class to an AI provider for security classification, producing per-method confidence scores, security tags, interaction scores, and human-readable rationales that go beyond what static analysis alone can determine.

## When to use AI enrichment

Enable AI enrichment when you need semantic understanding of test intent — for example, when distinguishing a test that genuinely verifies SQL-injection prevention from one that merely exercises the same code path without checking the outcome. Pure static analysis and code-coverage tools cannot make this distinction; AI can.

If your scan environment prohibits outbound network access, use the [manual AI workflow](../usage-modes/manual.md) or the [Ollama provider](providers.md#ollama--local-inference) to keep all data on-premises.

## How it works

When `-ai` is enabled, MethodAtlas:

1. **Scans and parses** source files deterministically — no AI involved at this stage.
2. **Builds a prompt** for each test class containing the security taxonomy, the list of JUnit test method names, and the complete class source.
3. **Sends the prompt** to the configured provider.
4. **Merges the response** into the emitted output — one AI suggestion per test method.

The model is explicitly instructed to classify only the methods that the parser found. It cannot invent or skip methods. This keeps the analysis deterministic at the structural level while benefiting from AI for semantic interpretation.

If classification fails for a class (network error, timeout, malformed response), MethodAtlas logs a warning and continues. The affected class appears in the output with empty AI columns.

!!! info "What is transmitted to the provider"
    **Only test source files are submitted to the configured AI provider.**
    MethodAtlas does not read, access, or transmit production source code,
    compiled artefacts, configuration files, environment variables, or any
    other project content outside the configured scan roots.

    The provider receives exactly three items per test class:

    - The security taxonomy that governs the permitted classification tags.
    - The list of test method names identified by the parser.
    - The content of the test class source file.

    For environments where transmitting source code to an external service is
    not permitted, the [manual AI workflow](../usage-modes/manual.md) performs
    the AI interaction through a supervised interface without any outbound API
    calls from the scan host.

## Choosing a provider

The table below summarises the key properties of each supported provider. The [Providers](providers.md) page covers each one in detail, including how to obtain credentials.

| Provider          | Config value     | Where data goes                    | Leaves org control? | Authentication           |
|-------------------|------------------|------------------------------------|---------------------|--------------------------|
| Ollama (local)    | `ollama`         | Local machine only                 | No                  | None                     |
| Azure OpenAI      | `azure_openai`   | Your Azure tenant                  | No¹                 | Resource API key         |
| OpenAI            | `openai`         | OpenAI servers (US)                | Yes                 | API key                  |
| Anthropic         | `anthropic`      | Anthropic servers (US)             | Yes                 | API key                  |
| OpenRouter        | `openrouter`     | OpenRouter + downstream            | Yes                 | API key                  |
| Groq              | `groq`           | Groq servers (US)                  | Yes                 | API key                  |
| xAI               | `xai`            | xAI servers (US)                   | Yes                 | API key                  |
| GitHub Models     | `github_models`  | Microsoft Azure (via GitHub)       | Yes                 | `GITHUB_TOKEN`           |
| Mistral AI        | `mistral`        | Mistral servers (EU)               | Yes²                | API key                  |
| Auto              | `auto`           | Ollama first, then cloud fallback  | Depends             | Depends                  |

¹ Data stays within the Azure subscription you control. Microsoft processes it under your enterprise agreement, not as a public service.

² Mistral processes data in the EU; this may satisfy GDPR-based restrictions on cross-border transfers. Confirm with your legal team.

!!! warning "Auto mode and data residency"
    When using `auto`, the provider selected at runtime determines where data goes.
    If Ollama is absent and a cloud API key is configured, source code will be
    transmitted to the cloud provider. Use an explicit provider value in
    configurations where data residency must be guaranteed.

## Quick decision guide

| Situation                                                       | Recommended provider                              |
|-----------------------------------------------------------------|---------------------------------------------------|
| Source code must not leave your infrastructure                  | **Ollama** or **Azure OpenAI**                    |
| Corporate laptop with Azure subscription                        | **Azure OpenAI**                                  |
| EU data residency required, no on-premises GPU                  | **Mistral AI**                                    |
| Personal project or team with accepted cloud risk               | **OpenAI**, **Anthropic**, or **OpenRouter**      |
| Try it with zero setup                                          | **Ollama** with a locally installed model         |
| Open-source CI with no separate API budget                      | **GitHub Models** (free with a GitHub account)    |
| Low-latency CI with free-tier access                            | **Groq**                                          |
