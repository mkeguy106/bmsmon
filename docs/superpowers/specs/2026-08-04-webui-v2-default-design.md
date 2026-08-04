# WebUI v2 becomes the default; v1 moves to /v1

**Date:** 2026-08-04
**Status:** approved

## Goal

Serve the v2 dashboard at `bmsmon.covert.life/` and move v1 to `/v1/`. Keep v1 fully
working — this is a demotion, not a removal.

## Background

v2 has shipped all six views (Command, Fleet Health, Alerts, History, Journey, Settings)
and is deployed to prod. It is the UI actually in use, but it lives one path segment down
while v1 owns the root. This flips that.

How the two are served today:

- `web/vite.config.ts` declares two rollup inputs — `index.html` (v1) and `v2/index.html` —
  emitting `dist/index.html` and `dist/v2/index.html`. Both build with base `/` and share a
  single `dist/assets` chunk pool; `dist/v2/` holds nothing but its HTML shell.
- `server/app/main.py` mounts `dist` at `/` with `html=True`, so `/` serves
  `dist/index.html` and `/v2/` serves `dist/v2/index.html`. There is no routing logic to
  change — only which shell lands where.
- Neither bundle has client-side routing. Nothing reads `location.pathname`, so no
  in-app navigation depends on the base path.

## Approach

**The flip happens at build time**, by swapping which HTML shell is emitted to
`dist/index.html`.

Two alternatives were rejected:

- **Traefik path-rewrite.** Leaves the repo claiming v1 is default, splits the truth
  across two repos (`bmsmon` and `qnap-nas-docker`), and does nothing for `vite dev` or
  the Playwright smoke test.
- **Server-side special case** (serve `dist/v2/index.html` when the path is `/`). Keeps
  the build untouched but leaves `dist/index.html` as a decoy and puts UI policy in the
  static-file layer.

Which UI is the default is a property of the build, so the build is where it is expressed.

## Changes

### 1. Swap the shells (`web/`)

```
git mv web/index.html    web/v1/index.html
git mv web/v2/index.html web/index.html
```

Both shells load their entry with a **root-absolute** src (`/src/main.tsx`,
`/src/v2/main.tsx`), and both bundles build with base `/`. v2's shell already lives in a
subdirectory today and resolves this way, so **no path inside either file changes**.

`vite.config.ts`:

```ts
build: { rollupOptions: { input: { v2: "index.html", v1: "v1/index.html" } } }
```

The input key names the emitted entry chunk. Renaming `main` → `v2` keeps each chunk named
for the UI it contains (`assets/v2-*.js`, `assets/v1-*.js`) rather than letting `main-*.js`
silently become the v2 bundle. Nothing hardcodes chunk names — the HTML is generated.

Titles follow the move: root `bmsmon`, v1 `bmsmon · v1`.

**Highest-risk line in the whole change: v2's `<meta name="viewport">`.** It moves with its
file, intact. Losing it renders phones at a virtual 980 px scaled to ~40% *and* defeats the
`<820 px` auto-mobile detection via `innerWidth`. v1's deliberate *absence* of that tag
(it has no mobile layout, so scaled-desktop is its better fallback) likewise moves with its
own file.

### 2. Keep `/v2/` alive (`server/app/main.py`)

Two narrow routes, registered before the `/` mount so they win over it:

```python
@app.get("/v2", include_in_schema=False)
@app.get("/v2/", include_in_schema=False)
async def _v2_moved():
    return RedirectResponse("/", status_code=307)
```

**307, not 308.** Browsers cache permanent redirects aggressively; a temporary one keeps the
flip reversible without users having to clear caches.

Deliberately **not** `/v2/{p:path}`. v2 has no client-side routing, so there are no deep
links to catch, and a catch-all would swallow `/v2/assets/*` — harmless today (nothing is
emitted there) but a trap the moment a future build does.

`_HASHED_ASSET_PREFIXES` flips `"v2/assets/"` → `"v1/assets/"`, keeping the same defensive
role: both bundles actually share the root `assets/` pool, and the subdirectory entry covers
the case where a per-bundle asset dir appears.

### 3. What makes the deploy land cleanly

`/` is already served with `Cache-Control: no-cache` — *always revalidate*. On deploy every
browser re-fetches the shell and gets v2. There is no stale-shell window and no cache-bust
step. That existing header is load-bearing for this change.

### 4. Storage carries over untouched

Every v2 key (`bmsmon-v2-settings`, `bmsmon-v2-journey`, `bmsmon-pins`, `bmsmon-theme`,
`bmsmon-temp-unit`) is scoped to the **origin**, not the path. Pins, theme, the Journey
TRAIL toggle and unit prefs survive the move with no migration.

### 5. Tests

- `server/tests/test_static_cache.py`: the fake dist tree gets `v1/index.html` instead of
  `v2/`; the shell assertions walk `/v1/` and `/v1/index.html`; a new case asserts `/v2` and
  `/v2/` return 307 to `/` (with `follow_redirects=False`).
- `web/scripts/smoke.mjs`: v2 shots move from `${BASE}/v2/` to `${BASE}/`; the v1 shot moves
  to `${BASE}/v1/`. Note the smoke test runs against `vite dev`, which has no redirect route —
  it must hit the real paths, not rely on `/v2/`.

### 6. Not changed (verified)

- **Traefik.** No label touches. The `/api/` and `/share/` zones are untouched; everything
  else already routes to the app through Authentik, `/v1/` included.
- **The `/share/` build.** Separate Vite config, own `base: "/share/"`, own asset dir. Its
  imports from `../../src/v2/…` are source paths, unaffected by HTML moves.
- **The Android app.** It only ever talks to `/api/v1/*`.
- **`web/preview.html`.** Dev-only, not a rollup input, stays at the web root.

### 7. Docs

- `CLAUDE.md` — the WebUI v2 section currently reads "runs alongside v1, served at `/v2/`".
- `~/GoogleDrive/obsidian/notes/Bmsmon.md` — one line.

## Verification

1. `cd web && npm run build` → `dist/index.html` is the v2 shell (carries the viewport meta),
   `dist/v1/index.html` is the v1 shell (carries none), `dist/v2/` is gone.
2. `cd server && .venv/bin/python -m pytest` → full suite green, including the new redirect
   case.
3. `cd web && npx vitest run` → green.
4. Smoke test against the local stack → v2 at `/`, v1 at `/v1/`, no console errors.

## Deploy

Normal path: merge to `main` → GitHub Actions builds
`ghcr.io/mkeguy106/bmsmon-server:latest` → pull + recreate `bmsmon-api` on `ddnas02`.

## Known residual

A browser tab left open across the deploy that then lazy-loads a chunk (e.g. opening Journey,
which is a `React.lazy` import) will 404, because a rebuild replaces the content-hashed
`assets/` pool. This is pre-existing behaviour on every deploy, not introduced here; a reload
fixes it.
