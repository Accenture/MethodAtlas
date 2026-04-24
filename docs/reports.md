# Reports

The following reports are generated automatically and published alongside this documentation site or attached to GitHub Actions runs and releases.

!!! info "Report availability"
    Reports hosted on GitHub Pages are only available after the first successful CI run on the `main` branch.
    If you see a 404, the pipeline may not have run yet.

## Quality reports on GitHub Pages

<div class="grid cards" markdown>

-   :books: **Javadoc**

    Generated from source Javadoc comments on every push to `main`.

    <a href="../javadoc/index.html" class="md-button">Open Javadoc</a>

-   :microscope: **JaCoCo Coverage**

    Test instruction coverage report. Build gate: ≥ 70 % instruction coverage.

    <a href="../jacoco/index.html" class="md-button">Open Coverage Report</a>

-   :mag: **PMD Static Analysis**

    Static analysis findings from PMD applied to main sources.

    <a href="../pmd/main.html" class="md-button">Open PMD Report</a>

-   :white_check_mark: **Test Results**

    JUnit 5 test execution report showing pass / fail / skip counts.

    <a href="../tests/index.html" class="md-button">Open Test Report</a>

-   :bug: **SpotBugs**

    Bug-pattern detection report. Results are also uploaded to GitHub Code Scanning as SARIF.

    <a href="../spotbugs/main.html" class="md-button">Open SpotBugs Report</a>

-   :dna: **PIT Mutation Testing**

    Mutation testing report showing which mutants were killed and which survived. Build gate: ≥ 60 % mutation score.

    <a href="../pitest/index.html" class="md-button">Open Mutation Report</a>

</div>

## Security and compliance reports

These reports are generated on a weekly schedule or at release time. They are not published to GitHub Pages because they contain dependency data that changes independently of source commits.

### OWASP Dependency-Check

Scans runtime dependencies against the NVD vulnerability database. Runs every Monday. Results are uploaded as a GitHub Actions artifact (`dependency-check-report`) retained for 30 days.

Build gate: CVSS score < 7.0 (requires `NVD_API_KEY` secret to be set).

To access: go to the **Security scan** workflow run in the Actions tab and download the `dependency-check-report` artifact.

### CycloneDX SBOM

A [CycloneDX](https://cyclonedx.org/) 1.5 software bill of materials listing all runtime dependencies with their versions, licences, and purl identifiers. Generated at release time and attached as `bom.json` to every GitHub Release asset.

To access: go to the [Releases](../../releases) page and download `bom.json` from the relevant release.

### SpotBugs SARIF — GitHub Code Scanning

SpotBugs also emits a SARIF file (`build/reports/spotbugs/main.sarif`) that is uploaded to GitHub Code Scanning on every Monday security scan run. Findings appear in the **Security → Code scanning** tab of the repository and as inline annotations on pull request diffs.

### MethodAtlas self-analysis SARIF

When the `OPENROUTER_API_KEY` repository secret is set, MethodAtlas classifies its own JUnit test methods for security relevance (dogfooding). The resulting SARIF is uploaded to GitHub Code Scanning under the `methodatlas` category. This surfaces security-relevant test methods directly in the Security tab as a live demonstration of the tool's output.

## CI quality gates

| Gate | Tool | Threshold | Scope |
| --- | --- | --- | --- |
| Instruction coverage | JaCoCo | ≥ 70 % | Every push (`./gradlew check`) |
| Mutation score | PIT | ≥ 60 % | Every push (`./gradlew check`) |
| Static analysis | PMD | configured ruleset | Every push |
| Bug patterns | SpotBugs | configured exclusions | Every push |
| Dependency vulnerabilities | OWASP Dependency-Check | CVSS < 7.0 | Weekly (requires `NVD_API_KEY`) |

See [CI/CD Setup](ci-setup.md) for the full workflow configuration.
