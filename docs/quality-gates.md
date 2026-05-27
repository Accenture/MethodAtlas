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
| root (`methodatlas`) | 70 % | 91.7 % | Established threshold; comfortably exceeded today. |
| `methodatlas-api` | 40 % | 45.7 % | Small SPI; per-instruction volatility is high. |
| `methodatlas-discovery-jvm` | 85 % | 90.9 % | Strongest plugin test suite. |
| `methodatlas-discovery-dotnet` | 38 % | 40.3 % | ANTLR-generated parser dominates SLOC. |
| `methodatlas-discovery-typescript` | 17 % | 19.3 % | Most logic in the bundled JS scanner; the Java glue is thin. |
| `methodatlas-discovery-go` | 44 % | 46.6 % | |
| `methodatlas-discovery-python` | 62 % | 65.7 % | |
| `methodatlas-discovery-powershell` | 70 % | 72.9 % | |
| `methodatlas-discovery-abap` | 38 % | 40.0 % | ANTLR-generated parser dominates SLOC. |
| `methodatlas-discovery-cobol` | 30 % | 31.8 % | ANTLR-generated parser dominates SLOC. |
| `methodatlas-gui` | 1 % | 1.1 % | Placeholder. Phase 4 of the architecture remediation plan extracts the audit-trail logic into `methodatlas-gui-core` and raises this substantially. |

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

## Project-wide PIT mutation gate

The root project enforces a 60 % PIT mutation-score floor on classes in
`org.egothor.methodatlas.*`. Per-module PIT floors will be added in a
follow-up commit (see Item 2b of the architecture remediation plan) once a
baseline measurement is taken for each Java subproject.

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
