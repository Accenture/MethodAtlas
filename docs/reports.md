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

    Test instruction coverage report. Build gate: per-module floor (root ≥ 70 %; subprojects see [Quality Gates](quality-gates.md)).

    <a href="../jacoco/index.html" class="md-button">Open Root Coverage</a>
    <a href="../jacoco-modules/index.html" class="md-button">Open Per-Module Coverage</a>

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

!!! note "Task name aliasing"
    The public Gradle task name `cyclonedxBom` is wired in `settings.gradle` to invoke `:cyclonedxDirectBom` internally. Historical CI references rely on this alias — do not rename either task. The artefact path is `build/reports/cyclonedx-direct/bom.json`.

### License compliance (`checkLicense`)

The `./gradlew checkLicense` task enforces that every runtime dependency carries a licence on the allowlist at `config/allowed-licenses.json`. It is part of the standard build gates and fails the build on any non-allowlisted licence — a copyleft licence introduced via a transitive dependency, for instance.

The report file is `build/reports/dependency-license/index.html` and lists every dependency with its declared licence. To add a new licence to the allowlist, edit `config/allowed-licenses.json` and submit a PR — the change is reviewed alongside the dependency that pulled it in.

### Error Prone

[Error Prone](https://errorprone.info/) is applied to main sources only (test sources are excluded to avoid noise from JUnit's assertion idioms). Findings appear as compilation warnings and, for the rules configured as errors, fail the build at `compileJava`. There is no standalone HTML report — findings are part of the compiler output captured by `./gradlew build`.

### SpotBugs SARIF — GitHub Code Scanning

SpotBugs also emits a SARIF file (`build/reports/spotbugs/main.sarif`) that is uploaded to GitHub Code Scanning on every Monday security scan run. Findings appear in the **Security → Code scanning** tab of the repository and as inline annotations on pull request diffs.

### MethodAtlas self-analysis SARIF

MethodAtlas classifies its own JUnit test methods for security relevance on every push to `main` using GitHub Models (free, no secrets required). The reusable workflow [`methodatlas-analysis.yml`](https://github.com/Accenture/MethodAtlas/blob/main/.github/workflows/methodatlas-analysis.yml) is called from `pages.yml` and uploads the resulting SARIF to GitHub Code Scanning under the `methodatlas` category. Security-relevant test methods surface in the Security tab as a live demonstration of the tool's output on a known codebase.

The workflow is designed to be copied and adapted for other projects. See [CI/CD setup — adapting the workflow](ci-setup.md#adapting-the-methodatlas-analysis-workflow-for-your-own-project) for instructions.

### Control-coverage matrix (opt-in)

Produced on demand by `-emit-coverage` together with a user-authored `-coverage-mapping <path>`. The output is a `controls-coverage.json` document mapping every requirement ID in the mapping to the test methods that provide evidence for it, plus a sorted `gaps` array enumerating every uncovered control. The file is direct-import-ready for major GRC platforms; see [Control-Coverage Matrix](usage-modes/control-coverage.md) for the schema and authoring guide.

A reference template that maps the MethodAtlas built-in taxonomy onto ASVS 4.0 is checked in at [`docs/examples/asvs4-mapping.json`](examples/asvs4-mapping.json).

### Reproducibility receipt (opt-in)

Produced on demand by passing `-emit-receipt` to a normal scan. The receipt is a small JSON sidecar that records the SHA-256 fingerprint of every input that influenced the run (override file, taxonomy source, AI cache, AI provider + model, prompt template). It is not generated by the standard CI pipeline because most CI runs would just write the same fingerprints repeatedly; emit it from release builds, compliance evidence pipelines, or whenever an auditor needs to compare two runs.

To access: invoke MethodAtlas with `-emit-receipt` (default destination `methodatlas-receipt.json`) or `-emit-receipt -receipt-file <path>`. See [Reproducibility Receipts](usage-modes/reproducibility-receipts.md) for the full schema and the `configHash` re-derivation algorithm.

## CI quality gates

| Gate | Tool | Threshold | Scope |
| --- | --- | --- | --- |
| Instruction coverage | JaCoCo | Per-module floor (root ≥ 70 %); see [Quality Gates](quality-gates.md) | Every push (`./gradlew check`) |
| Mutation score | PIT | Per-module floor (root ≥ 60 %); see [Quality Gates](quality-gates.md) | Every push (`./gradlew check`) |
| Static analysis | PMD | configured ruleset | Every push |
| Bug patterns | SpotBugs | configured exclusions | Every push |
| Dependency vulnerabilities | OWASP Dependency-Check | CVSS < 7.0 | On demand / weekly — only when `NVD_API_KEY` is set |
| Licence allowlist | `checkLicense` | `config/allowed-licenses.json` | Every push |
| Error-Prone bug patterns | Error Prone | configured ruleset | Every push (main sources) |

See [CI/CD Setup](ci-setup.md) for the full workflow configuration.

## Documentation PDF

A combined single-document PDF of the full documentation set (a book of every page in the documentation, in the order defined by `docs/publication-order.txt`) is produced by the `methodatlas-docs` Gradle module.

The PDF is **not** part of the standard `./gradlew build` lifecycle — it is built on demand. The [Release workflow](https://github.com/Accenture/MethodAtlas/blob/main/.github/workflows/release.yml) invokes it automatically for every `release@x.y.z` tag and uploads the resulting file (`MethodAtlas-<version>.pdf`) alongside the distribution archives and the CycloneDX SBOM.

**Prerequisites for local builds:** pandoc 3+, XeLaTeX, Mermaid CLI (`mmdc`), and Python 3.9+. The Gradle build is platform-aware — it picks `python` versus `python3` automatically, calls `mmdc` or `mmdc.cmd` as appropriate, and discovers a Chromium / Chrome / Edge browser for Mermaid rendering from each OS's standard install locations.

=== "Linux (Debian / Ubuntu)"

    ```bash
    sudo apt-get install -y \
        pandoc \
        texlive-xetex \
        texlive-fonts-recommended texlive-fonts-extra \
        texlive-latex-recommended texlive-latex-extra \
        texlive-plain-generic texlive-pictures \
        lmodern fonts-dejavu

    # texlive-fonts-extra ships Libertinus; refresh fontconfig so xelatex
    # can find it via fontspec. lmodern is loaded unconditionally by
    # pandoc's default LaTeX template. texlive-pictures provides the
    # TikZ libraries that tcolorbox[most] pulls in for admonition boxes.
    sudo fc-cache -f

    npm install -g @mermaid-js/mermaid-cli

    ./gradlew :methodatlas-docs:generatePdf
    ```

=== "Windows (MikTeX)"

    ```powershell
    # MikTeX, pandoc, Node.js, and Python 3 must be on PATH.
    mpm --install=libertinus-fonts --install=libertinus-otf
    mpm --install=dejavu-otf --install=dejavu
    initexmf --update-fndb

    npm install -g @mermaid-js/mermaid-cli

    .\gradlew :methodatlas-docs:generatePdf
    ```

=== "macOS (MacTeX)"

    ```bash
    # MacTeX bundles Libertinus and DejaVu; only pandoc + mmdc are extra.
    brew install pandoc
    npm install -g @mermaid-js/mermaid-cli

    ./gradlew :methodatlas-docs:generatePdf
    ```

The CI release workflow uses the Linux recipe above, so the artefact
shipped on every release is the byte-identical equivalent of running the
Linux commands on a clean Ubuntu host.

Output: `methodatlas-docs/build/MethodAtlas.pdf`

The Mermaid diagrams embedded in the documentation are rendered to PNG before the PDF is assembled. See `docs/publication-order.txt` for the document order and `methodatlas-docs/build.gradle` for all pandoc options.

### Release asset

| Asset | Where to download |
|---|---|
| `MethodAtlas-<version>.pdf` | [GitHub Releases](../../releases) — attached to every `release@*` tag from `2026.05` onward |
| `methodatlas-<version>.zip` / `.tar` | Distribution archives (CLI + GUI sharing one `lib/`) |
| `bom.json` | CycloneDX 1.5 SBOM |
