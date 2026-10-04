// What a failed write says (final review I3). The server refuses content it will never store
// with a 422: a note over NOTE_MAX_CHARS, a NUL byte, a share or API-key name outside 1–80
// characters. The same request fails the same way again, so a 422 says what to change, never
// "retry" or "check the connection".

export type FailureKind = "auth" | "refused" | "net";

/** api.ts throws Error(String(status)) for a non-2xx response; anything else is the network. */
export function failureKind(e: unknown): FailureKind {
  const m = e instanceof Error ? e.message : "";
  return m === "401" || m === "403" ? "auth" : m === "422" ? "refused" : "net";
}

/** The server's cap on a note body (server NoteBody). Counted in UTF-16 units like the
 *  textarea's maxLength, which is never fewer than the server's code-point count, so a note
 *  within it always fits. */
export const NOTE_MAX_CHARS = 4000;

/** A note the server would refuse for its length: don't send it. */
export const noteSaveBlocked = (body: string): boolean => body.length > NOTE_MAX_CHARS;

export type NoteSaveState = "idle" | "saving" | "saved" | "failed" | "refused";

export const NOTE_STATUS: Record<NoteSaveState, string> = {
  idle: "",
  saving: "saving…",
  saved: "saved",
  failed: "save failed — retry",
  refused: `can't save: over ${NOTE_MAX_CHARS} characters or has characters that can't be stored — edit it to save`,
};

const NAME_REFUSED = "That name can't be used — give it 1 to 80 characters, with no control characters.";

export const SHARE_CREATE_ERROR: Record<FailureKind, string> = {
  auth: "Not authorized — your session may have expired (admin required).",
  refused: NAME_REFUSED,
  net: "Couldn't create the share link — check the connection and try again.",
};

export const API_KEY_AUTH =
  "Not authorized — your session may have expired (admin required). Reload to sign in again.";

export const API_KEY_CREATE_ERROR: Record<FailureKind, string> = {
  auth: API_KEY_AUTH,
  refused: NAME_REFUSED,
  net: "Couldn't create the key — check the connection and try again.",
};
