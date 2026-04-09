# Output formats

MethodAtlas supports three report modes — **CSV** (default), **plain text**, and **SARIF** — plus a write-back mode (**`-apply-tags`**) that modifies source files directly instead of emitting a report.  
All report modes produce one record per discovered test method.

## CSV mode

CSV mode is the default. It produces a header row followed by one data row per test method.

### Without AI enrichment

```text
fqcn,method,loc,tags
com.acme.tests.SampleOneTest,alpha,8,fast;crypto
com.acme.tests.SampleOneTest,beta,6,param
com.acme.tests.SampleOneTest,gamma,4,nested1;nested2
com.acme.other.AnotherTest,delta,3,
```

Multiple JUnit `@Tag` values are joined with `;`. An empty `tags` field means the method has no source-level tags.

### With AI enrichment (`-ai`)

```text
fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason
com.acme.tests.SampleOneTest,alpha,8,fast;crypto,true,"SECURITY: crypto - validates encrypted happy path",security;crypto,The test exercises a crypto-related security property.
com.acme.tests.SampleOneTest,beta,6,param,false,,,
```

Fields `ai_display_name`, `ai_tags`, and `ai_reason` are empty for non-security-relevant methods.

### With AI enrichment and confidence scoring (`-ai -ai-confidence`)

```text
fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_confidence
com.acme.tests.SampleOneTest,alpha,8,fast;crypto,true,"SECURITY: crypto - validates encrypted happy path",security;crypto,The test exercises a crypto-related security property.,0.9
com.acme.tests.SampleOneTest,beta,6,param,false,,,,0.0
```

`ai_confidence` is `0.0` for methods classified as not security-relevant.

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
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto
com.acme.tests.SampleOneTest, beta, LOC=6, TAGS=param
com.acme.tests.SampleOneTest, gamma, LOC=4, TAGS=nested1;nested2
com.acme.other.AnotherTest, delta, LOC=3, TAGS=-
```

`TAGS=-` is printed when a method has no source-level JUnit tags.

### Plain mode with AI enrichment

```text
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto, AI_SECURITY=true, AI_DISPLAY=SECURITY: crypto - validates encrypted happy path, AI_TAGS=security;crypto, AI_REASON=The test exercises a crypto-related security property.
com.acme.tests.SampleOneTest, beta, LOC=6, TAGS=param, AI_SECURITY=false, AI_DISPLAY=-, AI_TAGS=-, AI_REASON=-
```

Absent AI values are printed as `-` in plain mode.

### Plain mode with confidence scoring

When `-ai-confidence` is also passed, an `AI_CONFIDENCE` token is appended:

```text
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto, AI_SECURITY=true, AI_DISPLAY=SECURITY: crypto - validates encrypted happy path, AI_TAGS=security;crypto, AI_REASON=The test exercises a crypto-related security property., AI_CONFIDENCE=0.9
```

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
| `note` | The AI classified the method as security-relevant |
| `none` | All other test methods (no AI, or AI returned `securityRelevant=false`) |

### Rule IDs

Rules are derived automatically from the AI tags present in the results:

| Rule ID | Meaning |
| --- | --- |
| `test-method` | Default rule for all non-security test methods |
| `security/<tag>` | One rule per specific security category (e.g. `security/auth`, `security/crypto`) |
| `security-test` | Security-relevant method carrying only the umbrella `security` tag |

### Locations

Each result carries both a physical and a logical location:

- **Physical location** — artifact URI derived from the FQCN (e.g. `com/acme/LoginTest.java`), relative to `%SRCROOT%`, with the method's start line when available
- **Logical location** — the fully qualified method name (e.g. `com.acme.LoginTest.testLoginWithValidCredentials`) with kind `member`

### Properties bag

AI enrichment fields are stored in the result `properties` object when AI is enabled:

| Property | Description |
| --- | --- |
| `loc` | Inclusive line count of the method declaration |
| `sourceTags` | Semicolon-separated JUnit `@Tag` values from the source, or `null` |
| `aiSecurityRelevant` | Boolean AI classification, or `null` when AI is disabled |
| `aiDisplayName` | Suggested `@DisplayName` text, or `null` |
| `aiTags` | Semicolon-separated security taxonomy tags, or `null` |
| `aiReason` | Explanatory rationale from the AI, or `null` |
| `aiConfidence` | Confidence score `0.0–1.0`, or `null` when `-ai-confidence` was not passed |

`null`-valued properties are omitted from the JSON output.

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
              "shortDescription": { "text": "JUnit test method" }
            },
            {
              "id": "security/auth",
              "name": "SecurityAuth",
              "shortDescription": { "text": "Security test: auth" }
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
                  "uri": "com/acme/tests/SampleOneTest.java",
                  "uriBaseId": "%SRCROOT%"
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
            "loc": 3
          }
        },
        {
          "ruleId": "security/auth",
          "level": "note",
          "message": { "text": "SECURITY: auth - validates session token after login" },
          "locations": [
            {
              "physicalLocation": {
                "artifactLocation": {
                  "uri": "com/acme/security/LoginTest.java",
                  "uriBaseId": "%SRCROOT%"
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

See [ai-guide.md](ai-guide.md#apply-tags-workflow) for the complete workflow, including how to combine it with the manual AI workflow.

## Choosing between modes

| Situation | Recommended mode |
| --- | --- |
| Feeding output into a spreadsheet or data pipeline | CSV (default) |
| Quick visual inspection in a terminal | Plain (`-plain`) |
| Archiving scan results with provenance metadata | CSV + `-emit-metadata` |
| Filtering high-confidence security findings | CSV + `-ai-confidence` |
| Integrating with a SAST platform or IDE | SARIF (`-sarif`) |
| Annotating source files with AI-suggested tags | `-apply-tags` |
