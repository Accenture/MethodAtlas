#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Egothor
# Copyright 2026 Accenture
#
# verify-evidence-pack.sh — offline verification of a MethodAtlas evidence pack.
#
# Usage:
#   scripts/verify-evidence-pack.sh <pack-dir> [<public-key.pem>]
#
# Checks, using only OpenSSL (3.0+) and coreutils. macOS ships LibreSSL as
# /usr/bin/openssl, which cannot verify these signatures; install OpenSSL 3
# (e.g. `brew install openssl@3`) and point OPENSSL at it if it is not first
# on the PATH:  OPENSSL=/opt/homebrew/opt/openssl@3/bin/openssl scripts/...
#   1. every file listed in manifest.sha256 exists and matches its SHA-256 digest;
#   2. no file in the pack is missing from the manifest;
#   3. manifest.sha256.signed is exactly manifest.sha256 followed by a signature;
#   4. that signature verifies with <public-key.pem> for the algorithm recorded in
#      pack-meta.json (Ed25519, RSA = RSA-PSS/SHA-256, ECDSA = P-256/SHA-256).
#      For a hybrid pack (e.g. Ed25519+SPHINCS+) pass the classical key's PEM: the
#      classical half is verified and the result is PARTIAL (exit 2), because the
#      post-quantum half needs ZeroEcho.
#
# The public key must come from an authenticated channel (your own copy of the
# <alias>-public.pem written by -gen-signing-key), never from the pack itself.
# Pure post-quantum signatures need ZeroEcho; the script says so and stops.
#
# Exit codes:
#   0  integrity and signature verified
#   1  verification failed (tampered, missing or unlisted file, bad signature)
#   2  usage error, missing or unsuitable tool (no OpenSSL 3), or pack not fully
#      authenticated (unsigned pack, no public key given, a post-quantum algorithm
#      that needs ZeroEcho, or only the classical half of a hybrid verified)

set -euo pipefail

usage() {
    sed -n '8,9p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

fail() { echo "FAIL: $*" >&2; exit 1; }
note() { echo "  $*"; }

[ $# -ge 1 ] && [ $# -le 2 ] || usage
PACK_DIR=$1
PUBLIC_KEY=${2:-}

[ -d "$PACK_DIR" ] || { echo "Not a directory: $PACK_DIR" >&2; exit 2; }
if [ -n "$PUBLIC_KEY" ]; then
    [ -f "$PUBLIC_KEY" ] || { echo "Public key not found: $PUBLIC_KEY" >&2; exit 2; }
    PUBLIC_KEY=$(cd "$(dirname "$PUBLIC_KEY")" && pwd)/$(basename "$PUBLIC_KEY")
fi

if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
    sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    echo "Neither sha256sum nor shasum is available" >&2; exit 2
fi

cd "$PACK_DIR"
[ -f manifest.sha256 ] || fail "manifest.sha256 is missing"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# --- 1. Every listed file matches its digest --------------------------------
echo "Checking file digests in $PACK_DIR/manifest.sha256"
LISTED="$WORK/listed"
: > "$LISTED"
while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    [ -n "$line" ] || continue
    expected=${line%%  *}
    name=${line#*  }
    [ "$expected" != "$line" ] || fail "malformed manifest line: $line"
    printf '%s\n' "$name" >> "$LISTED"
    [ -f "$name" ] || fail "listed file is missing: $name"
    actual=$(sha256 "$name")
    [ "$actual" = "$expected" ] || fail "digest mismatch: $name"
    note "ok  $name"
done < manifest.sha256

# --- 2. No unlisted files ---------------------------------------------------
for file in * .[!.]*; do
    [ -e "$file" ] || continue
    case "$file" in manifest.sha256|manifest.sha256.signed) continue ;; esac
    grep -Fxq -- "$file" "$LISTED" || fail "file not covered by the manifest: $file"
done
echo "Integrity: all artefacts match manifest.sha256"

# --- 3./4. Signature ---------------------------------------------------------
if [ ! -f manifest.sha256.signed ]; then
    echo "NOT AUTHENTICATED: the pack is unsigned (no manifest.sha256.signed)"
    exit 2
fi

ALGO=$(grep -o '"signatureAlgorithm"[[:space:]]*:[[:space:]]*"[^"]*"' pack-meta.json 2>/dev/null \
    | sed 's/.*"\([^"]*\)"$/\1/' || true)
[ -n "$ALGO" ] || ALGO=Ed25519
echo "Signature algorithm (from pack-meta.json): $ALGO"

BODY_LEN=$(wc -c < manifest.sha256 | tr -d ' ')
SIGNED_LEN=$(wc -c < manifest.sha256.signed | tr -d ' ')
[ "$SIGNED_LEN" -gt "$BODY_LEN" ] || fail "manifest.sha256.signed carries no signature trailer"
head -c "$BODY_LEN" manifest.sha256.signed > "$WORK/body"
tail -c "+$((BODY_LEN + 1))" manifest.sha256.signed > "$WORK/sig"
cmp -s "$WORK/body" manifest.sha256 || fail "manifest.sha256.signed does not carry this manifest.sha256"

if [ -z "$PUBLIC_KEY" ]; then
    echo "NOT AUTHENTICATED: no public key given; pass the signer's <alias>-public.pem to verify the signature"
    exit 2
fi
OPENSSL=${OPENSSL:-openssl}
command -v "$OPENSSL" >/dev/null 2>&1 || { echo "OpenSSL 3 is required to verify the signature" >&2; exit 2; }
OPENSSL_VERSION=$("$OPENSSL" version 2>/dev/null || true)
case "$OPENSSL_VERSION" in
    "OpenSSL "[3-9].*|"OpenSSL "[1-9][0-9].*) ;;
    *)
        echo "CANNOT VERIFY: '$OPENSSL' is '${OPENSSL_VERSION:-unknown}', but OpenSSL 3.0 or newer is required;" \
             "install it and set OPENSSL=/path/to/openssl (integrity checks above passed)" >&2
        exit 2 ;;
