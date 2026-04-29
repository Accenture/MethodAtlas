# Output formats

MethodAtlas supports four report modes — **CSV** (default), **plain text**, **SARIF**, and **GitHub Actions annotations** — plus a write-back mode (**`-apply-tags`**) that modifies source files directly instead of emitting a report.  
All report modes produce one record per discovered test method.

## CSV mode

CSV mode is the default. It produces a header row followed by one data row per test method.

### Without AI enrichment

```text
fqcn,method,loc,tags,display_name
com.acme.tests.SampleOneTest,alpha,8,fast;crypto,
com.acme.tests.SampleOneTest,beta,6,param,
com.acme.tests.SampleOneTest,gamma,4,nested1;nested2,
com.acme.other.AnotherTest,delta,3,,
```

Multiple JUnit `@Tag` values are joined with `;`. An empty `tags` field means the method has no source-level tags. The `display_name` field contains any `@DisplayName` annotation value declared on the method; it is empty when the annotation is absent.

### With content hash (`-content-hash`)

Pass `-content-hash` to append a SHA-256 fingerprint column immediately after `tags`:

```text
fqcn,method,loc,tags,display_name,content_hash
com.acme.tests.SampleOneTest,alpha,8,fast;crypto,,3a7f9b2e...
com.acme.tests.SampleOneTest,beta,6,param,,3a7f9b2e...
com.acme.other.AnotherTest,delta,3,,,f1c04a8d...
```

The hash is a 64-character lowercase hexadecimal string (SHA-256). It is computed from the AST text of the enclosing class, so all test methods in the same class share the same value. The hash changes only when the class body changes, not when unrelated files in the same package change.

### With AI enrichment (`-ai`)

```text
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score
com.acme.tests.SampleOneTest,alpha,8,fast;crypto,,true,"SECURITY: crypto - validates encrypted happy path",security;crypto,The test exercises a crypto-related security property.,0.0
com.acme.tests.SampleOneTest,beta,6,param,,false,,,,0.2
```

Fields `ai_display_name`, `ai_tags`, and `ai_reason` are empty for non-security-relevant methods. `ai_interaction_score` is always present when AI is enabled — see [Interaction Score](ai/interaction-score.md) for its meaning and use in CI gates.

When `-content-hash` is combined with `-ai`, the `content_hash` column appears between `display_name` and `ai_security_relevant`:

```text
fqcn,method,loc,tags,display_name,content_hash,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score
```

### With AI enrichment and confidence scoring (`-ai -ai-confidence`)

```text
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score,ai_confidence
com.acme.tests.SampleOneTest,alpha,8,fast;crypto,,true,"SECURITY: crypto - validates encrypted happy path",security;crypto,The test exercises a crypto-related security property.,0.0,0.9
com.acme.tests.SampleOneTest,beta,6,param,,false,,,,0.2,0.0
```

`ai_confidence` is `0.0` for methods classified as not security-relevant. `ai_interaction_score` is always present when AI is enabled.

### With drift detection (`-ai -drift-detect`)

Pass `-drift-detect` alongside `-ai` to append a `tag_ai_drift` column at the end of each row:

```text
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score,tag_ai_drift
com.acme.tests.SampleOneTest,alpha,8,security;crypto,,true,"SECURITY: crypto - ...",security;crypto,The test exercises...,0.0,none
com.acme.tests.SampleOneTest,beta,6,,,true,"SECURITY: auth - ...",security;auth,Verifies auth...,0.1,ai-only
com.acme.tests.SampleOneTest,gamma,4,security,,,,,0.0,tag-only
```

| `tag_ai_drift` value | Meaning |
|---|---|
| `none` | Source annotation and AI classification agree |
| `ai-only` | AI classifies as security-relevant; no `@Tag("security")` in source |
| `tag-only` | `@Tag("security")` present in source; AI does not classify as security-relevant |

