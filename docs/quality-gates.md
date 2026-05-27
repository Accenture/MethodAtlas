# Quality gates

MethodAtlas enforces a layered set of quality gates on every `./gradlew check`
run. Some gates apply project-wide; others have a per-module floor that locks
in each module's current state and ratchets upward over time.

## Per-module JaCoCo instruction coverage

Every Java module enforces its own JaCoCo instruction-coverage floor. The
floor is set to roughly the module's currently measured coverage, with a small
buffer for natural measurement noise. The full mapping lives in the
`ext.coverageFloors` map at the top of the root `build.gradle`.

| Module | Floor | Approximate current coverage | Notes |
| --- | ---: | ---: | --- |
| root (`methodatlas`) | 70 % | 94.3 % | Established threshold; comfortably exceeded after the AI subsystem moved to `methodatlas-ai`. |
| `methodatlas-api` | 40 % | 45.7 % | Small SPI; per-instruction volatility is high. |
| `methodatlas-ai` | 80 % | 84.9 % | AI subsystem; strong test suite. |
| `methodatlas-emit` | 65 % | 67.7 % | Output emitters (CSV / SARIF / JSON / delta / GitHub annotations) and audit-schema types. |
| `methodatlas-gui-core` | 75 % | 76.0 % | Swing-free GUI domain types plus the audit-trail writer. `AuditWriter` itself reaches 99 % line coverage after Item 14 hardening. |
| `methodatlas-discovery-jvm` | 85 % | 90.9 % | Strongest plugin test suite. |
| `methodatlas-discovery-dotnet` | 38 % | 40.3 % | ANTLR-generated parser dominates SLOC. |
| `methodatlas-discovery-typescript` | 17 % | 19.3 % | Most logic in the bundled JS scanner; the Java glue is thin. |
| `methodatlas-discovery-go` | 44 % | 46.6 % | |
| `methodatlas-discovery-python` | 62 % | 65.7 % | |
| `methodatlas-discovery-powershell` | 70 % | 72.9 % | |
| `methodatlas-discovery-abap` | 38 % | 40.0 % | ANTLR-generated parser dominates SLOC. |
| `methodatlas-discovery-cobol` | 30 % | 31.8 % | ANTLR-generated parser dominates SLOC. |
| `methodatlas-gui` | 1 % | 1.3 % | Swing event-loop code only after Items 13/14 moved the audit-trail logic and domain types into `methodatlas-gui-core` (where they reach 75 % overall and 99 % on `AuditWriter` specifically). The view layer here is genuinely thin and benefits little from automated tests; the business logic lives in `methodatlas-gui-core` and is comprehensively covered there. Raising this floor further would require AssertJ-Swing UI tests, which are out of scope for the present remediation plan. |

The floor is enforced by `jacocoTestCoverageVerification` and is wired into
the `check` lifecycle in the root `build.gradle` `subprojects` block. The
module-local task fails the build if instruction coverage drops below the
configured floor.

### Ratchet policy

Floors only go up. When a code change raises a module's coverage well above
its floor (for example, by 5 percentage points or more), raise the floor to
the new lower-bound in the same commit so the gain is locked in. The map in
`build.gradle` is the single source of truth; this document tracks the
rationale and the historical floors.

Lowering a floor is treated as an architectural decision: it must be
justified in the commit body, documented in this file, and approved through
the standard review process. Refactors that legitimately remove tested code
(for example, deleting a dead module) are the typical justification.

## Per-module PIT mutation score

Every Java module runs PIT against its own package and enforces its own
mutation-score floor. The full mapping lives in the `ext.pitConfig` map at
the top of the root `build.gradle`. ANTLR-generated parser and lexer
classes are excluded from mutation because mutating generated code yields
no signal — the grammar, not the parser, is the source of correctness.

