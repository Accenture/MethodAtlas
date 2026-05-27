# Contributing to MethodAtlas

Thank you for considering a contribution to MethodAtlas.
This document explains how to set up a development environment, how to submit
changes, and what quality gates every contribution must pass.

MethodAtlas is used in regulated environments — security, auditability, and
backward compatibility matter more here than in a typical open-source project.
Please read this guide fully before opening a pull request.

## Table of contents

- [Code of conduct](#code-of-conduct)
- [Development environment](#development-environment)
- [Project structure](#project-structure)
- [Contribution workflow](#contribution-workflow)
- [Coding standards](#coding-standards)
- [Commit messages](#commit-messages)
- [Pull request checklist](#pull-request-checklist)
- [Security-sensitive changes](#security-sensitive-changes)
- [Licensing](#licensing)

## Code of conduct

This project is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).
By participating you agree to abide by its terms.

## Development environment

Prerequisites:

| Tool | Minimum version |
| --- | --- |
| JDK (Temurin recommended) | 21 |
| Node.js (for TypeScript plugin) | 18 LTS |
| npm | bundled with Node.js |

```bash
# Clone and build everything
git clone https://github.com/Accenture/MethodAtlas.git
cd methodatlas
./gradlew build          # compile, test, PMD, SpotBugs, JaCoCo, PIT

# Run the desktop GUI during development
./gradlew :methodatlas-gui:run

# Build the combined distribution
./gradlew installDist
build/install/methodatlas/bin/methodatlas --help
```

No special environment variables are required for a local build.
Set `NVD_API_KEY` only if you intend to run `./gradlew dependencyCheckAnalyze`.

## Project structure

| Module | Purpose |
| --- | --- |
| `:` (root) | CLI entry point and command-mode orchestration |
| `methodatlas-api` | Public SPI: `TestDiscovery`, `SourcePatcher`, `DiscoveredMethod`, `ScanRecord` |
| `methodatlas-ai` | AI suggestion engine, provider clients, taxonomy, manual workflow |
| `methodatlas-emit` | Output emitters (CSV, plain, SARIF, JSON, GitHub annotations, delta) and audit-schema types (populated in Item 7) |
| `methodatlas-discovery-jvm` | Java / Kotlin test discovery via JavaParser |
| `methodatlas-discovery-dotnet` | C# test discovery via ANTLR grammar |
| `methodatlas-discovery-typescript` | TypeScript test discovery via esbuild bundle |
| `methodatlas-gui` | Swing desktop GUI (FlatLaf, RSyntaxTextArea) |
| `methodatlas-docs` | PDF documentation — not part of the binary distribution |

## Contribution workflow

1. **Open an issue first** for non-trivial features or changes that affect the
   public API or audit output schema.  This avoids wasted effort and lets
   maintainers flag regulatory implications early.

2. **Fork** the repository and create a feature branch:

   ```bash
   git checkout -b feat/short-description
   ```

3. **Develop** on your branch.  Keep commits focused — one logical change per
   commit.

4. **Run the full quality gate locally** before pushing:

   ```bash
   ./gradlew check   # test + coverage + PMD + SpotBugs + PIT mutation gate
   ```

5. **Open a pull request** against `main`.  Fill in the PR template completely.

6. **Address review comments** — do not force-push a branch that is under
   active review; add fixup commits instead.

7. A maintainer will squash-merge once all checks pass and at least one
   approving review is given.

## Coding standards

### Java

- All public API members (interfaces, records, methods) must have Javadoc.
  Stale `@see` / `@link` references are treated as bugs.
- PMD ruleset: `/.ruleset` — zero violations on `pmdMain`.
- SpotBugs: zero violations (filter file: `config/spotbugs/excludeFilter.xml`).
- JaCoCo instruction coverage: enforced per module against a current-state
  floor; root project remains at ≥ 70 %. See
  [`docs/quality-gates.md`](docs/quality-gates.md) for the per-module table
  and the ratchet policy.
- PIT mutation score: enforced per module against a current-state floor;
  root project remains at ≥ 60 %. ANTLR-generated parser packages are
  excluded from mutation. See
  [`docs/quality-gates.md`](docs/quality-gates.md) for the per-module
  table.
- Error Prone is enabled on main sources — fix any reported issues, do not
  suppress without a justification comment.

### Architectural boundaries

The plugin seam is enforced at build time by ArchUnit in
`org.egothor.methodatlas.arch.ArchitectureTest`. Three invariants must hold:

- Non-plugin code (orchestration core, AI subsystem, output emitters, GUI)
  must not declare a compile-time dependency on any class in a
  `org.egothor.methodatlas.discovery.*` package. Plugins are loaded at runtime
  through `ServiceLoader` via the SPI in `methodatlas-api`.
- Each discovery plugin must depend only on `methodatlas-api` and external
  (non-MethodAtlas) libraries. Imports of root, AI, `emit`, or GUI types from
  inside a plugin are rejected.
- Discovery plugins must not depend on each other. Each language plugin is
  independently distributable.

If a legitimate need to widen these rules arises, update the test class in the
same change and explain the rationale in the commit body — these are
load-bearing invariants for the optional-plugin contract.

### TypeScript scanner bundle

- Source: `methodatlas-discovery-typescript/src/main/node/ts-scanner.js`
- The bundle is built by esbuild and embedded in the JAR at compile time.
  Do not commit the generated `ts-scanner.bundle.js`.

### Dependency management

- Pin all dependency versions explicitly; do not use version ranges.
- Run `./gradlew dependencyCheckAnalyze` when adding or upgrading a
  dependency and confirm there are no CVSS ≥ 7 findings.
- ANTLR compile-time JARs (`antlr4`, `ST4`, `icu4j`, etc.) must **not** appear
  in the distribution `lib/` directory — the root `build.gradle` exclusion rule
  enforces this, but verify after any ANTLR version change.

### Audit output schema

The CSV (`DeltaReport` schema) and YAML (`ClassificationOverride` schema)
formats are used by downstream compliance tooling.  **Do not change field
names, types, or ordering** without bumping the schema version and updating
`docs/cli.md`, `docs/audit.md`, and `README.md`.

## Commit messages

Follow the Conventional Commits format:

```
<type>(<scope>): <short imperative description>

[optional body]

[optional footer: BREAKING CHANGE, Fixes #NNN, …]
```

Types: `feat`, `fix`, `build`, `ci`, `docs`, `refactor`, `test`, `chore`

Scope examples: `ai`, `dist`, `gui`, `jvm`, `dotnet`, `typescript`, `api`

Examples:

```
feat(ai): add Mistral provider client
fix(gui): suppress getActiveProfile() from JSON serialisation
build: centralise PMD config in root subprojects block
```

## Pull request checklist

Before requesting a review, confirm that:

- [ ] `./gradlew check` passes locally (all quality gates green)
- [ ] New public API members have Javadoc
- [ ] `README.md` is updated if a user-visible feature was added or changed
- [ ] Relevant documentation under `docs/` is updated
- [ ] No credentials, API keys, or personal data are present in the diff
- [ ] `CHANGELOG` or release notes entry drafted (for features and bug fixes)
- [ ] If the change affects audit-trail output, schema docs are updated

## Security-sensitive changes

Changes to the following areas require an additional security review from a
maintainer before merge:

- `AuditWriter` — any change to what is written to `.methodatlas/*.csv` or
  `overrides.yaml`
- `SourcePatcher` implementations — any change to how source files are modified
- AI provider clients — any change to what is sent to or received from external
  APIs
- The TypeScript scanner bundle — any change to what AST data is extracted
- CLI argument parsing — any change that could affect command injection or
  path traversal

Tag such PRs with the `security-review` label.

## Licensing

By submitting a pull request you certify that:

1. Your contribution is your original work, or you have the right to submit it.
2. Your contribution is licensed under the
   [Apache License 2.0](LICENSE) — the same license as the rest of the project.
3. You have the authority to grant this licence (i.e. your employer's IP policy
   permits open-source contributions to this project).

All source files must carry the standard Apache 2.0 SPDX header:

```java
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
```
