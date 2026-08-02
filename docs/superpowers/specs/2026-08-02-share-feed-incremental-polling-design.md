# Share Page: Incremental Feed Polling at 4 s — Design

**Date:** 2026-08-02
**Status:** Approved by user (design presented in conversation; "guest side only (~4 s poll)").

## Goal

Make the guest share page feel live. Today it polls `/share/{token}/feed` every 10 s and
each poll re-sends the **entire day's trail**. Drop the poll to 4 s *and* cut the bytes,
by sending only the buckets the guest doesn't already have.

## What was measured first (2026-08-02, prod)

The poll interval is only half the latency, and the payload is the real cost:

| | measured |
|---|---|
| Phone upload cadence (`FLUSH_AGE_MS` 15 s / `MIN_BATCH` 20) | **p50 12.2 s**, p90 13.7 s, max 25.3 s |
| Trail query (whole day, TTL-cached) | 47 ms, 3 868 points |
| Feed payload per poll | 419 KB raw, **56 KB gzipped** |
| Per guest-hour at the current 10 s poll | **19.7 MB** |
| Per guest-hour at 4 s if left as-is | 49.1 MB |
| The same update carried incrementally | **135 B** (2 new points) |

Two conclusions drive the design:

1. **The server's data is never fresher than ~12 s** because the phone batches uploads.
   Polling faster than that mostly re-reads unchanged data — worth doing for the dock, but
   not worth a proportional increase in bytes or DB work.
2. **A 15 s GPS bucket cannot be fresher than the existing 10 s trail cache.** So the
   trail does not need to be re-queried more often; only re-*sent* content needs to shrink.

Decided out of scope: lowering the phone's `FLUSH_AGE_MS` (would roughly triple upload
requests — battery and mobile data on the chair phone — and partly walk back the
deliberate batching win of ~470 B JWT/header overhead per POST plus worse gzip on small
bodies). Revisit only if ~8 s average lag proves insufficient.

## Server — `GET /share/{token}/feed?since=<bucket_ms>`

- `since` is optional. Absent → today's whole trail, exactly as now.
- Present → return only points with `t >= since`. The slice is taken from the **already
  cached list in memory** — no new query, no new cache key, so DB cost stays flat as the
  poll rate rises. `since` is clamped into the day window and ignored if malformed.
- `since` is the client's **last bucket start, not one past it**: the newest 15 s bucket is
  partially filled and its averages keep changing until it closes, so that bucket must be
  re-sent and replaced. This is the same seam rule `useTrack`/`appendTrack` already use.
- `last` is computed from the **full** cached list *before* slicing. Otherwise a poll with
  no new points would return `last: null` and the live chair marker would vanish.
- New response field `day_start` (the day window's start, = the cache key). The client
  replaces instead of appending when it changes, so a guest whose page is open across
  midnight can't append tomorrow's buckets onto yesterday's array.
- `status` stays fully per-request and uncached — this is the field that actually gains
  freshness from the faster poll.
- Per-IP rate limit 60/min → **150/min**. A 4 s poll is 15 req/min per guest, so this
  preserves today's ~10-guests-behind-one-IP headroom (CGNAT). Token scanning is
  infeasible at either number (192-bit tokens); the limiter exists to bound load, and an
  incremental poll is now cheap.

Error/terminal semantics are untouched: unknown/revoked → bare 404, expired → 410, both
with the same `no-store`/`no-referrer` headers, `since` or not.

## Guest page — `web/share/src/`

- `FEED_POLL_MS` 10 s → **4 s**. `FULL_REFRESH_MS` = 5 min safety net.
- `fetchFeed(token, since?)` passes the param through; `Feed` gains `day_start`.
- `App.tsx` accumulates **`TrackPoint[]`** in state (it already converts `FeedPoint` →
  `TrackPoint` for `cleanTrack`), converting at the fetch boundary and splicing with the
  existing, tested **`appendTrack`** from `web/src/v2/model/` — no new merge function. It
  already returns the previous array identity when nothing changed, so the
  `cleanTrack`/`trailProps` memos and the Leaflet trail effect no-op on the ~4 of 5 polls
  that bring no news. That identity behaviour is what makes a 4 s poll free on the guest's
  phone.
- A full (no-`since`) fetch happens on mount, when the accumulated track is empty, when
  `day_start` changes, and every `FULL_REFRESH_MS` — mirroring `useTrack`'s safety net.
- The live marker keeps reading `feed.last` (server-computed), not the array tail.

## Testing

Server (`tests/test_share_public.py`):
- `since` returns only newer buckets, and returns them **from the cache** (no new query):
  seed a fix, poll full, seed another, poll with `since` inside the TTL → the new fix is
  absent, proving the slice came from the cached list.
- `last` on a `since` poll is the full trail's newest point, not the slice's.
- `day_start` is present and equals the day window start.
- Out-of-range/garbage `since` degrades to a full response rather than erroring.
- Expired/revoked still 410/404 when `since` is supplied.

Web (`share/src/feed.test.ts`): `fetchFeed` builds the `?since=` URL and omits it when
undefined. Splicing itself is already covered by `appendTrack.test.ts`.

## Expected outcome

| | before | after |
|---|---|---|
| Guest lag (avg / worst) | ~11 s / ~35 s | **~8 s / ~17 s** |
| Data per guest-hour | 19.7 MB | **~180 KB** |
| Trail DB queries | 1 per 10 s (all guests) | unchanged |
