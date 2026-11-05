# Evidence Packs

`-evidence-pack <framework>` runs a full scan and bundles the results into a tamper-evident, self-contained directory that auditors can verify years after the fact without trusting the original build environment.

## Purpose

A typical compliance audit happens months or years after the scan that produced the artefact. The auditor needs three guarantees:

- The SARIF (Static Analysis Results Interchange Format) and CSV reports were produced by an unmodified MethodAtlas build, not edited after the fact.
- The list of files in the pack is complete and untampered.
- The provenance — toolchain version, scan roots, framework target, AI model — is verifiable from the pack alone.

An evidence pack delivers all three: every artefact is hashed into a `manifest.sha256` file, and that manifest is optionally signed with [ZeroEcho](https://github.com/Egothor/ZeroEcho) so the integrity of every byte in the directory rolls up to one signature line.

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
| `-evidence-pack-keyring <path>`       | Encrypted ZeroEcho keyring providing the signing key. When absent (and no keyring env var is set) the pack is produced unsigned and the stderr summary says so. |
| `-evidence-pack-keyring-env <name>`   | Name of an environment variable holding a keyring **secret bundle** (from `-export-keyring-secret`), for CI/CD. Takes precedence over the file. |
| `-evidence-pack-keyring-password-file <path>` | File whose first line is the keyring password (suits CI secret files and Docker/Kubernetes secrets). |
| `-evidence-pack-keyring-password-env <name>`  | Name of an environment variable holding the keyring password. |
| `-evidence-pack-key-alias <alias>`    | Keyring alias. Defaults to the first alias in the keyring. For hybrid signing use `classicAlias/pqcAlias`. |
| `-evidence-pack-sign-algo <algo>`     | Signature algorithm identifier. Omit to derive it from the keyring entry. See **Signing** below.     |

The signing key is read from an **encrypted** ZeroEcho keyring (ZeroEcho 1.2, PBKDF2-HMAC-SHA-256 + AES-256-GCM) — **not** a JDK PKCS12/JKS keystore and **not** produced by `keytool`. A password is never accepted as a command-line value. MethodAtlas takes the first password source that is configured:

1. `-evidence-pack-keyring-password-file <path>`;
2. `-evidence-pack-keyring-password-env <name>` (the variable must be set);
3. the `METHODATLAS_KEYRING_PASSWORD` environment variable;
4. an interactive, non-echoing prompt when a terminal is attached.

```bash
./methodatlas -evidence-pack PCI-6.4.1 \
              -evidence-pack-dir build/audit-q2 \
              -evidence-pack-keyring ~/.methodatlas/keys/audit \
              -evidence-pack-key-alias audit \
              src/test/java
# Keyring password: ********
```

!!! warning "Protect the keyring and its password separately"
    The keyring is encrypted, but anyone who obtains both the keyring and its password can sign in your name. Keep the password in a password manager or secret store, never next to the keyring, never in version control, and never in the evidence pack — MethodAtlas never copies the keyring or the password there.

### Keyring files and permissions

A keyring at `<dir>/<name>` is **two files** that always travel together, plus a lock file that exists while the keyring is open:

| File | Content |
|------|---------|
| `<name>` | the encrypted keyring store |
| `<name>.nonce` | an authenticated nonce-reservation sidecar; the keyring cannot be opened without it |
| `<name>.lock` | ownership lock held while the keyring is open (safe to delete when no MethodAtlas process is running) |
| `<alias>-public.pem` | the public key for auditors (written by `-gen-signing-key`, not secret) |

ZeroEcho enforces owner-only access: on Linux/macOS the directory must be `0700` and the keyring files `0600`; on Windows the directory gets an owner-only ACL. Symbolic links and hard links are rejected. `-gen-signing-key` creates a missing directory with the right permissions, so the easiest choice is a dedicated directory such as `~/.methodatlas/keys/`. If you point `-gen-signing-key` at an existing shared directory (for example a repository checkout with `0755`), it stops with a message telling you which `chmod` to run; `-evidence-pack` with such a keyring logs the same message and produces the pack unsigned.

Back up the keyring **and** its `.nonce` file together; restoring one without the other makes the keyring unreadable.

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

Signing is performed with the [ZeroEcho cryptographic toolkit](https://github.com/Egothor/ZeroEcho) (version 1.2.1). The output of the signing step is `manifest.sha256` followed by a ZeroEcho signature trailer. `pack-meta.json` records the `signatureAlgorithm`, the `keyAlias` used, and `zeroEchoLibVersion`.

### Available algorithms

The algorithm is taken from `-evidence-pack-sign-algo`; when that flag is omitted it is read from the keyring entry, so a keyring built by `-gen-signing-key` signs with its own algorithm. `-gen-signing-key` generates `Ed25519`, `RSA`, `ECDSA` and `SPHINCS+` keys; `Ed448`, `ML-DSA` and `SLH-DSA` keys can be used for signing when they were created in the keyring with ZeroEcho's own tooling.

| `-evidence-pack-sign-algo` value | Kind | Backed by |
|----------------------------------|------|-----------|
| `Ed25519` (generator default)    | Classical | `TagEngineBuilder.signature("Ed25519", key, …)` |
| `RSA`                            | Classical | `TagEngineBuilder.rsaSign(key, …)` — RSA-PSS, SHA-256 |
| `ECDSA`                          | Classical | `TagEngineBuilder.ecdsaSign(key, …)` — P-256 |
| `Ed448`                          | Classical | `TagEngineBuilder.signature("Ed448", key, …)` |
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

Generate a signing key pair with the built-in `-gen-signing-key` mode. It creates an encrypted ZeroEcho keyring (or adds the key to an existing one) and asks for a password — twice for a new keyring, which needs at least 12 characters:

```bash
./methodatlas -gen-signing-key ~/.methodatlas/keys/audit -key-alias audit
# New keyring password: ********
# Repeat keyring password: ********
```

For unattended use, supply the password with `-key-password-file <path>`, `-key-password-env <VAR>`, or the `METHODATLAS_KEYRING_PASSWORD` variable instead of the prompt (same order as for signing).

Alongside the keyring this also writes `~/.methodatlas/keys/audit-public.pem` — the public key in standard X.509 PEM form. The PEM is not secret; hand it to auditors so they can verify a signed manifest with standard tooling (see [Verifying with standard tools](#verifying-with-standard-tools-openssl)). The command finishes by printing the exact signing and CI-export commands for the new key.

`-key-algo` selects the algorithm (`Ed25519` default, or `RSA`, `ECDSA`, `SPHINCS+`); `-overwrite` replaces an existing alias — the keyring is backed up to `<name>.bak` / `<name>.nonce.bak` while the key is replaced, and the backup is removed once the new key is stored (it is kept, with a message, if the replacement fails). Aliases must not end in `.pub` or `.priv` and must not contain `/`, which separates the two aliases of a hybrid key. For a hybrid keyring, generate both halves into the same keyring:

```bash
./methodatlas -gen-signing-key ~/.methodatlas/keys/audit -key-alias classic -key-algo Ed25519
./methodatlas -gen-signing-key ~/.methodatlas/keys/audit -key-alias pqc     -key-algo SPHINCS+
```

then sign with `-evidence-pack-key-alias classic/pqc -evidence-pack-sign-algo Ed25519+SPHINCS+`.

!!! note "ZeroEcho's own tooling uses the same format"
    The keyring format is ZeroEcho 1.2's. A keyring created with the ZeroEcho CLI can be used by MethodAtlas and vice versa; `-gen-signing-key` exists so MethodAtlas users do not need a second tool.

### Signing in CI/CD pipelines

A CI platform secret holds one text value, while a keyring is two binary files. `-export-keyring-secret` packs the keyring and its `.nonce` sidecar into a single text **secret bundle** (Base64 lines under a `# MethodAtlas keyring secret v1` header). The keyring stays encrypted inside the bundle, so the pipeline also needs the password as a **second, separate** secret.

```bash
(umask 077; ./methodatlas -export-keyring-secret ~/.methodatlas/keys/audit > audit-keyring.secret)
```

Store the content of `audit-keyring.secret` as a secret named, for example, `METHODATLAS_KEYRING`, and the keyring password as `METHODATLAS_KEYRING_PASSWORD`; then delete the exported file. On GitHub the helper script [`scripts/setup-github-signing-secrets.sh`](https://github.com/Accenture/MethodAtlas/blob/main/scripts/setup-github-signing-secrets.sh) does both steps with the `gh` CLI, without writing the bundle to disk. Before storing anything it proves the password with a trial signing, so a mistyped password never reaches the pipeline:

```bash
scripts/setup-github-signing-secrets.sh ~/.methodatlas/keys/audit            # current repository
scripts/setup-github-signing-secrets.sh ~/.methodatlas/keys/audit owner/repo # another repository
```

At run time MethodAtlas reads the bundle from the variable named by `-evidence-pack-keyring-env`, restores it into a fresh owner-only temporary directory, unlocks it with the password, reads the key, and deletes the temporary copy before signing. `METHODATLAS_KEYRING_PASSWORD` is picked up automatically.

=== "GitHub Actions"

    ```yaml
    - name: Evidence pack (signed)
      env:
        METHODATLAS_KEYRING: ${{ secrets.METHODATLAS_KEYRING }}
        METHODATLAS_KEYRING_PASSWORD: ${{ secrets.METHODATLAS_KEYRING_PASSWORD }}
      run: |
        ./methodatlas -evidence-pack PCI-6.4.1 \
                      -evidence-pack-dir build/audit \
                      -evidence-pack-keyring-env METHODATLAS_KEYRING \
                      -evidence-pack-key-alias audit \
                      src/test/java
    ```

=== "Gitea Actions"

    ```yaml
    - name: Evidence pack (signed)
      env:
        METHODATLAS_KEYRING: ${{ secrets.METHODATLAS_KEYRING }}
        METHODATLAS_KEYRING_PASSWORD: ${{ secrets.METHODATLAS_KEYRING_PASSWORD }}
      run: |
        ./methodatlas -evidence-pack PCI-6.4.1 \
                      -evidence-pack-dir build/audit \
                      -evidence-pack-keyring-env METHODATLAS_KEYRING \
                      -evidence-pack-key-alias audit \
                      src/test/java
    ```

The keyring is unlocked before the scan starts, so a problem is reported immediately. If the bundle variable is unset or empty, the password is missing or wrong, the alias is unknown, the bundle is malformed, the keyring is a retired plaintext one, its directory is not private, it is in use by another process, or its `.nonce` file is missing or does not match, MethodAtlas logs a warning naming the cause and writes the pack unsigned (exit code `0`; `pack-meta.json` records `"signed": false`). Gate on that field if a release must not ship unsigned evidence. Re-export the bundle whenever you add or replace a key in the keyring.

### Verifying a signed pack

Verification needs only the **public** key, distributed to the auditor through an authenticated channel. Once `manifest.sha256.signed` validates, every byte of `manifest.sha256` is anchored, and every artefact listed in the manifest is anchored through its SHA-256 column; tampering with `findings.sarif`, `findings.csv`, or `pack-meta.json` after the fact breaks the chain.

#### Verifying with the helper script

[`scripts/verify-evidence-pack.sh`](https://github.com/Accenture/MethodAtlas/blob/main/scripts/verify-evidence-pack.sh) performs the complete offline check with only `openssl` (3.0+) and coreutils — no MethodAtlas, no ZeroEcho, no Java:

```bash
scripts/verify-evidence-pack.sh build/audit-q2 audit-public.pem
```

It needs OpenSSL 3.0 or newer. macOS ships LibreSSL as `/usr/bin/openssl`, which cannot verify these signatures; install OpenSSL 3 (for example `brew install openssl@3`) and point the script at it with `OPENSSL=/opt/homebrew/opt/openssl@3/bin/openssl` when it is not first on the `PATH`. An unsuitable OpenSSL is reported as "cannot verify" (exit `2`), never as a bad signature.

It recomputes every SHA-256 digest in `manifest.sha256`, reports unlisted files, confirms that `manifest.sha256.signed` carries exactly that manifest, and verifies the signature for the algorithm recorded in `pack-meta.json` (`Ed25519`, `RSA`, or `ECDSA`). It exits `0` only when everything validates, `1` when anything was tampered with or does not verify, and `2` when the pack cannot be authenticated (unsigned, no public key given, or a post-quantum/hybrid algorithm that needs ZeroEcho); without a public key it still checks the hashes. The script automates the manual steps described below.

#### Verifying with ZeroEcho

Post-quantum signatures and the full AND rule of hybrid signatures are verified with [ZeroEcho](https://github.com/Egothor/ZeroEcho): its signature-trailer verification (`TagTrailerDataContentBuilder` in verify mode with `TagEngineBuilder.*Verify` or `HybridSignatureContexts.verify`) checks the trailer of `manifest.sha256.signed` with the signer's public key(s) and re-emits the manifest body. Use the ZeroEcho 1.2 tooling and documentation for the exact invocation; classical signatures do not need ZeroEcho at all.

#### Verifying with standard tools (openssl)

ZeroEcho has not yet completed a formal cryptographic audit, so some auditors will not accept it as the verifier. The signed envelope is deliberately simple, so any tool that implements the chosen algorithm can verify it independently:

- `manifest.sha256.signed` is exactly the manifest bytes followed by a fixed-length signature **trailer**.
- The trailer is the raw output of the standard Java Cryptography Architecture (JCA) signature for the algorithm — for `Ed25519` that is the 64-byte RFC 8032 signature over the message; for `RSA` it is an RSA-PSS (SHA-256) signature; for `ECDSA` it is the 64-byte raw `r‖s` form (IEEE P1363) of an ECDSA P-256 / SHA-256 signature, which must be re-encoded as ASN.1 DER (Distinguished Encoding Rules) before `openssl dgst -verify` accepts it — `scripts/verify-evidence-pack.sh` does this automatically.
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

Hybrid (`classic+pqc`) envelopes carry both component signatures, **classical first**: the trailer is `classical signature ‖ post-quantum signature`. The full AND rule needs ZeroEcho, but an auditor can verify the classical half independently — it is the first 64 bytes after the manifest for Ed25519 or ECDSA (modulus length for RSA), not the last ones. `scripts/verify-evidence-pack.sh <pack> <classic-alias>-public.pem` does exactly this and reports the result as `PARTIAL` (exit `2`).

## Framework mapping

The framework token never changes what MethodAtlas scans — the source tree determines that. It does change how the pack is labelled, which controls the auditor reads it against, and which mapping table is implied by `pack-meta.json`.

| Framework       | Primary controls this evidence pack helps satisfy                                                            |
|-----------------|--------------------------------------------------------------------------------------------------------------|
| ASVS            | V1 (Architecture & Threat Modelling), V12 (API & Web Service), V14 (Configuration). Test inventory + AI tags. |
| PCI-6.4.1       | Software security requirements: documented testing of security-relevant changes before promotion.            |
| NIST-SSDF-PW.8  | PW.8 — Reuse existing, well-secured software; perform testing on third-party software in your build.         |
| ISO-27001-8.29  | Control 8.29 — Security testing in development and acceptance, with auditable evidence of testing scope.     |

## Known limitations

- **Verifier dependencies.** A single classical signature (Ed25519, RSA, ECDSA) can be validated with standard tools such as `openssl` after splitting the fixed-length trailer (see [Verifying with standard tools](#verifying-with-standard-tools-openssl)). Post-quantum envelopes and the full hybrid AND rule need ZeroEcho (or ZeroEcho-lib on the classpath), because no mainstream system tool yet implements SPHINCS+, ML-DSA, or SLH-DSA; the classical half of a hybrid envelope can still be checked with `openssl`.
- **Out-of-band public key distribution.** The pack contains the manifest and its signature but not the public key. Distribute the public half of the keyring to auditors through an authenticated channel — same as you would for any code-signing key.
- **Keyring format change in ZeroEcho 1.2.** Keyrings created by MethodAtlas 6.1 and earlier (plaintext files starting with `# KeyringStore v1`) are rejected with a migration hint; ZeroEcho 1.2 does not migrate them. See [Migration](../migration.md#evidence-pack-signing-keyrings-are-encrypted-zeroecho-12-620). Packs signed earlier stay verifiable with their original `<alias>-public.pem` — classical ones through the standard-tools path, which does not depend on the ZeroEcho version, post-quantum and hybrid ones with ZeroEcho.
- **Unattended signing needs the password.** Encrypted keyrings cannot be unlocked without their password; for pipelines, store it as a separate secret next to the keyring bundle. The keyring and the password are never written into the pack.
- **SPHINCS+ signature size.** SPHINCS+ trailers are in the 8–50 KB range depending on variant; hybrid `Ed25519+SPHINCS+` adds the classical signature on top. This affects only `manifest.sha256.signed`, not the other artefacts.
- **PCI/ASVS/NIST tokens do not modify behaviour.** They tag the pack for downstream tooling. The actual scan, filtering, and AI classification are identical across frameworks; only `pack-meta.json#framework` and the default output directory name change.
