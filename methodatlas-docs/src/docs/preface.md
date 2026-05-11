# Preface {.unnumbered .unlisted}

MethodAtlas is an open-source command-line tool that turns a test suite into a structured, AI-enriched security inventory. It bridges the gap between "we have tests" and "we can demonstrate which tests cover which security controls" — a distinction that matters every time a team faces an external security review, a regulatory audit, or a certification assessment.

The tool is deliberately split into two independent phases. The first phase is deterministic: it parses source files using language-specific abstract syntax trees and emits one record per discovered test method. No inference is involved; the structural inventory is exact and reproducible. The second phase is optional: when AI enrichment is enabled, each test class is submitted to a configured language model, which classifies methods against a closed security taxonomy and, optionally, scores the depth of each test's assertions. Because the structural discovery is separate from the semantic classification, teams in air-gapped or policy-restricted environments can use the first phase alone, or supply AI responses through a manual file-based workflow without any direct model API access.

## What this reference covers {.unnumbered .unlisted}

This volume compiles the complete MethodAtlas documentation in reading order. The chapters are grouped as follows.

**Getting started** (Chapters 1–2) introduces the rationale and covers installation and the quick-start workflow.

**Reference** (Chapters 3–6) documents the command-line interface in full, with one section per flag, a complete YAML configuration schema, all output formats with annotated examples, and per-language plugin configuration for Java, C#, and TypeScript.

**Usage modes** (Chapters 7–15) walks through every operating mode in depth: static inventory, AI-enriched scans, the manual AI workflow for restricted environments, source file write-back, CSV-driven annotation campaigns, delta reports between scan runs, security-only filtering, and multi-root monorepo scanning.

**AI enrichment** (Chapters 16–25) covers provider setup for all ten supported AI platforms, the security taxonomy and its customisation, confidence scoring, the interaction-score placebo-test detector, classification overrides, AI result caching, and tag-versus-AI drift detection.

**CI/CD integration** (Chapters 26–31) provides complete pipeline definitions for GitHub Actions, GitLab CI, and Azure Pipelines, with patterns for monorepo builds and security-gate release blocking.

**Concepts** (Chapters 32–36) explains MethodAtlas from a security-team perspective, covers data governance and residency, compares the tool with traditional SAST, maps findings to the OWASP Application Security Verification Standard (ASVS), and gives concrete remediation guidance for common finding types.

**Compliance and regulated environments** (Chapters 37–45) maps MethodAtlas features to control requirements in OWASP SAMM, NIST SSDF, ISO/IEC 27001, DORA, PCI DSS v4.0, and SOC 2, and covers the six-phase onboarding progression for brownfield codebases and air-gapped deployment.

**Migration and support** (Chapters 46–48) documents breaking changes across major version boundaries, troubleshooting guidance for common failure modes, and the published reports inventory.

## Intended audiences {.unnumbered .unlisted}

This reference is written for four overlapping audiences.

*Developers* integrating MethodAtlas into a build pipeline will spend most of their time in the Reference and Usage Modes sections.

*Security engineers* who need to understand the classification model, evaluate AI confidence scores, or tune the taxonomy will find their material in the AI Enrichment section.

*DevSecOps engineers* configuring CI/CD pipelines will find complete, ready-to-use workflow files in the CI/CD Integration section.

*Compliance officers and auditors* who need to map tool outputs to specific framework controls will find framework-aligned mapping tables in every chapter of the Compliance section.

## Conventions used in this reference {.unnumbered .unlisted}

Code listings, command-line examples, file paths, and configuration keys are set in `monospace type`. Output produced by MethodAtlas is shown in full, with line wrapping indicated by a small hook symbol (↪) at the right margin.

**Bold text** in prose identifies flag names, field names, and UI elements. *Italic text* introduces new terms and titles.

Cross-references to other chapters are shown as hyperlinks in the digital edition and as chapter and section numbers in print.

All command-line examples assume the tool binary is on the system `PATH` or invoked from the `bin/` directory of the distribution archive. On Windows, substitute `.\methodatlas.bat` for `./methodatlas` throughout.

\clearpage
