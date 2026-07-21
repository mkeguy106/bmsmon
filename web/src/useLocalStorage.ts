import { useCallback, useState } from "react";

// WEB-8: shared localStorage-backed state — theme, temp unit and pins were
// three hand-rolled copies of this pattern in App.tsx.

/** decode validates a raw stored string (null = absent/garbage); encode serializes. */
export interface Codec<T> {
  decode: (raw: string) => T | null;
  encode: (v: T) => string;
}

/** Resolve a Web Storage backend by name, or null if unavailable. */
function backend(kind: StorageKind): Storage | null {
  try { return kind === "session" ? sessionStorage : localStorage; } catch { return null; }
}

/** Which Web Storage to back a value with. "local" persists across sessions;
 *  "session" survives refresh/restore but resets on a fresh page session (new tab). */
export type StorageKind = "local" | "session";

/** Validated read of a stored value. Any storage/parse failure reads as absent. */
export function readStored<T>(key: string, decode: (raw: string) => T | null, kind: StorageKind = "local"): T | null {
  try {
    const raw = backend(kind)?.getItem(key) ?? null;
    return raw == null ? null : decode(raw);
  } catch (e) { return null; }
}

/**
 * Web-Storage-backed useState. Returns [value, setPersist, setLocal]:
 * - setPersist stores the value (a deliberate user choice); supports
 *   functional updates. Storage failures fall back to in-memory state.
 * - setLocal updates state WITHOUT persisting — for synced defaults that must
 *   not masquerade as a stored user choice (the temp-unit synced default).
 * `kind` picks the backend: "local" (default, cross-session) or "session"
 * (per-tab; kept on refresh, reset on a fresh page session).
 * Pass module-level codecs: the setter identity depends on them.
 */
export function useLocalStorage<T>(key: string, fallback: () => T, codec: Codec<T>, kind: StorageKind = "local") {
  const [value, setValue] = useState<T>(() => readStored(key, codec.decode, kind) ?? fallback());
  const setPersist = useCallback((v: T | ((prev: T) => T)) => {
    setValue((prev) => {
      const next = typeof v === "function" ? (v as (prev: T) => T)(prev) : v;
      try { backend(kind)?.setItem(key, codec.encode(next)); } catch (e) { /* not persisted */ }
      return next;
    });
  }, [key, codec, kind]);
  return [value, setPersist, setValue] as const;
}
