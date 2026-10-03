#!/usr/bin/env bash
# Unit tests for prune-select.jq -- the part of the GHCR prune that decides which tagged image
# versions die. Pure jq: no network, no token. Runs locally (`bash .github/scripts/test-prune-select.sh`)
# and as the first step of prune-storage's ghcr job, so a regression fails the job before anything
# is deleted.
set -euo pipefail

FILTER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/prune-select.jq"
NONE_YOUNG="9999-01-01T00:00:00Z"   # an age cutoff no version meets

# 14 builds, one a day from 2026-09-01: a tagged index (id N, tag "shaN") plus the two untagged
# children buildx really publishes (ids 100+N, 200+N). Build 14 also carries `latest`.
FIXTURE="$(jq -n '
  [ range(1; 15) as $n
    | ("2026-09-" + (if $n < 10 then "0" else "" end) + ($n | tostring)) as $day
    | {id: $n,         digest: "sha256:index\($n)",  created_at: "\($day)T12:00:05Z", tags: ["sha\($n)"]},
      {id: (100 + $n), digest: "sha256:image\($n)",  created_at: "\($day)T12:00:04Z", tags: []},
      {id: (200 + $n), digest: "sha256:attest\($n)", created_at: "\($day)T12:00:04Z", tags: []}
  ]
  | map(if .id == 14 then .tags += ["latest"] else . end)')"

fails=0; n=0
# check NAME KEEP PROTECT_JSON CUTOFF ASSERTION [INPUT]
#   ASSERTION is a jq expression over the filter's output that must evaluate to true.
check() {
  local name="$1" keep="$2" protect="$3" cutoff="$4" assertion="$5" input="${6:-$FIXTURE}" out
  n=$((n + 1))
  out="$(jq -c --argjson keep "$keep" --argjson protect "$protect" --arg cutoff "$cutoff" \
          -f "$FILTER" <<<"$input")" || out='{}'
  if jq -e "$assertion" <<<"$out" >/dev/null 2>&1; then
    echo "ok   $n - $name"
  else
    echo "FAIL $n - $name"
    echo "     want: $assertion"
    echo "     got:  keep=$(jq -c '[.keep[]?.id]' <<<"$out") delete=$(jq -c '[.delete[]?.id]' <<<"$out") has_latest=$(jq -c .has_latest <<<"$out") unmatched=$(jq -c .unmatched_protect <<<"$out")"
    fails=$((fails + 1))
  fi
}

check "keeps the newest KEEP tagged versions and deletes the rest" 10 '[]' "$NONE_YOUNG" \
  '[.keep[].id] == [14,13,12,11,10,9,8,7,6,5] and [.delete[].id] == [4,3,2,1]'
check "never selects an untagged version (the reference walk owns those)" 0 '[]' "$NONE_YOUNG" \
  '[.keep[].id, .delete[].id] | all(. < 100)'
check "keep and delete partition the tagged versions exactly" 10 '["sha2"]' "2026-09-12T00:00:00Z" \
  '([.keep[].id] + [.delete[].id] | sort) == [range(1; 15)]'
LATEST_OLD="$(jq -c 'map(.tags -= ["latest"] | if .id == 2 then .tags += ["latest"] else . end)' <<<"$FIXTURE")"
check "whatever carries latest survives even outside the newest KEEP" 10 '[]' "$NONE_YOUNG" \
  'any(.keep[].id; . == 2) and [.delete[].id] == [4,3,1]' "$LATEST_OLD"
check "a deploy-tag sha survives however many builds landed after it" 10 '["sha1"]' "$NONE_YOUNG" \
  'any(.keep[].id; . == 1) and [.delete[].id] == [4,3,2]'
check "anything newer than the age cutoff survives a burst bigger than KEEP" 10 '[]' "2026-09-03T00:00:00Z" \
  '[.delete[].id] == [2,1]'
check "KEEP=0 still never deletes latest" 0 '[]' "$NONE_YOUNG" \
  '[.keep[].id] == [14] and (.delete | length) == 13'
NO_LATEST="$(jq -c 'map(.tags -= ["latest"])' <<<"$FIXTURE")"
check "reports when nothing carries latest (the caller refuses to prune)" 10 '[]' "$NONE_YOUNG" \
  '.has_latest == false' "$NO_LATEST"
check "reports protected tags that match no image, still honours the ones that do" 10 '["sha3","deadbeef"]' "$NONE_YOUNG" \
  '.unmatched_protect == ["deadbeef"] and any(.keep[].id; . == 3)'
check "input order does not matter" 10 '[]' "$NONE_YOUNG" \
  '[.keep[].id] == [14,13,12,11,10,9,8,7,6,5]' "$(jq -c 'reverse' <<<"$FIXTURE")"

echo "== $n checks, $fails failed"
(( fails == 0 ))
