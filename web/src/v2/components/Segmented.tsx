export function Segmented<T extends string>({ options, value, onChange }: {
  options: { value: T; label: string }[]; value: T; onChange: (v: T) => void;
}) {
  return (
    <div style={{ display: "inline-flex", border: "1px solid var(--border)", borderRadius: 7, overflow: "hidden" }}>
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button key={o.value} onClick={() => onChange(o.value)} className="mono"
            style={{ padding: "6px 12px", fontSize: 11, letterSpacing: ".06em", cursor: "pointer",
              border: "none", background: active ? "var(--nav-active)" : "transparent",
              color: active ? "var(--text)" : "var(--text-3)" }}>
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
