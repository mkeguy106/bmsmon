// Per-pack inner-ring full scale (W) — matches the app's POWER_RING_FULL_W.
const POWER_FULL_W = 300;

export function Ring({ soc, power, current, connected, size = 132 }: {
  soc?: number | null; power?: number | null; current?: number | null; connected: boolean; size?: number;
}) {
  const c = size / 2;
  const swOuter = 9;
  const swInner = 7;
  const rOuter = size / 2 - swOuter / 2 - 1;
  const rInner = rOuter - swOuter / 2 - swInner / 2 - 3;
  const cOuter = 2 * Math.PI * rOuter;
  const cInner = 2 * Math.PI * rInner;

  // Keep drawing the last-known SOC when disconnected (muted), so we still see the last good state.
  const pct = soc != null ? Math.max(0, Math.min(100, soc)) : 0;
  const socColor = !connected ? "var(--text-4)"
    : pct < 15 ? "var(--live)" : pct < 30 ? "var(--warn)" : "var(--ok)";

  // Inner ring shows charge/discharge: green when charging, warn/power tone when discharging,
  // filled proportional to |power| up to POWER_FULL_W.
  const cur = current ?? 0;
  const charging = cur > 0.1;
  const pwFrac = connected ? Math.min(1, Math.abs(power ?? 0) / POWER_FULL_W) : 0;
  const innerColor = charging ? "var(--ok)" : "var(--warn)";

  return (
    <svg width={size} height={size} style={{ opacity: connected ? 1 : 0.5 }}>
      {/* outer: state of charge */}
      <circle cx={c} cy={c} r={rOuter} fill="none" stroke="var(--track)" strokeWidth={swOuter} />
      <circle cx={c} cy={c} r={rOuter} fill="none" stroke={socColor} strokeWidth={swOuter}
        strokeLinecap="round" strokeDasharray={cOuter} strokeDashoffset={cOuter * (1 - pct / 100)}
        transform={`rotate(-90 ${c} ${c})`} />
      {/* inner: charge / discharge rate */}
      <circle cx={c} cy={c} r={rInner} fill="none" stroke="var(--track)" strokeWidth={swInner} />
      {connected && pwFrac > 0 && (
        <circle cx={c} cy={c} r={rInner} fill="none" stroke={innerColor} strokeWidth={swInner}
          strokeLinecap="round" strokeDasharray={cInner} strokeDashoffset={cInner * (1 - pwFrac)}
          transform={`rotate(-90 ${c} ${c})`} />
      )}
      <text x="50%" y="50%" textAnchor="middle" dy="0.35em" className="mono"
        fill={connected ? "var(--text)" : "var(--text-4)"} fontSize={size * 0.2} fontWeight={700}>
        {soc != null ? `${Math.round(soc)}%` : "—"}
      </text>
    </svg>
  );
}
