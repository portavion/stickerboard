#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

mirror="${1:-${STICKERBOARD_MIRROR:-$HOME/projects/portavion.github.io}}"
html="$root/docs/privacy/index.html"
url="https://portavion.github.io/stickerboard/privacy/"

if [[ ! -f $html ]]; then
  echo "missing $html" >&2
  exit 1
fi

./gradlew :app:testDebugUnitTest --tests 'com.portavion.stickerboard.PrivacyDocTest'

if [[ ! -d $mirror/.git ]]; then
  echo "Mirror repo not found at $mirror. Clone portavion.github.io there or set STICKERBOARD_MIRROR." >&2
  exit 1
fi

dest="$mirror/stickerboard/privacy"
mkdir -p "$dest"
cp "$html" "$dest/index.html"
touch "$mirror/.nojekyll"

if [[ -n $(git -C "$mirror" status --porcelain) ]]; then
  git -C "$mirror" add stickerboard/privacy/index.html .nojekyll
  effective="$(sed -n 's/.*Effective \([0-9][0-9-]*\).*/\1/p' "$html" | head -1)"
  git -C "$mirror" commit -m "stickerboard privacy notice ${effective:-update}"
  git -C "$mirror" push
fi

for _ in $(seq 1 24); do
  if curl -fsSL "$url" | cmp -s - "$html"; then
    echo "Live page matches $html"
    exit 0
  fi
  sleep 5
done

echo "GitHub Pages is not serving the committed HTML yet at $url. Re-run after Pages catches up." >&2
exit 1
