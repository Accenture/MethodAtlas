# CI/CD environment setup

This guide describes every secret, variable, and one-time configuration step
required to make all workflows operational on both **Gitea** and **GitHub**.
Items are grouped by platform and sorted from mandatory to optional within
each section.


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
| `CODECOV_TOKEN` | Same as Gitea — upload token for private repositories. | See Gitea section above. |

### 3. Code Scanning (automatic — no secrets required)

`pages.yml` calls the reusable workflow
[`methodatlas-analysis.yml`](https://github.com/Accenture/MethodAtlas/blob/main/.github/workflows/methodatlas-analysis.yml)
on every push to `main`.  MethodAtlas classifies its own JUnit test methods
using **GitHub Models** authenticated with the `GITHUB_TOKEN` that is
automatically available in every GitHub Actions run — no additional secrets
or billing setup is required.

The resulting SARIF is uploaded to GitHub Code Scanning and appears under
**Security → Code scanning** after the first successful run.  Findings are
also retained as a downloadable workflow artifact for 30 days.

The `security-scan.yml` workflow additionally uploads SpotBugs results to
Code Scanning on a weekly schedule.  No configuration is needed for either
workflow beyond the `security-events: write` permission already declared in
`pages.yml`.

### 4. Adapting the MethodAtlas analysis workflow for your own project

The [`methodatlas-analysis.yml`](https://github.com/Accenture/MethodAtlas/blob/main/.github/workflows/methodatlas-analysis.yml)
workflow is designed to be copied and adapted.  The inline comments mark
exactly which parts to change:

**Replace build-from-source with a release download** (the most common
adaptation): in the workflow file, swap the three steps labelled
"Set up JDK 21", "Setup Gradle", and "Build MethodAtlas from source" for the
download snippet provided in the comment block inside the
"Obtain MethodAtlas" section.

**Point to your test sources**: change the `test-source-path` input when
calling the workflow, or pass a different path in the
"Run MethodAtlas AI classification" step.

**Use a different AI model or provider**: set the `ai-model` input, or
replace `-ai-provider github_models` with any of the providers listed in
[AI providers](ai/providers.md).  Cloud providers other than GitHub Models
require an API key stored as a repository secret.

To call the reusable workflow from your own `build.yml` or equivalent:

```yaml
jobs:
  methodatlas-analysis:
    needs: build   # run after your own build job
    uses: Accenture/MethodAtlas/.github/workflows/methodatlas-analysis.yml@main
    with:
      test-source-path: src/test/java
      ai-model: gpt-4o-mini
    permissions:
      contents: read
      security-events: write
```


## Workflow schedule reference

| Workflow | Platform | Trigger |
|---|---|---|
| CI quality gate | Gitea | Every push and pull request |
| Release | Gitea + GitHub | Push of a `release@x.y.z` tag |
| GitHub Pages + MethodAtlas self-analysis | GitHub | Push to `main` branch |
| OWASP + SpotBugs Code Scanning | GitHub | Every Monday at 03:00 UTC; manual dispatch |
| OWASP Security Scan | Gitea | Every Monday at 03:00 UTC; manual dispatch |
| PIT Mutation Testing | Gitea + GitHub | Every Sunday at 04:00 UTC; manual dispatch |


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
