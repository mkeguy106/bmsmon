export function Placeholder({ title }: { title: string }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center",
      gap: 8, padding: "120px 0", color: "var(--text-4)" }}>
      <span className="eyebrow">{title}</span>
      <span style={{ fontSize: 13 }}>Coming in a later phase.</span>
    </div>
  );
}
