# Regulated Environments

This section provides operational guidance for teams deploying MethodAtlas
in environments subject to formal compliance obligations. It covers which
flags to enable, which artefacts to produce and retain, and how to present
scan output to assessors and auditors.

!!! note "Compliance advice"
    The guidance on this page and its sub-pages is provided for informational
    purposes. Determining which standards apply to your organisation and how
    they must be satisfied requires assessment by a qualified compliance
    professional. Do not rely solely on this documentation as compliance advice.

## Why regulated deployment differs from standard use

In a standard development pipeline, MethodAtlas output is a diagnostic aid:
it surfaces security test coverage gaps early enough to act on them before
release. In a regulated environment, the same output additionally functions
as a **formal evidence artefact** — a timestamped, traceable record that
specific security tests existed, were classified, and were reviewed at a
known point in the software development life cycle.

This distinction has two practical consequences:

1. **Reproducibility.** The scan must produce output that can be correlated
   unambiguously with a specific source revision. The
   [`-content-hash`](../cli-reference.md#-content-hash) flag appends a
   SHA-256 fingerprint of each test class to every record; auditors can use
   this fingerprint to verify that the source has not changed since the scan
   was run.

2. **Provenance.** The scan metadata — timestamp, tool version, scan root —
   must be embedded in or accompany the output. The
   [`-emit-metadata`](../cli-reference.md#-emit-metadata) flag prepends this
   information as comment lines before the CSV header.

## Minimum recommended configuration

The following command produces a scan result suitable as a baseline evidence
artefact for all frameworks covered in this section:

```bash
./methodatlas \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -sarif \
  -security-only \
  -content-hash \
  -emit-metadata \
  src/test/java \
  > security-tests.sarif
```

| Flag               | Purpose in regulated context |
|--------------------|------------------------------|
| `-ai`              | Enables semantic classification; without it, only structural metadata is emitted |
| `-sarif`           | Produces SARIF 2.1.0, the standard exchange format for security findings tools |
| `-security-only`   | Suppresses non-security methods; the output contains only findings relevant to the evidence package |
| `-content-hash`    | Appends a SHA-256 class fingerprint; enables auditors to tie a finding to a specific source revision |
| `-emit-metadata`   | Prepends scan timestamp and tool version to CSV output; retained alongside SARIF for human review |

For environments where outbound network calls to an AI provider are not
permitted, the [Manual AI workflow](../usage-modes/manual.md) produces
the same classified output without any network calls from the scan host.

## AI provider selection

The choice of AI provider affects data governance review requirements.
Only test source files are submitted; no production code, configuration,
or other project content is sent.

| Provider          | Data residency                          | Credentials required    |
|-------------------|-----------------------------------------|-------------------------|
| `ollama`          | Local only — no data leaves the host    | None                    |
| `azure_openai`    | Customer's Azure tenant                 | Azure OpenAI API key    |
| `mistral`         | European Union                          | Mistral AI API key      |
| `openai`          | OpenAI infrastructure (US)              | OpenAI API key          |
| `github_models`   | Microsoft Azure                         | GitHub token            |
| `groq`            | Groq infrastructure (US)               | Groq API key            |

See [AI Providers](../ai/providers.md) for full configuration details.

## Artefact retention

Most frameworks require security testing evidence to be retained for a
defined period. The following artefacts should be archived alongside each
release or scheduled scan:

| Artefact                  | Produced by          | Retain |
|---------------------------|----------------------|--------|
| `security-tests.sarif`    | `-sarif` flag        | Duration required by applicable standard |
| Scan metadata CSV         | `-emit-metadata`     | Same as above |
| Source commit reference   | CI environment variable | Same as above |

The specific retention period depends on the applicable standard. Refer to
the per-standard pages for guidance:

- [PCI-DSS v4.0](pci-dss.md)
- [ISO/IEC 27001:2022](iso-27001.md)
- [NIST SP 800-218 (SSDF)](nist-ssdf.md)
- [DORA](dora.md)
- [SOC 2](soc2.md)

For environments where outbound connections to an AI provider are not permitted,
see [Air-Gapped Deployment](air-gapped.md).

## Onboarding an existing codebase

If you are introducing MethodAtlas to a project that already has a test suite,
see [Onboarding a Brownfield Codebase](onboarding.md) for the recommended
six-phase progression: static inventory → AI classification → override file →
drift detection → annotation write-back → CI gate.
