export function Ring({ soc, connected, size = 120 }:
  { soc: number | null; connected: boolean; size?: number }) {
  const r = size / 2 - 8, c = 2 * Math.PI * r;
  const pct = connected && soc != null ? Math.max(0, Math.min(100, soc)) : 0;
  const color = !connected ? "var(--text3)"
    : pct < 15 ? "var(--critical)" : pct < 30 ? "#e2b01e" : "var(--accent)";
  return (
    <svg width={size} height={size} style={{ opacity: connected ? 1 : 0.4 }}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--input-bg)" strokeWidth={8} />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={8}
        strokeLinecap="round" strokeDasharray={c} strokeDashoffset={c * (1 - pct / 100)}
        transform={`rotate(-90 ${size / 2} ${size / 2})`} />
      <text x="50%" y="50%" textAnchor="middle" dy="0.35em" className="mono"
        fill="var(--text)" fontSize={size * 0.22} fontWeight={700}>
        {connected && soc != null ? `${Math.round(soc)}%` : "—"}
      </text>
    </svg>
  );
}
