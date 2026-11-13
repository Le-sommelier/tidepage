#!/usr/bin/env bash
# Clone upstream pdfcraft into third_party/pdfcraft at a pinned commit, then apply every
# scripts/patches/*.patch on top. Idempotent: a clone that is already at the pin and already
# patched is left as it is.
#
# Optional environment:
#   UPSTREAM_DIR  where the clone lives (default: third_party/pdfcraft under the repo root)
#   UPSTREAM_URL  where to clone from (default: the upstream GitHub URL)
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/storytold/pdfcraft}"
UPSTREAM_REV="8d5d39ef5e87abc4012e3a224148fa5958ea1389"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${UPSTREAM_DIR:-$ROOT/third_party/pdfcraft}"
PATCH_DIR="$ROOT/scripts/patches"

patches=()
if [ -d "$PATCH_DIR" ]; then
  for p in "$PATCH_DIR"/*.patch; do
    [ -e "$p" ] && patches+=("$p")
  done
fi

if [ ! -d "$DEST/.git" ]; then
  mkdir -p "$(dirname "$DEST")"
  git clone "$UPSTREAM_URL" "$DEST"
fi

head_rev="$(git -C "$DEST" rev-parse HEAD)"
if [ "$head_rev" != "$UPSTREAM_REV" ]; then
  # Our patches sit in the working tree; take them off first so the checkout cannot trip on them.
  for ((i = ${#patches[@]} - 1; i >= 0; i--)); do
    if git -C "$DEST" apply --check -R "${patches[i]}" 2>/dev/null; then
      git -C "$DEST" apply -R "${patches[i]}"
    fi
  done
  git -C "$DEST" checkout "$UPSTREAM_REV"
fi

# A patch that reverses cleanly is already applied; one that applies cleanly is applied now;
# anything else means the clone has drifted and needs a human.
for p in ${patches[@]+"${patches[@]}"}; do
  if git -C "$DEST" apply --check -R "$p" 2>/dev/null; then
    echo "patch already applied: $(basename "$p")"
  elif git -C "$DEST" apply --check "$p" 2>/dev/null; then
    git -C "$DEST" apply "$p"
    echo "patch applied: $(basename "$p")"
  else
    echo "patch does not apply to $DEST: $(basename "$p")" >&2
    exit 1
  fi
done

echo "pdfcraft pinned at $(git -C "$DEST" rev-parse --short HEAD) in $DEST"
