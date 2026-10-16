# The Six Layers in MethodAtlas

This page walks the SABSA stack as it is actually populated in the MethodAtlas library —
what each layer holds, and the kinds of element that live there. For the layer model itself
see the [SABSA overview](index.md); for the complete element register see the full
[MethodAtlas SABSA Architecture](architecture.md).

Throughout, elements use stable IDs — **BD** driver, **RO** risk/opportunity, **BA**
attribute, **CO** control objective, **DM** domain, **TR** trust relationship, **AD**
decision, **SS** logical service, **PL** policy, **SM** mechanism, **ST** standard, **SC**
component, **ME** measure, **EV** evidence, **EX** exception, **RM** roadmap.

---

## 1 · Contextual — Business view

*What the business is, and why it needs security.*

The asset the architecture protects is **the trustworthiness of MethodAtlas's own output
and of the development process that produces it** — the product's reason to exist for a
regulated-industry audience. That need is captured as **eleven business drivers**
(BD‑001…BD‑011): output that is traceable, repeatable and defensible enough to stand as
audit evidence (BD‑001); deterministic, reproducible structural results (BD‑002); bounded,
auditable AI classification (BD‑003); the closed security taxonomy (BD‑004) and the
audit/evidence schemas (BD‑005) as stable contracts; build and distribution integrity
(BD‑006); data minimisation toward external AI providers (BD‑007); confidentiality of
human-review and override decisions (BD‑008); secure-development discipline and coordinated
disclosure (BD‑009); credibility for regulated and air-gapped adoption (BD‑010); and a
deliberate, proportionate self-asserted trust position — published hashes, not managed-key
signing (BD‑011). Fifteen risks and opportunities (RO‑001…RO‑015) sit beside them.

## 2 · Conceptual — Architect's view

*What "secure" must achieve — expressed as measurable attributes.*

This layer turns the drivers into **fourteen measurable business attributes**
(BA‑001…BA‑014) — Authentic, Reproducible, Bounded, Taxonomy‑Stable, Schema‑Stable,
Supply‑Chain‑Intact, Data‑Minimising, Confidential, Verifiably‑Tested, Supportable,
Credible, Transparent, Audit‑Trail‑Intact and AI‑Result‑Reproducible — each with a
definition, a metric, a measurement method and a target. Alongside the profile sit
**fourteen control objectives** (CO‑001…CO‑014), **eight security domains** (DM) and their
**eight trust relationships** (TR), and **eleven recorded architecture decisions** (AD) — for
example the decision to keep the structural layer deterministic and the AI layer bounded,
and the decision to rely on published hashes and keyless provenance rather than managed-key
signing.

## 3 · Logical — Designer's view

*The security services and information flows that deliver the concept.*

The information to protect, and the one boundary that classifies it, is the
**data-governance rule on what may leave the scan host**. Everything structural is always
local — source traversal, parsing, discovery, content-hash computation, output, caching and
delta compare make no network call under any configuration. Only when `-ai` is used with a
non-local provider are exactly three things submitted per test class: the taxonomy text; the
discovered method names and line numbers (which prevent the AI inventing or omitting a
method); and the single, truncated test-class source file. Production source, config,
credentials, directory structure and other test files are **never** transmitted. This
minimal egress set is realised by **thirteen logical services** (SS) governed by the
layer's **policies** (PL).

!!! note "See also"
    [Data Governance](../concepts/data-governance.md) · [AI Providers and tiers](../ai/providers.md)

## 4 · Physical — Builder's view

*The mechanisms and data structures that implement the design.*

The design is realised by **seventeen mechanisms** (SM) and the trust-relevant stores that
hold the evidence: the **AI result cache** (keyed by SHA‑256 content hash, giving
reproducibility for unchanged classes); the **classification-override store**
(human-reviewed corrections, confidence pinned to 1.0/0.0); the **reproducibility receipts**
(schema v2, recording the SHA‑256 of every input, including per-template prompt checksums);
the **attestation manifest** (each security test bound to its content hash and the commit
SHA); and the GUI `.methodatlas/` **audit trail** (an append-only evidence CSV plus the
cumulative `overrides.yaml`). The architecture is careful to state which artefacts carry a
cryptographic binding and which are append-only-but-not-hash-bound — a distinction it states
rather than overstates.

!!! note "See also"
    [Reproducibility Receipts](../usage-modes/reproducibility-receipts.md) · [Audit Trail](../audit-trail.md) · [Result Caching](../ai/caching.md)

## 5 · Component — Tradesman's view

*The concrete products, standards and formats that make up the mechanisms.*

This layer names the detailed, security-relevant formats as **twelve standards**
(ST‑001…ST‑012) realised by **twenty components** (SC‑001…SC‑020): the DeltaReport CSV
columns and ClassificationOverride YAML fields (the frozen audit-schema surface consumed by
downstream compliance tooling); the reproducibility-receipt schema v2; the attestation
manifest; SARIF 2.1.0 for SAST-pipeline integration; the CycloneDX 1.5 SBOM; and SHA‑256 as
the single hashing format throughout. These are the stable contracts that let other tools —
and auditors — rely on MethodAtlas output.

!!! note "See also"
    [Output Formats](../output-formats.md) · [Discovery Plugins](../discovery-plugins.md) · [Supply-Chain Security](../deployment/supply-chain.md)

## 6 · Service Management — Service manager's view

*How security is operated, measured, supported and kept current.*

For an open-source tool there is no always-on runtime to fail over; operational continuity
of security means that **every build and release reproduces the trust evidence intact**.
That continuity is assured by **fourteen evidence artefacts** (EV‑001…EV‑014) measured by
**fourteen measures** (ME) — the scanner-script hash-check and abort-on-mismatch test, the
reproducibility receipt and determinism test, the AI-bounding reconciliation, the
supply-chain gate reports and SBOM, the data-governance payload test, the override- and
credential-masking tests, the JaCoCo and PIT reports, the vulnerability-disclosure SLA
record, and the audit-trail integrity and AI-result reproducibility regression results.
Regenerating these on every build is what keeps the assurance continuous. **Seven
operations** (SO), **eleven exceptions** (EX — explicit, owner-accepted, time-boxed residual
risks) and an **eight-item roadmap** (RM) complete the layer.

!!! note "See also"
    [Quality Gates](../quality-gates.md) · [Build Reports](../reports.md)

---

## Reading the whole thing together

The six layers are one argument, not six documents. A reviewer who wants to challenge a
single mechanism can follow it up the [golden thread](index.md#the-golden-thread-traceability)
to the business driver that justifies it; a reader who wants to know whether a business need
is met can follow it *down* to the evidence that is regenerated on every build. That
two-way traceability — and the owner-approved, hash-bound snapshot behind it — is what makes
the architecture auditable rather than merely descriptive.
