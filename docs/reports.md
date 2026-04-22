# Reports

The following reports are generated automatically on every push to `main` and published to GitHub Pages alongside this documentation site.

!!! info "Report availability"
    Reports are only available after the first successful CI run on the `main` branch.
    If you see a 404, the pipeline may not have run yet.

<div class="grid cards" markdown>

-   :books: **Javadoc**

    ---

    Full API reference documentation generated from source Javadoc comments.

    <a href="../javadoc/index.html" class="md-button">Open Javadoc</a>

-   :microscope: **JaCoCo Coverage**

    ---

    Test code coverage report. Build gate requires ≥ 70 % instruction coverage.

    <a href="../jacoco/index.html" class="md-button">Open Coverage Report</a>

-   :mag: **PMD Static Analysis**

    ---

    Static analysis findings from PMD applied to main sources.

    <a href="../pmd/main.html" class="md-button">Open PMD Report</a>

-   :white_check_mark: **Test Results**

    ---

    JUnit 5 test execution report showing pass / fail / skip counts.

    <a href="../tests/index.html" class="md-button">Open Test Report</a>

-   :bug: **SpotBugs**

    ---

    Bug-pattern detection report. Results are also uploaded to GitHub Code Scanning as SARIF.

    <a href="../spotbugs/main.html" class="md-button">Open SpotBugs Report</a>

</div>

## CI quality gates

| Gate | Tool | Threshold | Enforced in |
|------|------|-----------|-------------|
| Instruction coverage | JaCoCo | ≥ 70 % | `./gradlew check` |
| CVSS vulnerability score | OWASP Dependency-Check | < 7.0 | `./gradlew dependencyCheckAnalyze` |
| Static analysis | PMD | configured ruleset | `./gradlew pmdMain` |
| Bug patterns | SpotBugs | configured exclusions | `./gradlew spotbugsMain` |

OWASP Dependency-Check and mutation testing (PIT) run on a separate schedule and are not blocking gates on every commit. See [CI/CD Setup](ci-setup.md) for details.
