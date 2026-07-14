// WebUI smoke test: screenshot v1, every v2 view, and the preview harness,
// failing on any console error or page crash.
//
// Prereqs (see CLAUDE.md "WebUI smoke test"):
//   1. dev Postgres up + seeded:  server/.venv/bin/python server/scripts/seed_dev.py
//   2. local API with dev-trust:  BMSMON_DEV_TRUST_HEADERS=1 server/.venv/bin/uvicorn app.main:app --port 8000
//   3. vite dev server:           npx vite dev --port 5173
// Run:  node scripts/smoke.mjs   (from web/; shots land in web/smoke-shots/)
import { mkdirSync } from "node:fs";
import { chromium } from "playwright";

const OUT = new URL("../smoke-shots", import.meta.url).pathname;
const BASE = process.env.SMOKE_BASE ?? "http://localhost:5173";
mkdirSync(OUT, { recursive: true });
const errors = [];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on("console", (m) => { if (m.type() === "error") errors.push(`[console] ${page.url()} ${m.text()}`); });
page.on("pageerror", (e) => errors.push(`[pageerror] ${page.url()} ${e.message}`));

async function shot(name, ms = 3500) {
  await page.waitForTimeout(ms);
  await page.screenshot({ path: `${OUT}/${name}.png` });
  console.log(`shot: ${name}`);
}

// v1 dashboard
await page.goto(`${BASE}/`, { waitUntil: "networkidle" });
await shot("v1-dashboard");

// v2 — walk every nav view (Command first so the persisted view choice can't skip it)
await page.goto(`${BASE}/v2/`, { waitUntil: "networkidle" });
for (const label of ["Command", "Fleet Health", "Alerts", "History", "Journey", "Settings"]) {
  const nav = page.getByText(label, { exact: true }).first();
  try {
    await nav.click({ timeout: 5000 });
    await shot(`v2-${label.toLowerCase().replace(" ", "-")}`, label === "Journey" ? 6000 : 3500);
  } catch (e) {
    errors.push(`[nav] could not open ${label}: ${e.message.split("\n")[0]}`);
  }
}

// v1 preview harness (mock data)
await page.goto(`${BASE}/preview.html`, { waitUntil: "networkidle" });
await shot("v1-preview", 2500);

// sit on Command 12 s to catch tick-driven runtime errors
await page.goto(`${BASE}/v2/`, { waitUntil: "networkidle" });
await page.getByText("Command", { exact: true }).first().click();
await page.waitForTimeout(12000);
await page.screenshot({ path: `${OUT}/v2-command-after-12s.png` });
console.log("shot: v2-command-after-12s");

await browser.close();
if (errors.length) { console.log("\nERRORS:"); errors.forEach((e) => console.log("  " + e)); process.exit(2); }
console.log("\nNO console/page errors");
