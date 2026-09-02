#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pattern='\b(LaunchedEffect|DisposableEffect|SideEffect)[[:space:]]*\(|import[[:space:]]+androidx\.compose\.runtime\.(LaunchedEffect|DisposableEffect|SideEffect)'
dir="$root"

if command -v rg >/dev/null 2>&1; then
  hits="$(rg -n --glob '*.kt' --glob '!**/build/**' "$pattern" "$dir" || true)"
else
  hits="$(grep -REn --include='*.kt' "$pattern" "$dir" | grep -v '/build/' || true)"
fi

if [[ -n $hits ]]; then
  echo "Compose effect APIs are banned. Load data before render or use an event." >&2
  printf '%s\n' "$hits" >&2
  exit 1
fi
