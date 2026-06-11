#!/usr/bin/env bash
# Writes keystore.properties (gitignored) from a KeePassXC entry, so the
# keystore password never sits in shell history or a dotfile you might sync.
#
# Usage:
#   scripts/make-keystore-properties.sh <db.kdbx> <entry-name> [keystore.jks]
#
# Example:
#   scripts/make-keystore-properties.sh ~/Passwords.kdbx marmalade-upload \
#       ~/secure/marmalade-upload.jks
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

echo "Wrote $OUT (mode 600). It is gitignored — never commit it."
echo "Build a signed release with: ./gradlew :app:bundleRelease"
