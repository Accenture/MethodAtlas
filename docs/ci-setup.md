# CI/CD environment setup

This guide describes every secret, variable, and one-time configuration step
required to make all workflows operational on both **Gitea** and **GitHub**.
Items are grouped by platform and sorted from mandatory to optional within
each section.

---

## Gitea

Gitea runs CI, releases, mutation testing, and security scans via its built-in
Actions engine.  You need at least one registered runner before any workflow
executes.

### 1. Register an Actions runner (mandatory)

Gitea requires a self-hosted `act_runner` because it does not provide
cloud-hosted runners.

1. Install the runner binary on a Linux host (or Docker):
   ```
   https://gitea.com/gitea/act_runner/releases
   ```
2. In the Gitea UI go to **Repository → Settings → Actions → Runners**,
   click **Create new runner**, and copy the registration token.
3. Register the runner:
   ```bash
   act_runner register \
     --instance https://<your-gitea-host> \
     --token    <registration-token> \
     --name     my-runner \
     --labels   ubuntu-latest
   ```
4. Start the runner and verify it appears as *Online* in the Gitea UI.

The label `ubuntu-latest` must match the `runs-on:` value in all workflow
files; change both consistently if you prefer a different label.

### 2. Secrets (Settings → Secrets and Variables → Actions → Secrets)

#### Mandatory

| Secret | Purpose |
|---|---|
| *(none)* | All Gitea Actions workflows work without any manually configured secret. `GITHUB_TOKEN` is automatically injected by the runner and grants the permissions each workflow declares. |

#### Optional — adds features when present

| Secret | Feature unlocked | How to obtain |
|---|---|---|
| `NVD_API_KEY` | Enables the OWASP Dependency-Check scan. When absent, both the scan and the report upload steps are **completely skipped** and the workflow finishes successfully without them. | Register at **https://nvd.nist.gov/developers/request-an-api-key** — free, no organisation required. You receive the key by e-mail within minutes. |
| `CODECOV_TOKEN` | Coverage data is uploaded to Codecov for trend tracking and PR delta comments. Not required for public repositories (Codecov accepts public-repo uploads without a token). | Log in at **https://codecov.io** with your Gitea account via GitHub OAuth, add the repository, and copy the Upload Token from the repository settings page. |

---

## GitHub

GitHub mirrors the Gitea repository and is used exclusively for read-only
artefacts: GitHub Releases, GitHub Pages, and Code Scanning results.
Developers do **not** push directly to GitHub; Gitea handles all development
activity and mirrors tags and commits automatically.

### 1. Enable GitHub Pages (mandatory for the Pages workflow)

The `pages.yml` workflow deploys Javadoc, JaCoCo, PMD, SpotBugs, and test
reports to GitHub Pages.

1. In the GitHub repository go to **Settings → Pages**.
2. Under *Source* select **GitHub Actions**.
3. Save.  No branch selection is needed; the workflow deploys via the
   `actions/deploy-pages` action.

The published site will be available at
`https://<org>.github.io/<repo>/`.

### 2. Secrets (Settings → Secrets and Variables → Actions → Secrets)

#### Mandatory

| Secret | Purpose |
|---|---|
| `GITHUB_TOKEN` | Automatically injected by GitHub Actions. Used by `release.yml` to create GitHub Releases and upload distribution assets. No configuration required. |

#### Optional — adds features when present

| Secret | Feature unlocked | How to obtain |
|---|---|---|
| `NVD_API_KEY` | Same as Gitea — enables the OWASP Dependency-Check scan; steps are skipped when absent. | See Gitea section above — the same key works on both platforms. |
| `OPENROUTER_API_KEY` | Enables MethodAtlas AI self-analysis in `security-scan.yml`. The three MethodAtlas steps (build, analyse, upload SARIF) are **completely skipped** when this secret is absent; no partial results are written. The SARIF output is uploaded to GitHub Code Scanning and appears in the Security tab. | Register at **https://openrouter.ai** — free tier available. Go to **Account → API Keys → Create key**. The default model (`stepfun/step-3.5-flash:free`) is free; no billing setup required unless you switch to a paid model. |
| `CODECOV_TOKEN` | Same as Gitea — upload token for private repositories. | See Gitea section above. |

### 3. Variables (Settings → Secrets and Variables → Actions → Variables)

Variables are non-secret configuration values visible in workflow logs.

#### Optional

| Variable | Default when absent | Purpose |
|---|---|---|
| `METHODATLAS_AI_MODEL` | `stepfun/step-3.5-flash:free` | Overrides the OpenRouter model used for MethodAtlas AI self-analysis. Set this to switch models (e.g. `openai/gpt-4o-mini`) without editing the workflow YAML or creating a new commit. Has no effect when `OPENROUTER_API_KEY` is not set. |

### 4. Code Scanning (automatic)

The `security-scan.yml` workflow uploads SpotBugs and (optionally)
MethodAtlas SARIF results to GitHub Code Scanning.  No additional
configuration is needed beyond the `security-events: write` permission
already declared in the workflow.  Findings appear automatically under
**Security → Code scanning** after the first successful workflow run.

---

## Workflow schedule reference

| Workflow | Platform | Trigger |
|---|---|---|
| CI quality gate | Gitea | Every push and pull request |
| Release | Gitea + GitHub | Push of a `release@x.y.z` tag |
| GitHub Pages | GitHub | Push to `main` branch |
| OWASP + Code Scanning | GitHub | Every Monday at 03:00 UTC; manual dispatch |
| OWASP Security Scan | Gitea | Every Monday at 03:00 UTC; manual dispatch |
| PIT Mutation Testing | Gitea + GitHub | Every Sunday at 04:00 UTC; manual dispatch |

---

## Creating a release

Releases are tagged in Gitea.  The tag is mirrored to GitHub automatically,
triggering the release workflow on both platforms.

```bash
# Conventional: tag message is used by git-cliff for the changelog header
git tag -a release@1.2.3 -m "release: 1.2.3"
git push origin release@1.2.3
```

Gitea produces a Gitea Release; GitHub produces a GitHub Release.  Both
attach the distribution archives (`zip`, `tar`) and the CycloneDX SBOM
(`bom.json`).  The release notes are generated automatically from
Conventional Commit messages via `git-cliff` (configuration in `cliff.toml`).
