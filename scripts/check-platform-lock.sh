#!/usr/bin/env bash
# platform.lock and ci.yml name the same platform commit: platform.lock holds exactly one
# easypeasyops/platform@<sha>, and every reusable workflow ci.yml calls and every platform
# checkout it makes (`ref: <sha> # platform.lock`) is at that commit. Renovate moves them
# together; this catches a hand edit that moves one and not the others. Run from the root.
set -euo pipefail

lock=platform.lock ci=.github/workflows/ci.yml
locked=$(sed -nE 's/^easypeasyops\/platform@([0-9a-f]{40})$/\1/p' "$lock")
[[ $(grep -c . <<<"$locked") == 1 ]] ||
  { echo "::error file=$lock::needs exactly one easypeasyops/platform@<40-hex sha> line"; exit 1; }

refs=$(grep -oE 'easypeasyops/platform/\.github/workflows/[A-Za-z0-9._-]+@[^ ]+|ref: [^ ]+ # platform\.lock' "$ci" |
  sed -E 's/^.*@//; s/^ref: ([^ ]+) .*/\1/')
[[ -n "$refs" ]] || { echo "::error file=$ci::calls no platform workflow"; exit 1; }
bad=0
while read -r ref; do
  [[ "$ref" == "$locked" ]] || { echo "::error file=$ci::platform ref $ref is not platform.lock's $locked"; bad=1; }
done <<<"$refs"
((bad == 0)) && echo "platform.lock and ci.yml: $(grep -c . <<<"$refs") refs, all at $locked"
exit "$bad"
