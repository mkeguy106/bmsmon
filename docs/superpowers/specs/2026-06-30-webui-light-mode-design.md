# WebUI Light Mode (toggleable, browser-local)

**Date:** 2026-06-30
**Status:** Approved
**Scope:** WebUI (`web/`) only. No server/Android changes.

## Motivation

Add a light theme to the bmsmon dashboard with a manual toggle. The preference is **WebUI-specific**
— stored in the browser (localStorage), not synced from or matched to the Android app.

## Decisions (locked with user)

- **Default dark** (today's look) with a header toggle that flips **dark ↔ light**.
- Preference persisted in `localStorage["bmsmon-theme"]` (`"dark"` | `"light"`); absent → dark.
- No-flash on load via an inline `<head>` script that sets `data-theme` before first paint.
- Keep `darkreader-lock` so Dark Reader never alters the page in either mode; relax the
  `color-scheme` meta to `light dark`.

## Architecture

All colors are CSS custom properties defined once in `web/src/theme.css` and consumed everywhere as
`var(--…)`. Light mode is therefore a single override block keyed off a root attribute; no component
changes are required for the re-theme itself (only the toggle control is added to `App.tsx`).

The active theme is the value of `document.documentElement.dataset.theme` (`"light"` applies the
override; anything else = dark default). Three things keep that attribute, React state, and
localStorage in sync: the inline head script (initial), and the toggle handler (on click).

## Components

### 1. `web/src/theme.css`
- The existing `:root { … }` stays as the **dark default**; add `color-scheme: dark;` to it.
- Add a light override block:
```css
:root[data-theme="light"] {
  color-scheme: light;
  --bg: #f4f5f6; --card: #ffffff; --border: #e3e5e8; --divider: #eceef0;
  --input-bg: #ffffff; --input-border: #cdd2d8; --text: #1b1d1f; --text2: #5b6168;
  --text3: #888f97; --accent: #d2691e; --power: #b4501a; --regen: #1f9d57; --critical: #d32f2f;
}
```
(`--sans`/`--mono` are fonts — unchanged. Values are a starting point; easy to tweak after review.)

### 2. `web/index.html`
- Change `<meta name="color-scheme" content="dark" />` → `content="light dark"`.
- Keep `<meta name="darkreader-lock" />`.
- Add an inline script in `<head>` (before the body renders) to avoid a flash:
```html
<script>
  try {
    if (localStorage.getItem("bmsmon-theme") === "light")
      document.documentElement.dataset.theme = "light";
  } catch (e) {}
</script>
```

### 3. `web/src/App.tsx`
- Theme state: `const [theme, setTheme] = useState<"dark" | "light">(() =>`
  `document.documentElement.dataset.theme === "light" ? "light" : "dark")`.
- Toggle handler: flips theme, sets `document.documentElement.dataset.theme`, writes
  `localStorage["bmsmon-theme"]` (wrapped in try/catch).
- Render a small **icon button in the header** (next to the LIVE/GPS pills): shows a moon glyph in
  dark mode (click → go light), a sun glyph in light mode (click → go dark). `aria-label`
  "Toggle light/dark theme". Styled with theme tokens (`var(--text2)`, hover `var(--text)`), matching
  the existing header pill sizing (~13px, button reset: no background/border, pointer cursor).

## Data flow

initial paint: head script reads localStorage → sets `data-theme` → CSS applies dark/light tokens.
React mounts: `App` reads `data-theme` into state → renders the correct toggle glyph.
click: handler updates state + `data-theme` + localStorage → CSS re-themes instantly; persists.

## Edge cases

- localStorage unavailable/blocked (private mode): try/catch in both the head script and the toggle;
  theme still works for the session, just not persisted.
- No saved value → dark (unchanged behavior for existing users).
- Dark Reader: `darkreader-lock` keeps it disabled in both modes; our light mode renders as authored.
- The toggle button uses CSS-var colors so it themes itself in both modes.

## Testing / verification

- `cd web && npm run build` (tsc + vite) succeeds; existing `store.test.ts` stays green.
- After deploy: load in Firefox, toggle to light (verify the whole dashboard re-themes: header,
  MainStage, AllBatteries, AdminDevices, rings, stat grids), reload (preference persists), toggle
  back to dark, and confirm Dark Reader shows the site as locked/off.

## Files touched

`web/index.html`, `web/src/theme.css`, `web/src/App.tsx`.
