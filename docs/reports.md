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

Scans runtime dependencies against the NVD vulnerability database. This report is **not generated on every build** — it runs only when the `NVD_API_KEY` repository secret is set, triggered every Monday by the Security scan workflow or on manual dispatch (`./gradlew dependencyCheckAnalyze`).

When it runs, results are uploaded as a GitHub Actions artifact (`dependency-check-report`) retained for 30 days. The CVSS threshold of 7.0 is enforced only during those runs, not on every commit.

To access: go to the **Security scan** workflow run in the Actions tab and download the `dependency-check-report` artifact. If no recent run exists with the artifact, the `NVD_API_KEY` secret may not be configured.

### CycloneDX SBOM

A [CycloneDX](https://cyclonedx.org/) 1.5 software bill of materials listing all runtime dependencies with their versions, licences, and purl identifiers. Generated at release time and attached as `bom.json` to every GitHub Release asset.

To access: go to the [Releases](../../releases) page and download `bom.json` from the relevant release.

### SpotBugs SARIF — GitHub Code Scanning

SpotBugs also emits a SARIF file (`build/reports/spotbugs/main.sarif`) that is uploaded to GitHub Code Scanning on every Monday security scan run. Findings appear in the **Security → Code scanning** tab of the repository and as inline annotations on pull request diffs.

### MethodAtlas self-analysis SARIF

MethodAtlas classifies its own JUnit test methods for security relevance on every push to `main` using GitHub Models (free, no secrets required). The reusable workflow [`methodatlas-analysis.yml`](https://github.com/Accenture/MethodAtlas/blob/main/.github/workflows/methodatlas-analysis.yml) is called from `pages.yml` and uploads the resulting SARIF to GitHub Code Scanning under the `methodatlas` category. Security-relevant test methods surface in the Security tab as a live demonstration of the tool's output on a known codebase.

The workflow is designed to be copied and adapted for other projects. See [CI/CD setup — adapting the workflow](ci-setup.md#adapting-the-methodatlas-analysis-workflow-for-your-own-project) for instructions.

## CI quality gates

| Gate | Tool | Threshold | Scope |
| --- | --- | --- | --- |
| Instruction coverage | JaCoCo | ≥ 70 % | Every push (`./gradlew check`) |
| Mutation score | PIT | ≥ 60 % | Every push (`./gradlew check`) |
| Static analysis | PMD | configured ruleset | Every push |
| Bug patterns | SpotBugs | configured exclusions | Every push |
| Dependency vulnerabilities | OWASP Dependency-Check | CVSS < 7.0 | On demand / weekly — only when `NVD_API_KEY` is set |

See [CI/CD Setup](ci-setup.md) for the full workflow configuration.

## Documentation PDF

A combined single-document PDF of the full documentation set can be built on demand from the `methodatlas-docs` Gradle module. It is **not generated automatically** during the standard build or CI pipeline.

**Prerequisites:** pandoc, XeLaTeX (MikTeX on Windows, TeX Live on Linux/macOS), `mmdc` (`npm install -g @mermaid-js/mermaid-cli`), and Python 3.9+.

```bash
# Windows
.\gradlew :methodatlas-docs:generatePdf

# Linux / macOS
./gradlew :methodatlas-docs:generatePdf
```

Output: `methodatlas-docs/build/MethodAtlas.pdf`

The Mermaid diagrams embedded in the documentation are rendered to PNG before the PDF is assembled. See `docs/publication-order.txt` for the document order and `methodatlas-docs/build.gradle` for all pandoc options.
