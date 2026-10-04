import { useEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";
import { getNotes, putNote } from "../../api";
import {
  NOTE_MAX_CHARS, NOTE_STATUS, failureKind, noteSaveBlocked, type NoteSaveState,
} from "../model/formErrors";

/**
 * Editable per-base notes, backed by GET/POST /web/notes. On mount (and when
 * `baseId` changes) the note for `baseId` is loaded; edits debounce ~800 ms
 * before a `putNote`. The local text is authoritative while editing — a late
 * GET never clobbers in-progress typing (guarded by `dirty`). The textarea holds the server's
 * NOTE_MAX_CHARS cap and counts toward it; a note the server refuses (422) says so instead of
 * "retry", since the same text would fail again.
 */
export function NotesCard({ baseId }: { baseId: string }) {
  const [text, setText] = useState("");
  const [status, setStatus] = useState<NoteSaveState>("idle");
  const dirty = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Tracks the not-yet-fired debounced save so it can be flushed (instead of
  // dropped) if the base changes or the component unmounts mid-debounce.
  const pendingSave = useRef<{ base: string; body: string } | null>(null);
  // Guards setStatus calls that resolve after unmount (harmless dev warning
  // otherwise). The fire-and-forget flush save intentionally does NOT use this.
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);

  // Load the note for this base. Reset dirty on base change so a fresh load can
  // populate; the alive guard drops a superseded/late response.
  useEffect(() => {
    let alive = true;
    dirty.current = false;
    setStatus("idle");
    getNotes()
      .then((r) => {
        if (!alive || dirty.current) return; // don't clobber in-progress typing
        setText(r.notes.find((n) => n.base_id === baseId)?.body ?? "");
      })
      .catch(() => { /* keep whatever's shown; a save can still create it */ });
    return () => { alive = false; };
  }, [baseId]);

  // When the base changes / component unmounts, flush any pending debounced
  // save (targeting the ORIGINAL base) instead of silently dropping it.
  useEffect(() => () => {
    if (timer.current && pendingSave.current) {
      putNote(pendingSave.current.base, pendingSave.current.body).catch(() => {}); // fire-and-forget
      pendingSave.current = null;
    }
    if (timer.current) clearTimeout(timer.current);
  }, [baseId]);

  const onChange = (v: string) => {
    setText(v);
    dirty.current = true;
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
    pendingSave.current = null;
    // Over the cap (maxLength stops typing past it; this covers anything that slips by):
    // the server would refuse it, so nothing is sent until it is trimmed.
    if (noteSaveBlocked(v)) { setStatus("refused"); return; }
    setStatus("saving");
    const target = baseId;
    pendingSave.current = { base: target, body: v };
    timer.current = setTimeout(() => {
      putNote(target, v)
        .then(() => { if (mounted.current && target === baseId) setStatus("saved"); })
        .catch((e) => {
          if (mounted.current && target === baseId) {
            setStatus(failureKind(e) === "refused" ? "refused" : "failed");
          }
        });
      pendingSave.current = null;
    }, 800);
  };

  const statusText = NOTE_STATUS[status];
  const statusColor = status === "failed" || status === "refused" ? "var(--live)" : "var(--text-4)";

  return (
    <div className="card">
      <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", marginBottom: 10 }}>
        <div className="eyebrow">Notes · Base {baseId}</div>
        <span className="mono" style={{ fontSize: 10, color: statusColor }}>{statusText}</span>
      </div>
      <textarea
        value={text}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Maintenance log, install notes, quirks…"
        rows={4}
        maxLength={NOTE_MAX_CHARS}
        style={textareaStyle}
      />
      <div className="mono" style={{ fontSize: 10, textAlign: "right", marginTop: 4,
        color: text.length >= NOTE_MAX_CHARS ? "var(--warn)" : "var(--text-4)" }}>
        {text.length}/{NOTE_MAX_CHARS}
      </div>
    </div>
  );
}

const textareaStyle: CSSProperties = {
  width: "100%", resize: "vertical", minHeight: 72,
  background: "var(--panel-2)", color: "var(--text)", border: "1px solid var(--border)",
  borderRadius: 6, padding: "8px 10px", fontSize: 12, lineHeight: 1.5,
  fontFamily: "Inter,system-ui,sans-serif",
};
