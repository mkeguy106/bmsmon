import { useEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";
import { getNotes, putNote } from "../../api";

type SaveState = "idle" | "saving" | "saved" | "failed";

/**
 * Editable per-base notes, backed by GET/POST /web/notes. On mount (and when
 * `baseId` changes) the note for `baseId` is loaded; edits debounce ~800 ms
 * before a `putNote`. The local text is authoritative while editing — a late
 * GET never clobbers in-progress typing (guarded by `dirty`).
 */
export function NotesCard({ baseId }: { baseId: string }) {
  const [text, setText] = useState("");
  const [status, setStatus] = useState<SaveState>("idle");
  const dirty = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

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

  // Clear any pending debounce when the base changes / component unmounts.
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, [baseId]);

  const onChange = (v: string) => {
    setText(v);
    dirty.current = true;
    setStatus("saving");
    if (timer.current) clearTimeout(timer.current);
    const target = baseId;
    timer.current = setTimeout(() => {
      putNote(target, v)
        .then(() => { if (target === baseId) setStatus("saved"); })
        .catch(() => { if (target === baseId) setStatus("failed"); });
    }, 800);
  };

  const statusText =
    status === "saving" ? "saving…" :
    status === "saved" ? "saved" :
    status === "failed" ? "save failed — retry" : "";
  const statusColor = status === "failed" ? "var(--live)" : "var(--text-4)";

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
        style={textareaStyle}
      />
    </div>
  );
}

const textareaStyle: CSSProperties = {
  width: "100%", resize: "vertical", minHeight: 72,
  background: "var(--panel-2)", color: "var(--text)", border: "1px solid var(--border)",
  borderRadius: 6, padding: "8px 10px", fontSize: 12, lineHeight: 1.5,
  fontFamily: "Inter,system-ui,sans-serif",
};
