#!/usr/bin/env bash
# Renders the desktop tray icon (desktop/icons/coffer-tray.svg) to the PNGs the tray loads. The PNGs are in
# git, so a build needs no SVG renderer; run this after changing the SVG, with librsvg's rsvg-convert.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
SVG="$ROOT/desktop/icons/coffer-tray.svg"
OUT="$ROOT/desktop/src/main/resources/me/esdex/coffer/desktop/tray"
command -v rsvg-convert >/dev/null || { echo "Error: rsvg-convert is missing (librsvg)" >&2; exit 1; }
mkdir -p "$OUT"
for size in 16 22 24 32 48 64 128; do
  rsvg-convert -w "$size" -h "$size" "$SVG" -o "$OUT/chest-$size.png"
done
echo "Done: $OUT"
