#!/usr/bin/env bash
# Renders the desktop's icons to the PNGs the app and its package use: the tray's chest (desktop/icons/coffer-tray.svg)
# and the app's own icon, Coffer's logo (assets/coffer-logo.svg), in the sizes of the hicolor theme. The PNGs are in
# git, so a build needs no SVG renderer; run this after changing either SVG, with librsvg's rsvg-convert.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
command -v rsvg-convert >/dev/null || { echo "Error: rsvg-convert is missing (librsvg)" >&2; exit 1; }

SVG="$ROOT/desktop/icons/coffer-tray.svg"
OUT="$ROOT/desktop/src/main/resources/me/esdex/coffer/desktop/tray"
mkdir -p "$OUT"
for size in 16 22 24 32 48 64 128; do
  rsvg-convert -w "$size" -h "$size" "$SVG" -o "$OUT/chest-$size.png"
done
echo "Done: $OUT"

LOGO="$ROOT/assets/coffer-logo.svg"
APP="$ROOT/desktop/icons/app"
mkdir -p "$APP"
for size in 16 22 24 32 48 64 128 256 512; do
  rsvg-convert -w "$size" -h "$size" "$LOGO" -o "$APP/coffer-$size.png"
done
echo "Done: $APP"
