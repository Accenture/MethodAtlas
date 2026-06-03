# Evidence Packs

`-evidence-pack <framework>` runs a full scan and bundles the results into a tamper-evident, self-contained directory that auditors can verify years after the fact without trusting the original build environment.

## Purpose

A typical compliance audit happens months or years after the scan that produced the artefact. The auditor needs three guarantees:

- The SARIF (Static Analysis Results Interchange Format) and CSV reports were produced by an unmodified MethodAtlas build, not edited after the fact.
- The list of files in the pack is complete and untampered.
- The provenance — toolchain version, scan roots, framework target, AI model — is verifiable from the pack alone.

An evidence pack delivers all three: every artefact is hashed into a `manifest.sha256` file, and that manifest is optionally signed with [ZeroEcho](https://gitea.egothor.org/Egothor/ZeroEcho) so the integrity of every byte in the directory rolls up to one signature line.

## Supported frameworks

| Token (CLI input)   | Canonical form    | What it maps to                                            |
|---------------------|-------------------|------------------------------------------------------------|
| `ASVS`              | `ASVS`            | OWASP Application Security Verification Standard           |
| `PCI-6.4.1`         | `PCI-6.4.1`       | PCI DSS requirement 6.4.1 (software security requirements) |
| `NIST-SSDF-PW.8`    | `NIST-SSDF-PW.8`  | NIST SSDF, practice PW.8                                   |
| `ISO-27001-8.29`    | `ISO-27001-8.29`  | ISO/IEC 27001:2022 control 8.29                            |

Tokens are case-insensitive on input; the canonical form is always used in `pack-meta.json` and in the default directory name. An unknown token causes the CLI to print an error to stderr and exit with code `2`.

The frameworks expand to: **ASVS** — the OWASP (Open Worldwide Application Security Project) Application Security Verification Standard; **PCI DSS** — the Payment Card Industry Data Security Standard; **NIST SSDF** — the NIST Secure Software Development Framework ([SP 800-218](https://csrc.nist.gov/pubs/sp/800/218/final)); **ISO/IEC 27001:2022** — the international information-security management standard.

## Command-line flags

| Flag                                  | Purpose                                                                                              |
|---------------------------------------|------------------------------------------------------------------------------------------------------|
| `-evidence-pack <framework>`          | Select the target framework. Required to engage the mode.                                            |
| `-evidence-pack-dir <path>`           | Output directory. Defaults to `<first-scan-root>/evidence-packs/<canonical-framework>/`.             |
| `-evidence-pack-overwrite`            | Reuse an existing output directory instead of treating it as an error.                               |
| `-evidence-pack-keyring <path>`       | ZeroEcho keyring file providing the signing key. When absent (and no keyring env var is set) the pack is produced unsigned with a warning. |
| `-evidence-pack-keyring-env <name>`   | Name of an environment variable holding the keyring **content**, for CI/CD. Takes precedence over the file. |
| `-evidence-pack-key-alias <alias>`    | Keyring alias. Defaults to the first alias in the keyring. For hybrid signing use `classicAlias/pqcAlias`. |
| `-evidence-pack-sign-algo <algo>`     | Signature algorithm identifier. Omit to derive it from the keyring entry. See **Signing** below.     |

The signing key is read from a ZeroEcho keyring — a plaintext `KeyringStore` file, **not** a JDK PKCS12/JKS keystore and **not** produced by `keytool`. ZeroEcho keyrings carry no password; protect the file with permissions (interactive CLI) or supply its content through a secret (CI/CD).

```bash
./methodatlas -evidence-pack PCI-6.4.1 \
              -evidence-pack-dir build/audit-q2 \
              -evidence-pack-keyring keys/audit-keyring.txt \
              -evidence-pack-key-alias audit \
              src/test/java
```

!!! warning "The keyring holds a private key in clear text"
    A ZeroEcho keyring stores the private signing key unencrypted. Treat it like an SSH private key: restrict its file permissions (the generator sets `0600`), keep it out of version control, and never place it inside an evidence pack — MethodAtlas never copies it there. For pipelines, hold it in a secrets manager and inject it as a secret, never as a file committed to the repository.

## Directory layout

```
<evidence-pack-dir>/
  findings.sarif          # SARIF 2.1.0 (security-only by default)
  findings.csv            # CSV with the same fields as standard scan output
  overrides.yaml          # verbatim copy of -override-file, if supplied
  ai-responses.jsonl      # one JSON object per AI round-trip, if -ai was used
  manifest.sha256         # SHA-256 of every other file in this directory
  manifest.sha256.signed  # ZeroEcho signed envelope of manifest.sha256
  pack-meta.json          # framework, version, scan roots, signing status
```

Files that did not apply to the run (e.g. `overrides.yaml` when no override was supplied, or `ai-responses.jsonl` when AI was not enabled) are simply absent. Absence is a stronger signal to an auditor than an empty file.

`manifest.sha256` lists one entry per line in `<hex-digest>  <filename>` format, with files in lexicographic order. `manifest.sha256` itself and `manifest.sha256.signed` are excluded from the manifest because they are the manifest.

## Signing

Signing is performed with the [ZeroEcho cryptographic toolkit](https://gitea.egothor.org/Egothor/ZeroEcho) (version 1.1.0). The output of the signing step is `manifest.sha256` followed by a ZeroEcho signature trailer. `pack-meta.json` records the `signatureAlgorithm`, the `keyAlias` used, and `zeroEchoLibVersion`.

### Available algorithms

The algorithm is taken from `-evidence-pack-sign-algo`; when that flag is omitted it is read from the keyring entry, so a keyring built by `-gen-signing-key` signs with its own algorithm.

| `-evidence-pack-sign-algo` value | Kind | Backed by |
|----------------------------------|------|-----------|
| `Ed25519` (generator default)    | Classical | `TagEngineBuilder.signature("Ed25519", key, …)` |
| `RSA`                            | Classical | `TagEngineBuilder.rsaSign(key, …)` — RSA-PSS, SHA-256 |
| `ECDSA`                          | Classical | `TagEngineBuilder.ecdsaSign(key, …)` — P-256 |
| `SPHINCS+`, `ML-DSA`, `SLH-DSA`  | Post-quantum | `TagEngineBuilder.signature("<id>", key, …)` |
| `classic+pqc` (e.g. `Ed25519+SPHINCS+`) | Hybrid | `HybridSignatureContexts.sign(profile, classicKey, pqcKey, …)` with the AND verification rule |

Hybrid signing combines a classical and a post-quantum signature so the pack stays verifiable even if one primitive is later broken. It needs two keys in the keyring; pass both aliases as `classicAlias/pqcAlias` to `-evidence-pack-key-alias`.

**Algorithm reference.** The identifiers above are standard signature schemes:

- **Ed25519** — an Edwards-curve Digital Signature Algorithm (EdDSA) defined in [RFC 8032](https://www.rfc-editor.org/rfc/rfc8032); fixed 64-byte signatures, and the default here.
- **RSA-PSS** — RSA with Probabilistic Signature Scheme padding (PKCS#1 v2.1, [RFC 8017](https://www.rfc-editor.org/rfc/rfc8017)), using a SHA-256 digest.
- **ECDSA P-256** — the Elliptic Curve Digital Signature Algorithm over the NIST P-256 curve (FIPS 186-5).
- **SPHINCS+** — a stateless hash-based signature scheme; the basis of the NIST SLH-DSA standard.
- **ML-DSA** — Module-Lattice Digital Signature Algorithm (NIST FIPS 204, formerly CRYSTALS-Dilithium).
- **SLH-DSA** — Stateless Hash-based Digital Signature Algorithm (NIST FIPS 205).

**Post-quantum cryptography (PQC)** is the family of algorithms designed to resist attacks by large-scale quantum computers. SPHINCS+, ML-DSA, and SLH-DSA are PQC schemes; Ed25519, RSA, and ECDSA are classical. A **hybrid** signature pairs one classical and one PQC signature; under the **AND rule** both must validate, so the evidence remains trustworthy even if one algorithm family is later broken.

### Generating a keyring

Generate a signing key pair with the built-in `-gen-signing-key` mode. It writes a ZeroEcho keyring file (a plaintext `KeyringStore`) and restricts it to owner-only permissions:

```bash
./methodatlas -gen-signing-key keys/audit-keyring.txt -key-alias audit
```

Alongside the keyring this also writes `keys/audit-public.pem` — the public key in standard X.509 PEM form. The PEM is not secret; hand it to auditors so they can verify a signed manifest with standard tooling (see [Verifying with standard tools](#verifying-with-standard-tools-openssl)).

`-key-algo` selects the algorithm (`Ed25519` default, or `RSA`, `ECDSA`, `SPHINCS+`); `-overwrite` replaces an existing alias. For a hybrid keyring, generate both halves into the same file:

```bash
./methodatlas -gen-signing-key keys/audit-keyring.txt -key-alias classic -key-algo Ed25519
./methodatlas -gen-signing-key keys/audit-keyring.txt -key-alias pqc     -key-algo SPHINCS+
```

then sign with `-evidence-pack-key-alias classic/pqc -evidence-pack-sign-algo Ed25519+SPHINCS+`.

!!! note "ZeroEcho's own tooling also generates keyrings"
    The keyring format is ZeroEcho's. If you already run the ZeroEcho CLI, `ZeroEcho -K --generate --alg Ed25519 --alias audit --keystore keys/audit-keyring.txt` produces an equivalent file. `-gen-signing-key` exists so MethodAtlas users do not need a second tool.

### Signing in CI/CD pipelines

A plaintext keyring file on a build runner is not acceptable for regulated pipelines. Instead, keep the key in the platform's secret store and pass its **content** — not a path — through an environment variable. MethodAtlas reads the variable named by `-evidence-pack-keyring-env` and parses the keyring in memory, so the private key never touches the runner's disk.

Generate the keyring locally, then paste the **entire file content** (multi-line text beginning with `# KeyringStore v1`) into a repository secret named, for example, `ZEROECHO_KEYRING`. Both GitHub Actions and Gitea Actions support multi-line secret values.

=== "GitHub Actions"

    ```yaml
    - name: Evidence pack (signed)
      env:
        ZEROECHO_KEYRING: ${{ secrets.ZEROECHO_KEYRING }}
      run: |
        ./methodatlas -evidence-pack PCI-6.4.1 \
                      -evidence-pack-dir build/audit \
                      -evidence-pack-keyring-env ZEROECHO_KEYRING \
                      -evidence-pack-key-alias audit \
                      src/test/java
    ```

=== "Gitea Actions"

    ```yaml
    - name: Evidence pack (signed)
      env:
        ZEROECHO_KEYRING: ${{ secrets.ZEROECHO_KEYRING }}
      run: |
        ./methodatlas -evidence-pack PCI-6.4.1 \
                      -evidence-pack-dir build/audit \
                      -evidence-pack-keyring-env ZEROECHO_KEYRING \
                      -evidence-pack-key-alias audit \
                      src/test/java
    ```

If the named variable is unset or empty, MethodAtlas logs a warning and writes the pack unsigned.

### Verifying a signed pack

Verification needs only the **public** key, distributed to the auditor through an authenticated channel. Once `manifest.sha256.signed` validates, every byte of `manifest.sha256` is anchored, and every artefact listed in the manifest is anchored through its SHA-256 column; tampering with `findings.sarif`, `findings.csv`, or `pack-meta.json` after the fact breaks the chain.

#### Verifying with ZeroEcho

The native path uses ZeroEcho's `Tag` tool with the public alias from the keyring; it verifies the trailer and re-emits the manifest body:

```bash
ZeroEcho -T --type signature --mode verify --alg Ed25519 \
         --ks audit-keyring.txt --pub audit \
         --in manifest.sha256.signed --out manifest.sha256
```

A non-zero exit means the signature did not validate.

#### Verifying with standard tools (openssl)

ZeroEcho has not yet completed a formal cryptographic audit, so some auditors will not accept it as the verifier. The signed envelope is deliberately simple, so any tool that implements the chosen algorithm can verify it independently:

- `manifest.sha256.signed` is exactly the manifest bytes followed by a fixed-length signature **trailer**.
- The trailer is the raw output of the standard Java Cryptography Architecture (JCA) signature for the algorithm — for `Ed25519` that is the 64-byte RFC 8032 signature over the message; for `RSA` it is an RSA-PSS (SHA-256) signature; for `ECDSA` a DER-encoded (ASN.1 Distinguished Encoding Rules) ECDSA signature.
- The signing public key is the `<alias>-public.pem` file written by `-gen-signing-key`, a standard X.509 `SubjectPublicKeyInfo` (SPKI).

The example below verifies an **Ed25519** pack with `openssl` (3.0 or newer, for `-rawin`). Split the envelope into body and signature, confirm the body matches the manifest in the pack, then verify:

```bash
SIGNED=manifest.sha256.signed
SIG_LEN=64                                   # Ed25519 signatures are 64 bytes
BODY_LEN=$(( $(wc -c < "$SIGNED") - SIG_LEN ))

head -c "$BODY_LEN" "$SIGNED" > manifest.body
tail -c "$SIG_LEN"  "$SIGNED" > manifest.sig

cmp manifest.body manifest.sha256            # body must equal the pack's manifest

openssl pkeyutl -verify -pubin -inkey audit-public.pem \
        -rawin -in manifest.body -sigfile manifest.sig
# -> "Signature Verified Successfully" (exit 0)
```

For an `RSA` pack the signature is the trailing modulus-length bytes (256 for RSA-2048) and the command becomes `openssl dgst -sha256 -sigopt rsa_padding_mode:pss -sigopt rsa_pss_saltlen:32 -verify audit-public.pem -signature manifest.sig manifest.body`.

!!! note "Trust the public key, not the pack"
    The public key must come from an authenticated channel — your own copy of `<alias>-public.pem`, not a copy embedded in the pack. A tamperer who replaces the manifest can also replace any public key shipped beside it; only an out-of-band key breaks that loop. This is the same discipline as any code-signing key.

Hybrid (`classic+pqc`) envelopes carry both component signatures and are verified with ZeroEcho; an auditor can still verify the classical half independently with the recipe above.

## Framework mapping

The framework token never changes what MethodAtlas scans — the source tree determines that. It does change how the pack is labelled, which controls the auditor reads it against, and which mapping table is implied by `pack-meta.json`.

| Framework       | Primary controls this evidence pack helps satisfy                                                            |
|-----------------|--------------------------------------------------------------------------------------------------------------|
| ASVS            | V1 (Architecture & Threat Modelling), V12 (API & Web Service), V14 (Configuration). Test inventory + AI tags. |
| PCI-6.4.1       | Software security requirements: documented testing of security-relevant changes before promotion.            |
| NIST-SSDF-PW.8  | PW.8 — Reuse existing, well-secured software; perform testing on third-party software in your build.         |
| ISO-27001-8.29  | Control 8.29 — Security testing in development and acceptance, with auditable evidence of testing scope.     |

## Known limitations

- **Verifier dependencies.** A single classical signature (Ed25519, RSA, ECDSA) can be validated with standard tools such as `openssl` after splitting the fixed-length trailer (see [Verifying with standard tools](#verifying-with-standard-tools-openssl)). Post-quantum and hybrid envelopes need ZeroEcho (or ZeroEcho-lib on the classpath), because no mainstream system tool yet implements SPHINCS+, ML-DSA, SLH-DSA, or the hybrid AND rule.
- **Out-of-band public key distribution.** The pack contains the manifest and its signature but not the public key. Distribute the public half of the keyring to auditors through an authenticated channel — same as you would for any code-signing key.
- **Plaintext keyring.** ZeroEcho keyrings hold the private key unencrypted. Protect the file with file-system permissions or access control lists (ACLs) for CLI use, or supply it through a secret with `-evidence-pack-keyring-env` in pipelines. The keyring is never written into the pack.
- **SPHINCS+ signature size.** SPHINCS+ trailers are in the 8–50 KB range depending on variant; hybrid `Ed25519+SPHINCS+` adds the classical signature on top. This affects only `manifest.sha256.signed`, not the other artefacts.
- **PCI/ASVS/NIST tokens do not modify behaviour.** They tag the pack for downstream tooling. The actual scan, filtering, and AI classification are identical across frameworks; only `pack-meta.json#framework` and the default output directory name change.
