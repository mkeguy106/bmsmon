/** A pin toggle. Filled/accent when pinned to the main stage, muted outline otherwise. */
export function PinButton({ pinned, onToggle, title }:
  { pinned: boolean; onToggle: () => void; title?: string }) {
  return (
    <button
      onClick={(e) => { e.stopPropagation(); onToggle(); }}
      title={title ?? (pinned ? "Unpin from main stage" : "Pin to main stage")}
      aria-label={title ?? (pinned ? "Unpin from main stage" : "Pin to main stage")}
      aria-pressed={pinned}
      style={{ background: "none", border: "none", cursor: "pointer", padding: 2, lineHeight: 0,
        color: pinned ? "var(--accent)" : "var(--text3)" }}
    >
      <svg width={16} height={16} viewBox="0 0 24 24"
        fill={pinned ? "currentColor" : "none"} stroke="currentColor" strokeWidth={1.8}
        strokeLinecap="round" strokeLinejoin="round">
        {/* pushpin */}
        <path d="M12 17v5" />
        <path d="M9 3h6l-1 7 3 3H7l3-3-1-7z" />
      </svg>
    </button>
  );
}