When `-ai-confidence` is also set, `ai_confidence` appears between `ai_interaction_score` and `tag_ai_drift`.

### With source root (`-emit-source-root`)

Pass `-emit-source-root` when scanning multiple roots where the same fully qualified class name can appear under different source trees. The flag appends a `source_root` column immediately after `display_name` (before `content_hash` and AI columns):

```text
fqcn,method,loc,tags,display_name,source_root
com.acme.auth.AuthTest,testLogin,12,security,,module-a/src/test/java/
com.acme.auth.AuthTest,testLogout,8,,,module-b/src/test/java/
```

The column value is the CWD-relative path of the scan root with a trailing `/`. When a scan root is the current working directory itself, the column is empty. The column is absent from the header and all rows when the flag is not set, so downstream scripts that do not need it are unaffected.

The flag can be combined with all other column flags. When combined with `-content-hash` and `-ai`:

```text
fqcn,method,loc,tags,display_name,source_root,content_hash,ai_security_relevant,...
```

See [Multi-root and monorepo scanning](usage-modes/multi-root.md) for a detailed walkthrough and a CI pipeline example.

### Metadata header

Pass `-emit-metadata` to prepend `# key: value` comment lines before the CSV header:

```text
# tool_version: 1.2.0
# scan_timestamp: 2025-04-09T10:15:30Z
# taxonomy: built-in/default
fqcn,method,loc,tags,...
```

Standard CSV parsers treat `#`-prefixed lines as comments and skip them. The lines help historical output files remain interpretable when compared over time.

## Plain mode

Enable plain mode with `-plain`:

```bash
./methodatlas -plain /path/to/project
```

Plain mode renders one human-readable line per method:

```text
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto, DISPLAY=-
com.acme.tests.SampleOneTest, beta, LOC=6, TAGS=param, DISPLAY=-
com.acme.tests.SampleOneTest, gamma, LOC=4, TAGS=nested1;nested2, DISPLAY=-
com.acme.other.AnotherTest, delta, LOC=3, TAGS=-, DISPLAY=-
```

`TAGS=-` is printed when a method has no source-level JUnit tags. `DISPLAY=-` is printed when the method has no `@DisplayName` annotation; when the annotation is present its value is printed verbatim.

### Plain mode with content hash

When `-content-hash` is also passed, a `HASH=<value>` token is appended after `DISPLAY`:

```text
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto, DISPLAY=-, HASH=3a7f9b2e...
com.acme.tests.SampleOneTest, beta, LOC=6, TAGS=param, DISPLAY=-, HASH=3a7f9b2e...
com.acme.other.AnotherTest, delta, LOC=3, TAGS=-, DISPLAY=-, HASH=f1c04a8d...
```

### Plain mode with AI enrichment

```text
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto, DISPLAY=-, AI_SECURITY=true, AI_DISPLAY=SECURITY: crypto - validates encrypted happy path, AI_TAGS=security;crypto, AI_REASON=The test exercises a crypto-related security property., AI_INTERACTION_SCORE=0.0
com.acme.tests.SampleOneTest, beta, LOC=6, TAGS=param, DISPLAY=-, AI_SECURITY=false, AI_DISPLAY=-, AI_TAGS=-, AI_REASON=-, AI_INTERACTION_SCORE=0.2
```

Absent AI values are printed as `-` in plain mode. `AI_INTERACTION_SCORE` is always present when AI is enabled.

### Plain mode with confidence scoring

When `-ai-confidence` is also passed, an `AI_CONFIDENCE` token is appended after `AI_INTERACTION_SCORE`:

```text
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto, DISPLAY=-, AI_SECURITY=true, AI_DISPLAY=SECURITY: crypto - validates encrypted happy path, AI_TAGS=security;crypto, AI_REASON=The test exercises a crypto-related security property., AI_INTERACTION_SCORE=0.0, AI_CONFIDENCE=0.9
```

### Plain mode with source root (`-emit-source-root`)

