import { useEffect, useState } from "react";

export type ThemeMode = "system" | "light" | "dark";
export type Resolved = "dark" | "light";

export function resolveTheme(mode: ThemeMode, prefersDark: boolean): Resolved {
  if (mode === "light" || mode === "dark") return mode;
  return prefersDark ? "dark" : "light";
}

/** Applies the resolved theme to <html data-theme> and live-follows OS changes in system mode. */
export function useTheme(mode: ThemeMode): Resolved {
  const [prefersDark, setPrefersDark] = useState(
    () => matchMedia("(prefers-color-scheme: dark)").matches,
  );
  useEffect(() => {
    const mq = matchMedia("(prefers-color-scheme: dark)");
    const on = () => setPrefersDark(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  const resolved = resolveTheme(mode, prefersDark);
  useEffect(() => { document.documentElement.dataset.theme = resolved; }, [resolved]);
  return resolved;
}
