#!/usr/bin/env bash
# Apply patches/<path/in/tree>/*.patch onto the synced LineageOS tree with `git am`.
# Fails loudly when a patch no longer applies, so upstream rebases are caught by CI.
set -euo pipefail
export LC_ALL=C
SRC="${1:?src dir}"; PATCHES="${2:?patches dir}"
PATCHES="${PATCHES%/}"
[[ -d "$PATCHES" ]] || exit 0
patch_dirs=$(find "$PATCHES" -name '*.patch' -exec dirname {} \; | sort -u)
while IFS= read -r dir; do
  [ -n "$dir" ] || continue
  rel="${dir#"$PATCHES"/}"
  if [[ "$rel" == "$dir" || -z "$rel" ]]; then
    echo "invalid patch directory outside $PATCHES: $dir" >&2
    exit 1
  fi
  if [[ ! -d "$SRC/$rel/.git" && ! -f "$SRC/$rel/.git" ]]; then
    echo "missing git repo for patches: $SRC/$rel" >&2
    exit 1
  fi
  echo ">> $rel"
  git -C "$SRC/$rel" am --abort >/dev/null 2>&1 || true
  base=$(git -C "$SRC/$rel" rev-parse HEAD)
  if ! git -C "$SRC/$rel" -c user.name=ci -c user.email=ci@localhost am --3way "$dir"/*.patch; then
    # Back to where this project was: with the series' first patches left committed, a second
    # run failed on them instead of on the patch that doesn't apply.
    git -C "$SRC/$rel" am --abort >/dev/null 2>&1 || true
    git -C "$SRC/$rel" reset -q --hard "$base"
    echo "patches for $rel do not apply; $rel is back at $base" >&2
    exit 1
  fi
done <<<"$patch_dirs"
