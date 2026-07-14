// Guest-page theme persistence: default LIGHT (user decision — guests get the
// friendlier mode), toggleable, stored per-browser.
export type ShareTheme = "light" | "dark";

export const THEME_KEY = "bmsmon-share-theme";

type ReadableStorage = Pick<Storage, "getItem">;
type WritableStorage = Pick<Storage, "setItem">;

export function loadShareTheme(storage: ReadableStorage): ShareTheme {
  try {
    return storage.getItem(THEME_KEY) === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}

export function saveShareTheme(storage: WritableStorage, theme: ShareTheme): void {
  try {
    storage.setItem(THEME_KEY, theme);
  } catch {
    // private mode / storage denied — theme just won't persist
  }
}
