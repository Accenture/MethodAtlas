# Output formats

MethodAtlas supports two output modes: **CSV** (default) and **plain text**.  
Both modes produce one record per discovered test method.

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

## Choosing between modes

| Situation | Recommended mode |
| --- | --- |
| Feeding output into a spreadsheet or data pipeline | CSV (default) |
| Quick visual inspection in a terminal | Plain (`-plain`) |
| Archiving scan results with provenance metadata | CSV + `-emit-metadata` |
| Filtering high-confidence security findings | CSV + `-ai-confidence` |
