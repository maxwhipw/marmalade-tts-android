#!/usr/bin/env bash
# Writes keystore.properties (gitignored) from a KeePassXC entry, so the
# keystore password never sits in shell history or a dotfile you might sync.
#
# Usage:
#   scripts/make-keystore-properties.sh <db.kdbx> <entry-name> [keystore.jks] \
#       [dist-entry-name] [dist-keystore.jks]
#
# Example (upload key only):
#   scripts/make-keystore-properties.sh ~/Passwords.kdbx marmalade-upload \
#       ~/secure/marmalade-upload.jks
#
# Example (with the dist quartet — a real distribution build needs it too;
# decided 2026-08-09 the dist key IS the upload key, so both quartets point
# at the same keystore, see docs/release/CI-SIGNING.md):
#   scripts/make-keystore-properties.sh ~/Passwords.kdbx marmalade-upload \
#       ~/secure/marmalade-upload.jks marmalade-upload ~/secure/marmalade-upload.jks
#
# The KeePassXC entry's Password field must hold the keystore password
# (the same password is used for the store and the key — that's how
# keytool's default PKCS12 format works). The entry name doubles as the
# key alias unless the entry has a custom attribute "keyAlias".
#
# See docs/release/SIGNING.md for the one-time keystore generation steps.

set -euo pipefail

DB=${1:?usage: $0 <keepassxc-db.kdbx> <entry-name> [keystore.jks]}
ENTRY=${2:?usage: $0 <keepassxc-db.kdbx> <entry-name> [keystore.jks]}
KEYSTORE=${3:-$HOME/secure/marmalade-upload.jks}

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$REPO_ROOT/keystore.properties"

if ! command -v keepassxc-cli >/dev/null 2>&1; then
    echo "keepassxc-cli not found. It ships with KeePassXC — on Debian/Ubuntu" >&2
    echo "it's part of the 'keepassxc' package; flatpak users can use" >&2
    echo "  flatpak run --command=keepassxc-cli org.keepassxc.KeePassXC ..." >&2
    exit 1
fi

if [ ! -f "$KEYSTORE" ]; then
    echo "Keystore not found at $KEYSTORE — generate it first (docs/release/SIGNING.md)." >&2
    exit 1
fi

PASSWORD=$(keepassxc-cli show -s -a Password "$DB" "$ENTRY")
ALIAS=$(keepassxc-cli show -a keyAlias "$DB" "$ENTRY" 2>/dev/null || echo "$ENTRY")

umask 077
cat > "$OUT" <<EOF
storeFile=$KEYSTORE
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF

# Optional second quartet: the distribution key that signs the fdroid
# flavor (permanent F-Droid/GitHub identity — currently the same keystore
# as the upload key).
if [ $# -ge 4 ]; then
    DIST_ENTRY=$4
    DIST_KEYSTORE=${5:-$HOME/secure/marmalade-upload.jks}
    if [ ! -f "$DIST_KEYSTORE" ]; then
        echo "Dist keystore not found at $DIST_KEYSTORE — generate it first (docs/release/CI-SIGNING.md)." >&2
        exit 1
    fi
    DIST_PASSWORD=$(keepassxc-cli show -s -a Password "$DB" "$DIST_ENTRY")
    DIST_ALIAS=$(keepassxc-cli show -a keyAlias "$DB" "$DIST_ENTRY" 2>/dev/null || echo "$DIST_ENTRY")
    cat >> "$OUT" <<EOF
distStoreFile=$DIST_KEYSTORE
distStorePassword=$DIST_PASSWORD
distKeyAlias=$DIST_ALIAS
distKeyPassword=$DIST_PASSWORD
EOF
fi

echo "Wrote $OUT (mode 600). It is gitignored — never commit it."
echo "Build a signed release with: ./gradlew :app:bundleRelease"
