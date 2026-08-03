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
# Usage:
#   DRY_RUN=1 .github/scripts/prune-ghcr.sh     # report only (default)
#   DRY_RUN=0 KEEP=10 .github/scripts/prune-ghcr.sh
#
# Needs a token with delete:packages (local `gh auth`, or GH_TOKEN in CI).
set -euo pipefail

OWNER="${OWNER:-mkeguy106}"
PKG="${PKG:-bmsmon-server}"
KEEP="${KEEP:-10}"        # newest tagged versions to retain (rollback headroom)
DRY_RUN="${DRY_RUN:-1}"   # default to safe

echo "== ghcr.io/$OWNER/$PKG — keep newest $KEEP tagged (DRY_RUN=$DRY_RUN)"

ALL="$(gh api "/users/$OWNER/packages/container/$PKG/versions" --paginate \
  --jq '.[] | {id, digest: .name, created_at, tags: (.metadata.container.tags // [])}' \
  | jq -s 'sort_by(.created_at) | reverse')"

TAGGED="$(jq -c '[.[] | select(.tags | length > 0)]'  <<<"$ALL")"
UNTAGGED="$(jq -c '[.[] | select(.tags | length == 0)]' <<<"$ALL")"

# Keep the newest N, and unconditionally keep whatever carries `latest` —
# that is what production pulls, so it must survive even if ordering shifts.
KEEP_TAGGED="$(jq -c --argjson n "$KEEP" \
  '[ (.[:$n])[], (.[] | select(.tags | index("latest"))) ] | unique_by(.id)' <<<"$TAGGED")"
DEL_TAGGED="$(jq -c --argjson keep "$KEEP_TAGGED" \
  '[ .[] | select( .id as $i | ($keep | map(.id) | index($i)) | not ) ]' <<<"$TAGGED")"

# Resolve the children still referenced by survivors.
TOKEN="$(curl -fsS "https://ghcr.io/token?scope=repository:$OWNER/$PKG:pull&service=ghcr.io" | jq -r .token)"
REFERENCED="$(mktemp)"; trap 'rm -f "$REFERENCED"' EXIT
for d in $(jq -r '.[].digest' <<<"$KEEP_TAGGED"); do
  if ! curl -fsS -H "Authorization: Bearer $TOKEN" \
       -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json" \
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

printf -- "-- tagged   %3d total, %3d keep, %3d delete\n" \
  "$(jq length <<<"$TAGGED")" "$(jq length <<<"$KEEP_TAGGED")" "$(jq length <<<"$DEL_TAGGED")"
printf -- "-- untagged %3d total, %3d referenced by survivors, %3d delete\n" \
  "$(jq length <<<"$UNTAGGED")" \
  "$(( $(jq length <<<"$UNTAGGED") - $(jq length <<<"$DEL_UNTAGGED") ))" \
  "$(jq length <<<"$DEL_UNTAGGED")"

if [[ "$DRY_RUN" != "0" ]]; then
  echo "== DRY RUN — nothing deleted. Set DRY_RUN=0 to apply."
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

echo "== done. Verify production can still pull:"
echo "   docker manifest inspect ghcr.io/$OWNER/$PKG:latest >/dev/null && echo OK"
