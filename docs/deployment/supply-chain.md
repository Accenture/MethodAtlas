# Supply-chain security

MethodAtlas publishes two supply-chain assurances on GitHub, and is explicit
about a third that it deliberately does **not** provide.

| Assurance | Provided | Mechanism |
|---|---|---|
| Build provenance (SLSA) | Yes | Keyless attestation of every release asset by `release.yml` |
| Repository posture scoring | Yes | OpenSSF Scorecard (`scorecard.yml`), weekly and on push to `main` |
| Managed-key release-package signing | **No, by design** | Owner decision D-1; see [Posture](#honest-posture) |

## Build provenance (SLSA, keyless)

For every `release@x.y.z` tag the `Release` workflow attests, with
[`actions/attest-build-provenance`](https://github.com/actions/attest-build-provenance),
each file it publishes:

- `methodatlas-<version>.zip` and `.tar` (distribution archives)
- `bom.json` (CycloneDX SBOM)
- `MethodAtlas-<version>.pdf` (documentation)

The attestation is **keyless**: it is signed with the short-lived GitHub OIDC
identity of the workflow run through Sigstore and recorded in the public
Sigstore transparency log. There are no signing keys to generate, store,
rotate or revoke. It states which repository, workflow, tag and commit
produced each artefact. Individual module JARs are not separate release
assets and are not attested on their own; they are covered inside the
attested archives.

### Verifying a download

```bash
gh attestation verify methodatlas-<version>.zip --repo Accenture/MethodAtlas
```

A zero exit code means the file's SHA-256 digest matches an attestation issued
to a run of this repository's release workflow. A repackaged or rebuilt copy
(for example a JAR re-zipped with altered content) has a different digest and
fails verification, so repackaging becomes detectable.

## OpenSSF Scorecard

`.github/workflows/scorecard.yml` runs
[OpenSSF Scorecard](https://securityscorecards.dev/) weekly (Monday 04:23 UTC),
on every push to `main`, and on manual dispatch. Results are published to the
OpenSSF API (the repository is public) and uploaded to GitHub code scanning as
SARIF, where they appear in the **Security** tab under the `scorecard`
tool. The workflow runs with least privilege (`security-events: write`,
`id-token: write`, `contents: read`, `actions: read`) and pins its actions to
commit SHAs.

## Honest posture

- **Provided:** keyless build provenance and Scorecard.
- **Not provided, deliberately:** signing of release packages with managed keys
  (cosign with stored keys, GPG). Owner decision D-1 rejects it because of the
  key management, rotation and administration burden. Owner decision D-4
  approves keyless provenance only, because it has no long-lived keys.
- **Scorecard consequence:** the `Signed-Releases` check stays **unmet by
  design**. Provenance attestations are not the detached signatures that check
  looks for. Expect that line in the Scorecard result; it is not a defect.
- **What provenance is, and is not:** it is a signed statement (not a bare
  hash) about where and how an artefact was built. It does not prove the
  source code is free of defects, and it only helps if consumers actually run
  the verification above.
- The published SHA-256 checksums remain an integrity reference, not proof of
  origin.

## Maintenance notes

- Every workflow under `.github/workflows/` pins its actions to full commit
  SHAs with a version comment (`uses: owner/action@<sha> # vX.Y.Z`).
  `.github/dependabot.yml` proposes updates weekly; re-check each SHA against
  the upstream tag before merging.
- Tools installed in run steps are lock-file pinned too: MkDocs from the
  hash-pinned `.github/requirements/mkdocs.txt` (`pip install --require-hashes`)
  and the Mermaid CLI from `.github/tools/mermaid-cli/package-lock.json`
  (`npm ci`). Edit the `.in` / `package.json` file and regenerate the lock
  rather than editing the lock by hand.
- Every workflow keeps a read-only top-level `GITHUB_TOKEN` and grants write
  scopes only on the job that needs them (Scorecard `Token-Permissions`).
- The Gitea mirror does not run these workflows; attestation and Scorecard
  exist only on GitHub.