When `-emit-source-root` is passed, a `SRCROOT=` token is appended after `DISPLAY` (before `HASH` when that flag is also set):

```text
com.acme.auth.AuthTest, testLogin, LOC=12, TAGS=security, DISPLAY=-, SRCROOT=module-a/src/test/java/
com.acme.auth.AuthTest, testLogout, LOC=8, TAGS=-, DISPLAY=-, SRCROOT=module-b/src/test/java/
```

When the scan root is the current working directory itself, `SRCROOT=-` is printed.

## SARIF mode

Enable SARIF mode with `-sarif`:

```bash
./methodatlas -sarif /path/to/project
./methodatlas -ai -sarif /path/to/project
```

MethodAtlas buffers all discovered test methods and, after the scan completes, emits a single [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html) JSON document to standard output. The document contains one SARIF run with one result per test method.

### Result levels

The SARIF `level` field distinguishes security-relevant methods from ordinary test methods:

| Level | Condition |
| --- | --- |
| `note` | The AI classified the method as security-relevant, or the method carries `@DisplayName("")` |
| `none` | All other test methods (no AI, or AI returned `securityRelevant=false`) |

### Result messages

Each SARIF result carries a `message.text` field that stands alone as a complete, human-readable annotation. The design goal is that an operator should be able to act on a finding without leaving the SARIF viewer or opening the raw JSON.

#### Why scores appear in the message text by default

SARIF results have two parallel channels for data:

- **`message.text`** — the visible annotation shown in every SARIF-aware tool (GitHub Code Scanning, Azure DevOps, SonarQube, IDE plugins, and custom viewers alike).
- **`properties` bag** — structured key/value metadata attached to each result. Whether this bag is surfaced in the UI depends entirely on the consuming tool.

**GitHub Code Scanning does not display the `properties` bag.** When a SARIF file is uploaded to GitHub, the Security tab shows only the `message.text` field as the inline annotation on the code. The `properties` values (interaction score, confidence, tags, reason) are stored in GitHub's database and accessible via the API, but they are not shown in the finding panel that an operator reviews during triage.

Because of this, MethodAtlas embeds the interaction score and, when `-ai-confidence` is active, the confidence percentage directly in the `message.text` by default. An operator reviewing findings in GitHub sees the score immediately in the annotation without needing to download and inspect the raw SARIF JSON.

