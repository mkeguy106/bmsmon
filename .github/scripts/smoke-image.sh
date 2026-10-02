#!/usr/bin/env bash
# Boot-smoke a bmsmon-server image: does it start against an EMPTY Postgres (schema.sql applies
# from scratch), answer /api/v1/health, serve both UI shells, and carry the admin CLI? The test
# suites run against the source tree; this is the only check of the artifact production pulls --
# 7cec11c had to fix an image that shipped without tools/ while every test passed.
#
# Usage: smoke-image.sh <image-ref>
# Needs: docker; a Postgres at $SMOKE_DATABASE_URL (default: the CI service / local dev DB on
# 127.0.0.1:5432, user/password/db all "bmsmon"); host port 8000 free (--network host).
set -euo pipefail

IMAGE="${1:?usage: smoke-image.sh <image-ref>}"
DB_URL="${SMOKE_DATABASE_URL:-postgresql://bmsmon:bmsmon@127.0.0.1:5432/bmsmon}"
NAME="bmsmon-smoke-$$"
BASE="http://127.0.0.1:8000"

cleanup() {
  local status=$?
  if (( status != 0 )); then
    echo "!! smoke FAILED for $IMAGE -- container log follows" >&2
    docker logs "$NAME" >&2 2>&1 || true
  fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name "$NAME" --network host -e DATABASE_URL="$DB_URL" "$IMAGE" >/dev/null

# Every HTTP check captures the response body FIRST and greps it second. `curl | grep -q` would
# let a failed request pass or fail on grep's say-so alone (and grep -q closing the pipe early
# makes curl exit 23 under pipefail), so a refused connection could not be told from a wrong page.
healthy=""
for _ in $(seq 1 45); do
  body="$(curl -fsS "$BASE/api/v1/health" 2>/dev/null || true)"
  if grep -q '"status":"ok"' <<<"$body"; then healthy=1; break; fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$NAME")" != "true" ]]; then
    echo "!! container exited during startup" >&2; exit 1
  fi
  sleep 2
done
[[ -n "$healthy" ]] || { echo "!! /api/v1/health never returned ok within 90 s" >&2; exit 1; }
echo "ok   /api/v1/health"

page="$(curl -fsS "$BASE/")" || { echo "!! GET / failed" >&2; exit 1; }
grep -q '<title>bmsmon</title>' <<<"$page" || { echo "!! / did not serve the v2 shell" >&2; exit 1; }
echo "ok   v2 shell at /"
page="$(curl -fsS "$BASE/v1/")" || { echo "!! GET /v1/ failed" >&2; exit 1; }
grep -q 'v1</title>' <<<"$page" || { echo "!! /v1/ did not serve the v1 shell" >&2; exit 1; }
echo "ok   v1 shell at /v1/"
docker exec "$NAME" python -m tools.api_key_admin --help >/dev/null \
  || { echo "!! tools.api_key_admin is missing from the image" >&2; exit 1; }
echo "ok   tools.api_key_admin present"
echo "== smoke passed: $IMAGE"