| Module | Floor | Approximate current mutation score | Notes |
| --- | ---: | ---: | --- |
| root (`methodatlas`) | 60 % | 72 % | Established threshold; comfortably exceeded today. |
| `methodatlas-api` | 0 % | 0 % | SPI is mostly records and interfaces; few mutations exist. |
| `methodatlas-ai` | 70 % | 74.7 % | AI subsystem; ratcheted after Item 11b record refactor (provider de-duplication via `HttpJsonExecutor`). |
| `methodatlas-emit` | 45 % | 49.1 % | Output emitters and audit-schema types; SARIF/JSON paths well-covered, CSV-parsing edge cases dominate surviving mutants. |
| `methodatlas-gui-core` | 50 % | 56.0 % | Swing-free GUI domain types and `AuditWriter`. |
| `methodatlas-discovery-jvm` | 60 % | 68.6 % | |
| `methodatlas-discovery-dotnet` | 35 % | 41.6 % | Parser package excluded. |
| `methodatlas-discovery-typescript` | 10 % | 15.6 % | Most logic in bundled JS, not mutated by PIT. |
| `methodatlas-discovery-go` | 42 % | 48.0 % | Parser package excluded. |
| `methodatlas-discovery-python` | 30 % | 34.9 % | |
| `methodatlas-discovery-powershell` | 38 % | 43.9 % | Parser package excluded. |
| `methodatlas-discovery-abap` | 38 % | 44.0 % | Parser package excluded. |
| `methodatlas-discovery-cobol` | 33 % | 39.2 % | Parser package excluded. |
| `methodatlas-gui` | 0 % | 1.0 % | Placeholder. Phase 4 ratchets up after audit-trail extraction. |

The floor is enforced by the per-module `pitest` task and is wired into the
`check` lifecycle in the root `build.gradle` `subprojects` block. The
module-local task fails the build if the mutation score drops below the
configured threshold.

The same ratchet policy applies as for JaCoCo: floors only go up. Raising
a floor after a tests-strengthening change should be done in the same
commit, by updating the `threshold` value in `ext.pitConfig` and the table
above. A full PIT run across all modules takes roughly 40 minutes on a
typical developer machine; CI runs PIT under `continue-on-error` for the
Pages build but enforces the gate on `./gradlew check`.

## Other gates

| Gate | Scope | Notes |
| --- | --- | --- |
| PMD ruleset (`/.ruleset`) | All Java modules | Zero violations on `pmdMain`. Configured in the `subprojects` block of root `build.gradle`. |
| SpotBugs | Root main sources | Zero violations. Filter file: `config/spotbugs/excludeFilter.xml`. Test sources excluded. |
| Error Prone | All Java main sources | Suppressions require an inline justification comment. |
| ArchUnit boundary tests | Root test suite | Three plugin-seam invariants; see `org.egothor.methodatlas.arch.ArchitectureTest`. |
| OWASP Dependency-Check | Runtime classpath | On-demand: `./gradlew dependencyCheckAnalyze`. Build fails on CVSS ≥ 7.0. |
| License compliance | Runtime classpath | `./gradlew checkLicense` against `config/allowed-licenses.json`. |

## Running the gates locally

```bash
# Full quality run (test + coverage + PMD + SpotBugs + PIT mutation + ArchUnit)
./gradlew check

# Coverage verification only
./gradlew test jacocoTestCoverageVerification

# Generate per-module HTML coverage reports
./gradlew test jacocoTestReport
# Reports land at <module>/build/reports/jacoco/test/html/index.html
```

## Raising a floor

1. Run `./gradlew test jacocoTestReport`.
2. Inspect the module's HTML report or parse the XML report at
   `<module>/build/reports/jacoco/test/jacocoTestReport.xml`.
3. If the measured coverage exceeds the floor by a comfortable margin
   (typically 5 percentage points or more), update the floor in
   `ext.coverageFloors` in the root `build.gradle` and refresh the table in
   this document in the same commit.
4. Re-run `./gradlew test jacocoTestCoverageVerification` to confirm the new
   floor passes.