**For SARIF viewers that do surface the `properties` bag** (custom tooling, enterprise SAST dashboards, etc.), the scores would appear twice — once in the message and once in the structured properties. In that case, pass `-sarif-omit-scores` to suppress the inline embedding and keep the message clean. See [`-sarif-omit-scores`](cli-reference.md#-sarif-omit-scores) for details.

#### Message examples

**Security-relevant method** (`security/<tag>` or `security-test`) — states the AI's suggested `@DisplayName` and `@Tag` values, the reasoning, and the interaction score. When `-ai-confidence` is active the confidence percentage follows. When the score is ≥ 0.8 an additional sentence points to the `security-test/placebo` finding.

Default (no `-ai-confidence`):
```
AI suggests: @DisplayName("SECURITY: auth - verify API key absence") @Tag("security") @Tag("auth"). Reason: The test verifies that requests without a valid API key are rejected with 401. Interaction score: 0.12.
```

With `-ai-confidence` and a low interaction score:
```
AI suggests: @DisplayName("SECURITY: auth - verify API key absence") @Tag("security") @Tag("auth"). Reason: The test verifies that requests without a valid API key are rejected with 401. Interaction score: 0.12. Confidence: 88%.
```

With `-ai-confidence` and a high interaction score (≥ 0.8):
```
AI suggests: @DisplayName("SECURITY: auth - verify API key absence") @Tag("security") @Tag("auth"). Reason: The test verifies that requests without a valid API key are rejected with 401. Interaction score: 0.90. Confidence: 88%. Assertions primarily verify method calls, not actual outcomes. See the security-test/placebo finding for remediation guidance.
```

With `-sarif-omit-scores` (scores suppressed from message):
```
AI suggests: @DisplayName("SECURITY: auth - verify API key absence") @Tag("security") @Tag("auth"). Reason: The test verifies that requests without a valid API key are rejected with 401.
```

**`security-test/placebo`** — a separate result emitted alongside the primary security finding when `ai_interaction_score >= 0.8`. The message always states the actual score and the threshold it was compared against, because those values are the core content of this finding, not supplementary context. The interaction score is never suppressed from this message even when `-sarif-omit-scores` is active.

```
Interaction score: 0.90 (threshold: 0.8). This security test only verifies that methods were called, not what values they returned or what state they produced. Tests that do not assert outcomes cannot catch regressions in security-critical logic. Add assertions on return values, thrown exceptions, or observable state changes.
```

**`annotation/empty-display-name`** — names the class and method, explains the audit impact, and states the corrective action:
```
@DisplayName("") on com.acme.util.HelperTest.testHelper is explicitly empty — the test will appear unnamed in CI reports and audit evidence packages. Replace with a meaningful description, e.g. @DisplayName("Verifies that ...").
```

### Rule IDs

Rules are derived automatically from the AI tags present in the results:

| Rule ID | Level | Meaning |
| --- | --- | --- |
| `test-method` | `none` | Default rule for all non-security test methods |
| `security/<tag>` | `note` | One rule per specific security category (e.g. `security/auth`, `security/crypto`) |
| `security-test` | `note` | Security-relevant method carrying only the umbrella `security` tag |
| `annotation/empty-display-name` | `note` | Method carries `@DisplayName("")` — an empty display name that causes the test to appear unnamed in reports, obscuring the audit trail |
| `security-test/placebo` | `warning` | Security-relevant method whose `ai_interaction_score` is ≥ 0.8 — the test asserts only method calls, not return values or state; may not catch outcome regressions |

The `annotation/empty-display-name` rule applies to any test method (security-relevant or not) that declares `@DisplayName("")`. Because an empty display name hides tests from report views, it is treated as a low-severity quality finding that affects auditability.

The `security-test/placebo` rule identifies security tests that are at risk of being ineffective. A security test with `ai_interaction_score >= 0.8` has assertions that primarily verify whether methods were called (e.g. Mockito `verify()`, spy call counts) rather than what values they returned or what state they produced. Such tests may give false confidence: the code under test could return wrong data or corrupt application state and the test would still pass. This finding is emitted as a second SARIF result alongside the primary security-relevant result, and uses level `warning` and `security-severity: 6.0` (Medium) so that GitHub Code Scanning surfaces it distinctly from informational security-test inventory entries.

### Locations

Each result carries both a physical and a logical location:

- **Physical location** — artifact URI derived from the scan root and the FQCN, producing a path relative to the repository root (e.g. `src/test/java/com/acme/LoginTest.java`). MethodAtlas computes this by combining the scan root path (relative to the current working directory) with the FQCN-derived path, using the same algorithm as the GitHub Annotations emitter. GitHub Code Scanning uses this path to resolve the inline annotation position in the PR diff.
- **Logical location** — the fully qualified method name (e.g. `com.acme.LoginTest.testLoginWithValidCredentials`) with kind `member`

### Properties bag

AI enrichment fields are stored in the result `properties` object when AI is enabled:

| Property | Description |
| --- | --- |
| `loc` | Inclusive line count of the method declaration |
| `contentHash` | SHA-256 fingerprint of the enclosing class (64-char lowercase hex), or omitted when `-content-hash` was not passed |
| `sourceTags` | Semicolon-separated JUnit `@Tag` values from the source, or omitted when none |
| `aiSecurityRelevant` | Boolean AI classification, or omitted when AI is disabled |
| `aiDisplayName` | Suggested `@DisplayName` text, or omitted |
| `aiTags` | Semicolon-separated security taxonomy tags, or omitted |
| `aiReason` | Explanatory rationale from the AI, or omitted |
| `aiInteractionScore` | Interaction score `0.0–1.0`; present whenever AI is enabled |
| `aiConfidence` | Confidence score `0.0–1.0`, or omitted when `-ai-confidence` was not passed |

Properties with `null` or absent values are omitted from the JSON output entirely.

The `properties` bag is the machine-readable counterpart of the result message. It exposes every AI-enrichment field in a structured form suitable for API queries, custom dashboards, and policy automation. Whether a given SARIF viewer surfaces this bag in its UI is tool-dependent. See [Result messages — Why scores appear in the message text by default](#why-scores-appear-in-the-message-text-by-default) for an explanation of how MethodAtlas handles this difference and how to control it.

### Example output

```json
{
  "$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
  "version": "2.1.0",
  "runs": [
    {
      "tool": {
        "driver": {
          "name": "MethodAtlas",
          "version": "1.3.0",
          "rules": [
            {
              "id": "test-method",
              "name": "TestMethod",
              "shortDescription": { "text": "JUnit test method" },
              "properties": { "tags": ["test"] },
              "help": { "text": "MethodAtlas inventories all JUnit test methods found in the scanned source tree. No action required." }
            },
            {
              "id": "security/auth",
              "name": "SecurityAuth",
              "shortDescription": { "text": "Security test: auth" },
              "properties": { "tags": ["security", "auth"] },
              "help": { "text": "MethodAtlas detected this test method as security-relevant via AI analysis. Review the suggested @DisplayName and @Tag values in the result message. If correct, apply them by running: ./methodatlas -ai -apply-tags SOURCE_ROOT." }
            }
          ]
        }
      },
      "results": [
        {
          "ruleId": "test-method",
          "level": "none",
          "message": { "text": "com.acme.tests.SampleOneTest.testCountItems" },
          "locations": [
            {
              "physicalLocation": {
                "artifactLocation": {
                  "uri": "src/test/java/com/acme/tests/SampleOneTest.java"
                },
                "region": { "startLine": 14 }
              },
              "logicalLocations": [
                {
                  "fullyQualifiedName": "com.acme.tests.SampleOneTest.testCountItems",
                  "kind": "member"
                }
              ]
            }
          ],
          "properties": {
            "loc": 3,
            "contentHash": "3a7f9b2e4c1d8f5a0e2b6c9d7f4a1e8b3c5d2f7a0b4e9c6d1f8a3b5e2c7d4f9"
          }
        },
        {
          "ruleId": "security/auth",
          "level": "note",
          "message": { "text": "AI suggests: @DisplayName(\"SECURITY: auth - validates session token after login\") @Tag(\"security\") @Tag(\"auth\"). Reason: Verifies that a valid session token is issued after successful login." },
          "locations": [
            {
              "physicalLocation": {
                "artifactLocation": {
                  "uri": "src/test/java/com/acme/security/LoginTest.java"
                },
                "region": { "startLine": 22 }
              },
              "logicalLocations": [
                {
                  "fullyQualifiedName": "com.acme.security.LoginTest.testLoginWithValidCredentials",
                  "kind": "member"
                }
              ]
            }
          ],
          "properties": {
            "loc": 8,
            "aiSecurityRelevant": true,
            "aiDisplayName": "SECURITY: auth - validates session token after login",
            "aiTags": "security;auth",
            "aiReason": "Verifies that a valid session token is issued after successful login.",
            "aiConfidence": 0.95
          }
        }
      ]
    }
  ]
}
```

### Integrating SARIF output

SARIF is natively supported by many static-analysis platforms and IDEs:

- **GitHub Advanced Security** — upload via the `upload-sarif` action to surface findings in the Security tab
- **VS Code** — the [SARIF Viewer](https://marketplace.visualstudio.com/items?itemName=MS-SarifVSCode.sarif-viewer) extension renders results inline
- **Azure DevOps** — the `PublishBuildArtifacts` + SARIF viewer extension pipeline tasks
- **SonarQube** — import via the generic issue import format after conversion

## GitHub Actions annotations mode

Enable with `-github-annotations`:

```bash
./methodatlas -ai -github-annotations src/test/java
```

Instead of CSV or JSON, MethodAtlas emits GitHub Actions [workflow commands](https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/workflow-commands-for-github-actions) to standard output.

Each line is one of two command forms:

| Command | Condition |
|---|---|
| `::warning file=…,line=…,title=…::…` | Security-relevant method with `ai_interaction_score >= 0.8` — the test only verifies method calls, not outcomes (potential placebo test) |
| `::notice file=…,line=…,title=…::…` | Security-relevant method with interaction score below threshold, OR any method carrying `@DisplayName("")` |

The `@DisplayName("")` notice is emitted for every method that declares an explicitly empty display name, regardless of whether AI enrichment is enabled or whether the method is security-relevant. An empty display name causes the test to appear unnamed in CI and coverage reports, which obscures the audit trail.

Example output:

```text
::notice file=src/test/java/com/acme/auth/LoginTest.java,line=22,title=Security test: auth::SECURITY: auth - validates session token after login
::warning file=src/test/java/com/acme/auth/SessionTest.java,line=45,title=Placebo security test::SECURITY: auth - session invalidation (interaction-only)
::notice file=src/test/java/com/acme/util/HelperTest.java,line=10,title=@DisplayName("") on com.acme.util.HelperTest#testHelper::@DisplayName("") declares an empty display name — the test will appear unnamed in reports, obscuring the audit trail
```

GitHub renders these as inline annotations on the PR diff. No GitHub Advanced Security licence is required — `::notice`/`::warning` are standard GitHub Actions features available on all plan tiers.

File paths in the annotations are derived from the scan root and the class FQCN, producing paths such as `src/test/java/com/acme/auth/LoginTest.java` that GitHub resolves to the correct inline position for standard Maven/Gradle source layouts.

See [docs/cli/github-actions.md](ci/github-actions.md) for a complete workflow example, and [docs/cli-reference.md#-github-annotations](cli-reference.md#-github-annotations) for the full flag description.

## Apply-tags mode

`-apply-tags` is not a report mode — it modifies source files in place instead of emitting output. When combined with `-ai` (or `-manual-consume`), MethodAtlas writes AI-generated `@DisplayName` and `@Tag` annotations directly into the scanned test source files, then prints a summary to standard output.

```bash
./methodatlas -ai -apply-tags /path/to/tests
```

Before:

```java
@Test
void testLoginWithValidCredentials() { ... }
```

After:

```java
@Test
@DisplayName("SECURITY: auth - validates session token after login")
@Tag("security")
@Tag("auth")
void testLoginWithValidCredentials() { ... }
```

Only security-relevant methods are annotated. Existing `@DisplayName` or `@Tag` annotations are never overwritten or duplicated. Required imports (`org.junit.jupiter.api.DisplayName`, `org.junit.jupiter.api.Tag`) are added automatically when at least one annotation of that type is inserted. Unrelated formatting is preserved through lexical-preserving pretty printing.

See [Source Write-back](usage-modes/apply-tags.md) for the complete workflow, including how to combine it with the manual AI workflow.

## Choosing between modes

| Situation | Recommended mode |
| --- | --- |
| Feeding output into a spreadsheet or data pipeline | CSV (default) |
| Quick visual inspection in a terminal | Plain (`-plain`) |
| Archiving scan results with provenance metadata | CSV + `-emit-metadata` |
| Filtering high-confidence security findings | CSV + `-ai-confidence` |
| Incremental scanning or change detection | CSV or SARIF + `-content-hash` |
| Integrating with a SAST platform or IDE | SARIF (`-sarif`) |
| Annotating source files with AI-suggested tags | `-apply-tags` |
