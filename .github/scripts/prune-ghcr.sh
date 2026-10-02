#!/usr/bin/env bash
#
# Manifest-aware GHCR prune for the bmsmon-server container package.
#
# WHY THIS IS NOT THE ONE-LINER YOU EXPECT
# ----------------------------------------
# Every build pushes ONE tag but creates THREE package versions, because
# buildx publishes an OCI *index*:
#
#     :latest  ->  OCI index                       (the "tagged" version)
#                    |- sha256:9d55f8...  amd64 image      <- shows as UNTAGGED
#                    `- sha256:7cb69a...  provenance att.  <- shows as UNTAGGED
#
# So the widely-copied recipe `delete-only-untagged-versions: true` deletes the
# actual image layers out from under :latest, and the next
# `docker compose pull bmsmon-api` on the NAS fails with a manifest-unknown
# error. Confirmed against this package on 2026-08-03.
#
# This script therefore resolves each SURVIVING index's children from the
# registry and only deletes an untagged version once nothing still points at it.
# If a manifest cannot be resolved it aborts rather than guess.
#
# WHAT SURVIVES
# -------------
# Decided by the pure prune-select.jq (unit-tested by test-prune-select.sh, which the
# CI job runs first). A tagged version survives if ANY of these holds:
#   - it is among the newest KEEP tagged versions (default 10);
#   - it carries `latest` (what a plain deploy pulls);
#   - it is younger than MIN_AGE_DAYS (default 30) -- one busy day has produced 12
#     builds, more than KEEP, and none may vanish before anyone could deploy it;
#   - it is the image of one of the newest PROTECT_DEPLOYS (default 3) `deploy/*` git
#     tags -- the deploy procedure (CLAUDE.md "Production deploy") tags each deployed
#     commit, so the running image and its rollback targets survive however many
#     builds land after them;
#   - it carries a tag listed in PROTECT_TAGS (space-separated manual pins).
# It refuses to prune at all if nothing carries `latest`, and a REAL run also refuses when
# the newest `deploy/*` tag matches no image version (the registry cannot know what the NAS
# runs, so without that tag the prune would be blind; a dry run only warns). It re-resolves
# every survivor and each child it references (before exiting a dry run, after real deletes).
#
# Usage:
#   DRY_RUN=1 .github/scripts/prune-ghcr.sh     # report only (default)
#   DRY_RUN=0 KEEP=10 .github/scripts/prune-ghcr.sh
#
# Needs a token with delete:packages (local `gh auth`, or GH_TOKEN in CI); reading the
# deploy/* tags needs only read access to this public repo.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OWNER="${OWNER:-mkeguy106}"
REPO="${REPO:-bmsmon}"
PKG="${PKG:-bmsmon-server}"
KEEP="${KEEP:-10}"                       # newest tagged versions to retain (rollback headroom)
MIN_AGE_DAYS="${MIN_AGE_DAYS:-30}"       # never delete a tagged version younger than this
PROTECT_DEPLOYS="${PROTECT_DEPLOYS:-3}"  # newest deploy/* git tags whose images are pinned
PROTECT_TAGS="${PROTECT_TAGS:-}"         # extra image tags to pin, space-separated
DRY_RUN="${DRY_RUN:-1}"                  # default to safe
ACCEPT="application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json"

echo "== ghcr.io/$OWNER/$PKG — keep newest $KEEP tagged, anything < $MIN_AGE_DAYS d old, newest $PROTECT_DEPLOYS deploy tag(s) (DRY_RUN=$DRY_RUN)"

ALL="$(gh api "/users/$OWNER/packages/container/$PKG/versions" --paginate \
  --jq '.[] | {id, digest: .name, created_at, tags: (.metadata.container.tags // [])}' \
  | jq -s 'sort_by(.created_at) | reverse')"

TAGGED="$(jq -c '[.[] | select(.tags | length > 0)]'  <<<"$ALL")"
UNTAGGED="$(jq -c '[.[] | select(.tags | length == 0)]' <<<"$ALL")"

# Deploy markers -> commit shas (CI tags every image with its full commit sha). A lightweight
# tag points straight at the commit; an annotated one at a tag object that points at it. Names
# are deploy/YYYYMMDDTHHMMZ, so reverse name order is newest first.
DEPLOY_SHAS=""
if (( PROTECT_DEPLOYS > 0 )); then
  DEPLOY_SHAS="$(gh api "/repos/$OWNER/$REPO/git/matching-refs/tags/deploy/" --paginate \
      --jq '.[] | "\(.ref) \(.object.type) \(.object.sha)"' \
    | LC_ALL=C sort -r | sed -n "1,${PROTECT_DEPLOYS}p" \
    | while read -r _ref type sha; do
        if [[ "$type" == "tag" ]]; then
          gh api "/repos/$OWNER/$REPO/git/tags/$sha" --jq .object.sha
        else
          echo "$sha"
        fi
      done)"
fi
NEWEST_DEPLOY_SHA="$(head -n1 <<<"$DEPLOY_SHAS")"   # the running image, by the deploy procedure's record
# shellcheck disable=SC2086  # word-splitting the two whitespace-separated lists is the point
PROTECT_JSON="$(printf '%s\n' $DEPLOY_SHAS $PROTECT_TAGS | jq -R 'select(length > 0)' | jq -sc 'unique')"
CUTOFF="$(date -u -d "$MIN_AGE_DAYS days ago" +%Y-%m-%dT%H:%M:%SZ)"

SELECTION="$(jq -c --argjson keep "$KEEP" --argjson protect "$PROTECT_JSON" --arg cutoff "$CUTOFF" \
  -f "$HERE/prune-select.jq" <<<"$ALL")"
