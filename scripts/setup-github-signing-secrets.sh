#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Egothor
# Copyright 2026 Accenture
#
# setup-github-signing-secrets.sh — store an evidence-pack signing keyring and
# its password as GitHub Actions secrets.
#
# Usage:
#   scripts/setup-github-signing-secrets.sh <keyring-file> [<owner/repo>]
#
# Creates (or replaces) two repository secrets with the GitHub CLI:
#   METHODATLAS_KEYRING           the keyring secret bundle (-export-keyring-secret)
#   METHODATLAS_KEYRING_PASSWORD  the keyring password, entered at a hidden prompt
# Override the names with KEYRING_SECRET_NAME / PASSWORD_SECRET_NAME. The
# bundle is piped straight to `gh secret set` and never written to disk.
#
# Requirements: gh (authenticated: `gh auth login`) and a MethodAtlas launcher,
# taken from $METHODATLAS, ../bin next to this script (installed distribution),
# the PATH, or build/install/methodatlas/bin (source checkout).
#
# Then sign in a workflow with:
#   env:
#     METHODATLAS_KEYRING: ${{ secrets.METHODATLAS_KEYRING }}
#     METHODATLAS_KEYRING_PASSWORD: ${{ secrets.METHODATLAS_KEYRING_PASSWORD }}
#   run: methodatlas -evidence-pack ASVS -evidence-pack-keyring-env METHODATLAS_KEYRING ...
#
# Exit codes:
#   0  both secrets stored
#   1  the keyring could not be exported or the password does not unlock it
#      (nothing is stored in either case)
#   2  usage error, empty or mismatched password, or a missing prerequisite
#      (keyring file, gh, gh login, MethodAtlas launcher)

set -euo pipefail

[ $# -ge 1 ] && [ $# -le 2 ] || { sed -n '9,10p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
KEYRING=$1
REPO=${2:-}
KEYRING_SECRET_NAME=${KEYRING_SECRET_NAME:-METHODATLAS_KEYRING}
PASSWORD_SECRET_NAME=${PASSWORD_SECRET_NAME:-METHODATLAS_KEYRING_PASSWORD}

[ -f "$KEYRING" ] || { echo "Keyring not found: $KEYRING" >&2; exit 2; }
command -v gh >/dev/null 2>&1 || { echo "The GitHub CLI 'gh' is required: https://cli.github.com/" >&2; exit 2; }
gh auth status >/dev/null 2>&1 || { echo "Run 'gh auth login' first" >&2; exit 2; }

if [ -z "${METHODATLAS:-}" ]; then
    HERE=$(cd "$(dirname "$0")" && pwd)
    if [ -x "$HERE/../bin/methodatlas" ]; then              # installed distribution
        METHODATLAS="$HERE/../bin/methodatlas"
    elif command -v methodatlas >/dev/null 2>&1; then
        METHODATLAS=methodatlas
    else                                                   # source checkout after installDist
        METHODATLAS="$HERE/../build/install/methodatlas/bin/methodatlas"
    fi
fi
[ -x "$METHODATLAS" ] || command -v "$METHODATLAS" >/dev/null 2>&1 \
    || { echo "MethodAtlas launcher not found; set METHODATLAS or run ./gradlew installDist" >&2; exit 2; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

REPO_ARGS=()
[ -z "$REPO" ] || REPO_ARGS=(--repo "$REPO")
TARGET=${REPO:-the current repository}

read -r -s -p "Keyring password: " PASSWORD; echo
read -r -s -p "Repeat keyring password: " CONFIRM; echo
[ -n "$PASSWORD" ] || { echo "The password must not be empty" >&2; exit 2; }
[ "$PASSWORD" = "$CONFIRM" ] || { echo "The passwords do not match" >&2; exit 2; }
unset CONFIRM

# Export first, so a failure (missing .nonce file, legacy keyring, ...) is shown
# and nothing is stored. The bundle stays in this shell's memory only.
BUNDLE=$("$METHODATLAS" -export-keyring-secret "$KEYRING" 2>"$WORK/export.err") \
    || { cat "$WORK/export.err" >&2; echo "Export failed; nothing was stored" >&2; exit 1; }

# Prove that the password unlocks the keyring with a trial signing of an empty
# pack, so a mistyped password never reaches CI (where it would only produce
# unsigned packs with exit code 0).
echo "Checking the password against the keyring ..."
mkdir "$WORK/src"
METHODATLAS_KEYRING_PASSWORD="$PASSWORD" "$METHODATLAS" -evidence-pack ASVS \
    -evidence-pack-dir "$WORK/pack" -evidence-pack-keyring "$KEYRING" "$WORK/src" >/dev/null 2>"$WORK/check.err" || true
if ! grep -q '"signed"[[:space:]]*:[[:space:]]*true' "$WORK/pack/pack-meta.json" 2>/dev/null; then
    grep -i 'cannot sign\|warning' "$WORK/check.err" | head -3 >&2 || true
    echo "The password does not unlock $KEYRING; nothing was stored" >&2
    exit 1
fi

echo "Storing $KEYRING_SECRET_NAME in $TARGET ..."
printf '%s' "$BUNDLE" | gh secret set "$KEYRING_SECRET_NAME" ${REPO_ARGS[@]+"${REPO_ARGS[@]}"}
unset BUNDLE

echo "Storing $PASSWORD_SECRET_NAME in $TARGET ..."
printf '%s' "$PASSWORD" | gh secret set "$PASSWORD_SECRET_NAME" ${REPO_ARGS[@]+"${REPO_ARGS[@]}"}
unset PASSWORD

echo "Done. Reference both secrets in the workflow step that runs -evidence-pack:"
echo "  env:"
echo "    METHODATLAS_KEYRING: \${{ secrets.$KEYRING_SECRET_NAME }}"
echo "    METHODATLAS_KEYRING_PASSWORD: \${{ secrets.$PASSWORD_SECRET_NAME }}"
echo "  run: methodatlas -evidence-pack <framework> -evidence-pack-keyring-env METHODATLAS_KEYRING ..."
