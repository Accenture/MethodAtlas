# SOC 2

Service Organisation Control 2 (SOC 2) is an auditing standard developed by
the American Institute of Certified Public Accountants (AICPA). It evaluates
an organisation's controls relevant to security, availability, processing
integrity, confidentiality, and privacy — collectively the Trust Services
Criteria (TSC). SOC 2 Type II reports cover a defined observation period
(typically six to twelve months) and are widely required by enterprise
customers, investors, and regulators in the United States.

!!! note "Audit scope"
    The relevance of specific Trust Services Criteria to your SOC 2 engagement
    depends on the scope defined with your auditor. The guidance on this page
    addresses the Change Management criterion (CC8.1), which is in scope for
    virtually all SOC 2 engagements. Consult your auditor to confirm applicability.

## Relevant criterion

**CC8.1 — Change Management** requires that an organisation authorises,
designs, develops, implements, and tests changes to infrastructure, data,
software, and procedures in a manner that maintains the security commitments
made to customers.

For software development organisations, auditors assess CC8.1 by examining
whether security testing is performed before changes are deployed to
production, whether the results of that testing are documented, and whether
the evidence is retained for the observation period.

## Control mapping

| CC8.1 implementation requirement                    | MethodAtlas feature                                                                           | Evidence produced |
|-----------------------------------------------------|-----------------------------------------------------------------------------------------------|-------------------|
| Changes are authorised and tracked                  | Scans are run in CI and tied to specific commits via [`-content-hash`](../cli-reference.md#-content-hash) and the git SHA in the output file name | `content_hash` column in CSV/SARIF; SHA-tagged artefact filenames |
| Security testing is performed before deployment     | CI gate runs MethodAtlas on every pull request targeting the production branch                | Gate job status in CI build log per commit |
| Test results are documented                         | SARIF and CSV outputs provide a structured, machine-readable record per build                 | `security-tests-<sha>.sarif` and `.csv` per release |
| Evidence is retained for the observation period     | Outputs archived as CI artefacts or release assets with `retention-days` ≥ 400               | CI artefact store or release asset attachments |
| Test coverage did not regress                       | [`-diff`](../cli-reference.md#-diff) mode detects removed or reclassified security tests between releases | `delta.txt` artefact per pull request and per release |
| Human review decisions are recorded                 | [`-override-file`](../cli-reference.md#-override-file) in version control provides a tamper-evident record | Override file git history; `override_applied` column in CSV |

## Recommended configuration

**Context:** CC8.1 requires evidence that security testing ran before each
production deployment and that the results are documented. The output file
must be tied to the specific commit that was deployed.

**MethodAtlas capability:** [`-content-hash`](../cli-reference.md#-content-hash)
and [`-emit-metadata`](../cli-reference.md#-emit-metadata) together satisfy
the documentation and traceability requirements.

```bash
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -sarif \
  -security-only \
  -content-hash \
  -emit-metadata \
  src/test/java \
  > security-tests-$(git rev-parse --short HEAD).sarif
```

For human-readable supplementary evidence, produce a CSV alongside the SARIF:

```bash
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash \
  -emit-metadata \
  src/test/java \
  > security-tests-$(git rev-parse --short HEAD).csv
```

**Evidence output:** `security-tests-<sha>.sarif` and
`security-tests-<sha>.csv` — a machine-readable and a human-readable record,
both tied to the specific source revision via the commit SHA in the filename
and the `content_hash` column.

## CI integration for continuous evidence

Configure the pipeline to run MethodAtlas and archive its output on every
merge to the main branch. This produces a continuous evidence trail across
the observation period without manual effort:

```yaml
# GitHub Actions example
- name: Run MethodAtlas
  env:
    AI_API_KEY: ${{ secrets.AI_API_KEY }}
  run: |
    java -jar methodatlas.jar \
      -ai -ai-provider openai -ai-api-key-env AI_API_KEY \
      -sarif -security-only -content-hash -emit-metadata \
      src/test/java \
      > security-tests-${{ github.sha }}.sarif

- uses: actions/upload-artifact@v4
  with:
    name: security-tests-${{ github.sha }}
    path: security-tests-${{ github.sha }}.sarif
    retention-days: 400   # retain beyond the 12-month observation period
```

Setting `retention-days` to at least 400 ensures that artefacts from the
start of an observation period remain available at the time of the audit.

## Regression prevention gate

CC8.1 requires evidence that security testing coverage did not decrease
across the observation period. Implement a count gate on pull requests to
prevent regressions from reaching the main branch:

```bash
baseline=$(tail -n +2 baseline.csv | grep ',true,' | wc -l)
current=$(tail -n +2 current.csv | grep ',true,' | wc -l)

if [ "$current" -lt "$baseline" ]; then
  echo "ERROR: Security test count dropped — CC8.1 regression risk"
  exit 1
fi
```

See [Release Gating](../ci/release-gating.md) for a complete pipeline
implementation including both count-gate and delta-gate patterns.

## Artefact package for the auditor

At the close of each observation period or during an audit, provide the
following materials in response to CC8.1 evidence requests:

| Artefact                           | Content                                                      | Retention |
|------------------------------------|--------------------------------------------------------------|-----------|
| SARIF output per production release | Security test inventory at the time of each release         | Observation period plus 12 months |
| Delta reports between releases     | Evidence that coverage did not regress                       | Same as above |
| CI pipeline logs                   | Proof that MethodAtlas ran as part of the pre-deployment process | Same as above |
| Override file (version-controlled) | Record of all human classification decisions                 | Git history; no expiry |

!!! tip "Auditor presentation"
    Most SOC 2 auditors are not familiar with SARIF. Supplement the SARIF
    artefact with the CSV output, which is readable in a spreadsheet and
    provides an immediately interpretable view of the security test inventory.
    The [Guide for Security Teams](../concepts/for-security-teams.md) contains
    an executive summary template suitable for inclusion in an evidence package.

## Further reading

- [AICPA — Trust Services Criteria](https://www.aicpa-cima.com/resources/landing/trust-services-criteria)
- [AICPA — SOC 2 overview](https://www.aicpa-cima.com/topic/audit-assurance/audit-and-assurance-greater-than-soc-2)
- [MethodAtlas — Guide for Security Teams](../concepts/for-security-teams.md)
- [MethodAtlas — Release Gating](../ci/release-gating.md)
- [MethodAtlas — Output Formats](../output-formats.md)
