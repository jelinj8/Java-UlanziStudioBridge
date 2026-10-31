#!/usr/bin/env bash
# Builds the installable plugin archive: dist/deck-bridge-plugin-<version>.zip, holding the plugin
# folder with its production dependencies. Unzip it into Studio's Plugins folder. Uses `jar` to
# create the archive because the CI runner image has no zip.
#
# Usage: scripts/package-plugin.sh <version>
set -euo pipefail

VERSION="${1:?usage: package-plugin.sh <version>}"
PLUGIN=com.ulanzi.deckbridge.ulanziPlugin
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/dist/stage"

rm -rf "$STAGE"
mkdir -p "$STAGE/$PLUGIN"
cd "$ROOT/bridge-plugin/$PLUGIN"
cp -r assets libs plugin property-inspector manifest.json package.json package-lock.json "$STAGE/$PLUGIN/"
cp "$ROOT/LICENSE" "$STAGE/$PLUGIN/"

cd "$STAGE/$PLUGIN"
npm ci --omit=dev --no-audit --no-fund
# Studio shows this version; a snapshot keeps the one in the source manifest
case "$VERSION" in
  *-SNAPSHOT) ;;
  *) node -e '
    const fs = require("fs");
    const manifest = JSON.parse(fs.readFileSync("manifest.json", "utf8"));
    manifest.Version = process.argv[1];
    fs.writeFileSync("manifest.json", JSON.stringify(manifest, null, 2) + "\n");
  ' "$VERSION" ;;
esac

cd "$STAGE"
jar --create --no-manifest --file "../deck-bridge-plugin-$VERSION.zip" "$PLUGIN"
cd "$ROOT"
rm -rf "$STAGE"
echo "dist/deck-bridge-plugin-$VERSION.zip"
