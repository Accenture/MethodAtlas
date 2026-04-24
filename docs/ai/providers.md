# AI providers

Each provider differs in where requests are sent, what authentication is required, and what data-sovereignty guarantees it offers. This page covers each provider in detail.

## Ollama — local inference

**What it is:** [Ollama](https://ollama.ai/) is an open-source runtime that runs large language models entirely on your local machine. No data leaves the host, no API key is required, and no account is needed.

**Data residency:** Requests are sent to `http://localhost:11434` and never leave the machine. This is the only provider where source code is never transmitted over a network.

**Regulatory perspective:** Fully compliant with any policy that prohibits transmitting source code outside the organization. Suitable for air-gapped environments.

**When to use:** Development workstations, CI runners with local GPUs, environments with strict data-egress policies.

**Credentials:** None required.

**Setup:**

1. [Download and install Ollama](https://ollama.ai/download)
2. Pull a model: `ollama pull qwen2.5-coder:7b`
3. Run MethodAtlas:

```bash
./methodatlas -ai -ai-provider ollama -ai-model qwen2.5-coder:7b /path/to/tests
```

The default model (`qwen2.5-coder:7b`) is suitable for code classification and runs on consumer hardware with 8 GB VRAM, or on CPU.

## Azure OpenAI — corporate cloud inference

**What it is:** [Azure OpenAI Service](https://azure.microsoft.com/en-us/products/ai-services/openai-service) is a managed offering from Microsoft that hosts OpenAI models (GPT-4o, GPT-4, etc.) inside a customer's own Azure subscription. It is distinct from the public OpenAI API: requests go to infrastructure your organization controls, not to OpenAI's shared platform.

**Data residency:** Requests are sent to a resource endpoint within your Azure tenant (e.g. `https://contoso.openai.azure.com`). Data does not leave your Azure subscription boundary. Microsoft processes it under the terms of your enterprise agreement. The EU Data Boundary commitment applies when the resource is provisioned in an EU region.

**Regulatory perspective:** Recommended for organizations subject to GDPR, HIPAA, ISO 27001, or internal policies that prohibit sending source code to third-party cloud services. If your organization already has an Azure subscription, this is usually the most straightforward path to compliant cloud AI.

**When to use:** Corporate environments, regulated industries, any situation where source code must not leave the organization's cloud tenant.

!!! note "Difference from Microsoft 365 Copilot"
    Microsoft 365 Copilot (the AI assistant in Teams, Word, etc.) is a separate
    product and is not accessible via this integration. MethodAtlas uses the
    **Azure OpenAI Service API**, which requires an Azure subscription and a dedicated
    resource provisioned by your IT or cloud team — independent of any M365 licence.

**Credentials:** A resource-scoped API key generated in the Azure portal.

**How to obtain credentials (coordinate with your IT or cloud team):**

1. Provision an **Azure OpenAI resource** in the Azure portal under your subscription. Choose a region close to your users (EU regions satisfy EU Data Boundary requirements).
2. **Deploy a model** within that resource. The deployment gets a name you choose (e.g. `gpt-4o-prod`). This deployment name — not the model family name — is what you supply as `model` in MethodAtlas.
3. Copy the **API key** from *Azure Portal → your resource → Keys and Endpoint*. Two keys are available (Key 1 / Key 2); either works and both can be rotated independently.
4. Copy the **endpoint URL** from the same page (e.g. `https://contoso.openai.azure.com`).

**Configuration:**

```bash
export AZURE_OPENAI_KEY=<your-key>
./methodatlas -ai \
  -ai-provider azure_openai \
  -ai-base-url https://contoso.openai.azure.com \
  -ai-model gpt-4o-prod \
  -ai-api-key-env AZURE_OPENAI_KEY \
  /path/to/tests
```

Or via YAML:

```yaml
ai:
  enabled: true
  provider: azure_openai
  baseUrl: https://contoso.openai.azure.com
  model: gpt-4o-prod          # deployment name, not model family name
  apiKeyEnv: AZURE_OPENAI_KEY
  apiVersion: 2024-02-01      # optional; defaults to 2024-02-01
  timeoutSec: 120
  maxRetries: 2
```

The `apiVersion` field selects the Azure OpenAI REST API version. The default (`2024-02-01`) targets the generally-available Chat Completions API. Use `2024-08-01-preview` to access preview features such as structured outputs.

## OpenAI

**What it is:** The [OpenAI API](https://platform.openai.com/) provides direct access to GPT-4o, GPT-4, and other models hosted on OpenAI's infrastructure in the United States.

**Data residency:** Requests are sent to `https://api.openai.com` and processed on OpenAI's infrastructure. Data leaves the organization's control and is governed by OpenAI's API data usage policy. By default, OpenAI does not use API data to train models, but confirm the current policy with your legal team before use in regulated contexts.

**Regulatory perspective:** Not suitable for environments where source code must not be transmitted to third-party services. Acceptable for teams with explicit approval for external cloud AI usage.

**Credentials:** An OpenAI platform API key.

**How to obtain:**

1. Create an account at [platform.openai.com](https://platform.openai.com/)
2. Go to *API keys → Create new secret key*
3. Add a payment method or enable a usage limit

**Configuration:**

```bash
export OPENAI_API_KEY=sk-...
./methodatlas -ai -ai-provider openai \
  -ai-api-key-env OPENAI_API_KEY \
  -ai-model gpt-4o-mini \
  /path/to/tests
```

## Anthropic

**What it is:** The [Anthropic API](https://www.anthropic.com/) provides access to Claude models hosted on Anthropic's infrastructure in the United States.

**Data residency:** Requests are sent to `https://api.anthropic.com` and processed on Anthropic's infrastructure. Data leaves the organization's control.

**Regulatory perspective:** Same as OpenAI — not suitable where source code must stay within the organization. Acceptable for teams with explicit approval for external cloud AI.

**Credentials:** An Anthropic API key.

**How to obtain:**

1. Create an account at [console.anthropic.com](https://console.anthropic.com/)
2. Go to *API keys → Create key*

**Configuration:**

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./methodatlas -ai -ai-provider anthropic \
  -ai-api-key-env ANTHROPIC_API_KEY \
  -ai-model claude-3-haiku-20240307 \
  /path/to/tests
```

## OpenRouter

**What it is:** [OpenRouter](https://openrouter.ai/) is an API aggregation service that routes requests to multiple underlying AI providers (OpenAI, Anthropic, Google, Meta, Mistral, and others) through a single endpoint using an OpenAI-compatible interface.

**Data residency:** Requests pass through OpenRouter's infrastructure and are forwarded to the underlying model provider. Data leaves the organization's control twice: once to OpenRouter, and again to the downstream provider.

**Regulatory perspective:** Not suitable for environments where source code must not leave the organization. Acceptable for development use where access to many models through one key is convenient.

**Credentials:** An OpenRouter API key.

**How to obtain:**

1. Create an account at [openrouter.ai](https://openrouter.ai/)
2. Go to *Keys → Create key*

**Configuration:**

```bash
export OPENROUTER_API_KEY=sk-or-...
./methodatlas -ai -ai-provider openrouter \
  -ai-api-key-env OPENROUTER_API_KEY \
  -ai-model stepfun/step-3.5-flash:free \
  /path/to/tests
```

## Auto mode

`auto` first probes the local Ollama endpoint (`http://localhost:11434`). If Ollama is reachable, it is used and no data leaves the machine. If Ollama is not available and an API key has been configured, an OpenAI-compatible provider is used instead.

Auto mode is convenient for developer workstations where Ollama is typically running, with a cloud provider as fallback for CI environments that lack a local GPU.

## Configuration file

All AI options can be stored in a YAML configuration file so that teams share the same settings without repeating flags on every invocation:

```yaml
ai:
  enabled: true
  provider: openrouter
  model: stepfun/step-3.5-flash:free
  apiKeyEnv: OPENROUTER_API_KEY
  taxonomyMode: optimized
  timeoutSec: 120
  maxRetries: 2
  confidence: true
```

Load it with `-config`:

```bash
./methodatlas -config ./methodatlas.yaml /path/to/tests
```

Command-line flags always override values from the file:

```bash
# Use the team config but switch to local Ollama for offline work
./methodatlas -config ./methodatlas.yaml -ai-provider ollama -ai-model qwen2.5-coder:7b /path/to/tests
```

See [CLI reference](../cli-reference.md#-config-file) for the complete YAML field reference.