esac

# verify_classic <algorithm> <signature-file> <message-file>: returns 0 when the
# classical signature verifies with $PUBLIC_KEY.
verify_classic() {
    case "$1" in
        Ed25519|ED25519|ed25519)
            "$OPENSSL" pkeyutl -verify -pubin -inkey "$PUBLIC_KEY" -rawin -in "$3" -sigfile "$2" >/dev/null 2>&1 ;;
        RSA|RSA-PSS|RSASSA-PSS)
            "$OPENSSL" dgst -sha256 -sigopt rsa_padding_mode:pss -sigopt rsa_pss_saltlen:32 \
                -verify "$PUBLIC_KEY" -signature "$2" "$3" >/dev/null 2>&1 ;;
        ECDSA)
            # ZeroEcho writes ECDSA P-256 signatures as raw r||s (IEEE P1363, 64 bytes);
            # openssl expects ASN.1 DER, so re-encode before verifying.
            if [ "$(wc -c < "$2" | tr -d ' ')" -eq 64 ]; then
                HEX=$(od -An -v -tx1 "$2" | tr -d ' \n')
                printf 'asn1=SEQUENCE:sig\n[sig]\nr=INTEGER:0x%s\ns=INTEGER:0x%s\n' \
                    "${HEX:0:64}" "${HEX:64:64}" > "$WORK/sig.cnf"
                "$OPENSSL" asn1parse -genconf "$WORK/sig.cnf" -out "$WORK/sig.der" -noout >/dev/null 2>&1 || return 1
            else
                cp "$2" "$WORK/sig.der"
            fi
            "$OPENSSL" dgst -sha256 -verify "$PUBLIC_KEY" -signature "$WORK/sig.der" "$3" >/dev/null 2>&1 ;;
        *)
            return 3 ;;
    esac
}

# classic_length <algorithm>: byte length of a classical signature for $PUBLIC_KEY.
classic_length() {
    case "$1" in
        Ed25519|ED25519|ed25519|ECDSA) echo 64 ;;
        RSA|RSA-PSS|RSASSA-PSS)
            bits=$("$OPENSSL" pkey -pubin -in "$PUBLIC_KEY" -noout -text 2>/dev/null \
                | sed -n 's/.*(\([0-9][0-9]*\) bit).*/\1/p' | head -1)
            [ -n "$bits" ] && echo $(( (bits + 7) / 8 )) ;;
        *) ;;
    esac
}

case "$ALGO" in
    *+*)
        # Hybrid trailer = classical signature || post-quantum signature. The classical
        # half can be checked with openssl; the AND rule needs ZeroEcho for the PQC half.
        CLASSIC=${ALGO%%+*}
        PQC=${ALGO#*+}
        LEN=$(classic_length "$CLASSIC")
        if [ -z "$LEN" ]; then
            echo "NOT AUTHENTICATED: $ALGO signatures cannot be checked with openssl; use ZeroEcho"
            exit 2
        fi
        head -c "$LEN" "$WORK/sig" > "$WORK/sig.classic"
        verify_classic "$CLASSIC" "$WORK/sig.classic" "$WORK/body" \
            || fail "the $CLASSIC half of the $ALGO signature does not verify with $PUBLIC_KEY"
        echo "PARTIAL: the $CLASSIC half of the hybrid signature is valid and every artefact matches the" \
             "signed manifest; the $PQC half (AND rule) must be verified with ZeroEcho"
        exit 2 ;;
    *)
        rc=0
        verify_classic "$ALGO" "$WORK/sig" "$WORK/body" || rc=$?
        if [ "$rc" -eq 3 ]; then
            echo "NOT AUTHENTICATED: $ALGO signatures cannot be verified with openssl; use ZeroEcho"
            exit 2
        fi
        [ "$rc" -eq 0 ] || fail "$ALGO signature does not verify with $PUBLIC_KEY" ;;
esac

echo "VERIFIED: signature valid ($ALGO) and every artefact matches the signed manifest"