if [[ "$(jq -r .has_latest <<<"$SELECTION")" != "true" ]]; then
  echo "!! no version carries :latest — refusing to prune anything; investigate first" >&2
  exit 1
fi
jq -r '.unmatched_protect[] | "!! protected tag \(.) matches no image version (a deploy of a commit CI never built?)"' \
  <<<"$SELECTION" >&2

# Never prune blind. The newest deploy/* tag IS the running image; if it matches no image
# version we cannot tell what production runs or what to roll back to, so a real run deletes
# nothing. (A dry run reports it and carries on, so the numbers can still be inspected.)
if [[ -n "$NEWEST_DEPLOY_SHA" ]] \
   && jq -e --arg s "$NEWEST_DEPLOY_SHA" 'any(.unmatched_protect[]; . == $s)' <<<"$SELECTION" >/dev/null; then
  if [[ "$DRY_RUN" == "0" ]]; then
    echo "!! the newest deploy tag ($NEWEST_DEPLOY_SHA) matches no image version — refusing a real prune; fix or remove that tag first" >&2
    exit 1
  fi
  echo "!! DRY RUN continues, but a real run would abort here: the newest deploy tag ($NEWEST_DEPLOY_SHA) matches no image version" >&2
fi
KEEP_TAGGED="$(jq -c .keep <<<"$SELECTION")"
DEL_TAGGED="$(jq -c .delete <<<"$SELECTION")"
echo "-- protected tags: $PROTECT_JSON; age cutoff $CUTOFF"

# Resolve the children still referenced by survivors.
TOKEN="$(curl -fsS "https://ghcr.io/token?scope=repository:$OWNER/$PKG:pull&service=ghcr.io" | jq -r .token)"
REFERENCED="$(mktemp)"; trap 'rm -f "$REFERENCED"' EXIT
for d in $(jq -r '.[].digest' <<<"$KEEP_TAGGED"); do
  if ! curl -fsS -H "Authorization: Bearer $TOKEN" -H "Accept: $ACCEPT" \
       "https://ghcr.io/v2/$OWNER/$PKG/manifests/$d" \
       | jq -r '(.manifests // [])[].digest' >>"$REFERENCED"; then
    echo "!! cannot resolve manifest $d — aborting rather than risk orphaning a live image" >&2
    exit 1
  fi
  echo "$d" >>"$REFERENCED"
done
sort -u "$REFERENCED" -o "$REFERENCED"

DEL_UNTAGGED="$(jq -c --slurpfile ref <(jq -R . "$REFERENCED" | jq -s .) \
  '[ .[] | select( .digest as $d | ($ref[0] | index($d)) | not ) ]' <<<"$UNTAGGED")"

# Every survivor -- each kept index and every child it references -- must resolve, or pulling
# that tag would fail with "manifest unknown".
verify_survivors() {
  local tok d bad=0
  tok="$(curl -fsS "https://ghcr.io/token?scope=repository:$OWNER/$PKG:pull&service=ghcr.io" | jq -r .token)"
  while read -r d; do
    if ! curl -fsSI -o /dev/null -H "Authorization: Bearer $tok" -H "Accept: $ACCEPT" \
         "https://ghcr.io/v2/$OWNER/$PKG/manifests/$d"; then
      echo "!! survivor $d does not resolve" >&2; bad=1
    fi
  done <"$REFERENCED"
  return "$bad"
}

printf -- "-- tagged   %3d total, %3d keep, %3d delete\n" \
  "$(jq length <<<"$TAGGED")" "$(jq length <<<"$KEEP_TAGGED")" "$(jq length <<<"$DEL_TAGGED")"
printf -- "-- untagged %3d total, %3d referenced by survivors, %3d delete\n" \
  "$(jq length <<<"$UNTAGGED")" \
  "$(( $(jq length <<<"$UNTAGGED") - $(jq length <<<"$DEL_UNTAGGED") ))" \
  "$(jq length <<<"$DEL_UNTAGGED")"

if [[ "$DRY_RUN" != "0" ]]; then
  verify_survivors || { echo "!! survivors are already broken BEFORE any delete — investigate" >&2; exit 1; }
  echo "== DRY RUN — nothing deleted; all $(wc -l <"$REFERENCED") surviving manifests resolve. Set DRY_RUN=0 to apply."
  exit 0
fi

ok=0; failed=0
for id in $(jq -r '.[].id' <<<"$DEL_TAGGED") $(jq -r '.[].id' <<<"$DEL_UNTAGGED"); do
  # NB: do NOT pass --silent here; it masks the exit status and the loop then
  # reports success for every failed delete (hit during the 2026-08-03 cleanup).
  if err="$(gh api --method DELETE "/user/packages/container/$PKG/versions/$id" 2>&1 >/dev/null)"; then
    echo "   deleted $id"; ok=$((ok+1))
  else
    echo "   FAILED  $id — ${err//$'\n'/ }" >&2; failed=$((failed+1))
  fi
done

echo "== deleted $ok, failed $failed"
if (( failed > 0 )); then
  # Must be fatal. A token lacking delete:packages rejects every call, and if the
  # job still went green the prune would look healthy forever while deleting
  # nothing. For a USER-scoped package GITHUB_TOKEN may not suffice — set a
  # GHCR_PRUNE_TOKEN secret (PAT with delete:packages) if this trips.
  echo "!! $failed delete(s) rejected — check token scopes (delete:packages)" >&2
  exit 1
fi

verify_survivors || { echo "!! a survivor no longer resolves after the prune" >&2; exit 1; }
echo "== done; all $(wc -l <"$REFERENCED") surviving manifests resolve."
