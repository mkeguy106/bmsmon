// v2's app-wide state hooks each have ONE instance, owned by the App and passed down as
// props. A second call silently forks the state: SettingsView calling useV2Settings itself
// made Settings edits invisible to the rest of the app until a reload, and the next TopBar
// theme toggle wrote the App's stale copy back over them (WEB-16). A second useFleetData
// would open a second store and a second WebSocket. This pins each hook to one call site.
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const V2 = fileURLToPath(new URL(".", import.meta.url));

/** Every non-test .ts/.tsx file under src/v2. */
function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = join(dir, e.name);
    if (e.isDirectory()) return sources(p);
    return /\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) ? [p] : [];
  });
}

/** Source with comments removed, so prose that names a hook is not a call site. A `//`
 *  right after ':' or a quote (a URL in a string) is kept. */
const code = (src: string): string =>
  src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:"'`])\/\/.*$/gm, "$1");

/** Files (relative to src/v2) that CALL [hook]; its own `function hook(` is not a call. */
function callSites(hook: string): string[] {
  const call = new RegExp(`(?<!function\\s)\\b${hook}\\s*\\(`);
  return sources(V2)
    .filter((f) => call.test(code(readFileSync(f, "utf8"))))
    .map((f) => relative(V2, f))
    .sort();
}

describe("v2 app-wide state has exactly one owner", () => {
  for (const hook of ["useV2Settings", "useFleetData", "useV2Configs", "useStageBase"]) {
    it(`${hook} is called only by App.tsx`, () => {
      expect(callSites(hook)).toEqual(["App.tsx"]);
    });
  }
});
