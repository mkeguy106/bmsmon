// Per-pack inner-ring full scale (W) — matches the app's POWER_RING_FULL_W.
const POWER_FULL_W = 300;

export function Ring({ soc, current, power, connected, size = 120 }: {
  soc: number | null;
  current?: number | null;
  power?: number | null;
  connected: boolean;
  size?: number;
}) {
  const c = size / 2;
  const rOuter = size / 2 - 8;
  const rInner = size / 2 - Math.max(18, size * 0.16);
  const cOuter = 2 * Math.PI * rOuter;
  const cInner = 2 * Math.PI * rInner;
  const sw = Math.max(5, size * 0.05);
  const swi = Math.max(4, size * 0.042);

  const pct = connected && soc != null ? Math.max(0, Math.min(100, soc)) : 0;
  const socColor = !connected ? "var(--text3)"
    : pct < 15 ? "var(--critical)" : pct < 30 ? "#e2b01e" : "var(--accent)";

  // Inner ring shows charge/discharge: green when charging, power-orange when discharging,
  // filled proportional to |power| up to POWER_FULL_W.
  const cur = current ?? 0;
  const charging = cur > 0.1;
  const discharging = cur < -0.1;
  const pwFrac = connected ? Math.min(1, Math.abs(power ?? 0) / POWER_FULL_W) : 0;
  const innerColor = charging ? "var(--regen)" : discharging ? "var(--power)" : "var(--input-border)";

  return (
    <svg width={size} height={size} style={{ opacity: connected ? 1 : 0.4 }}>
      {/* outer: state of charge */}
      <circle cx={c} cy={c} r={rOuter} fill="none" stroke="var(--input-bg)" strokeWidth={sw} />
      <circle cx={c} cy={c} r={rOuter} fill="none" stroke={socColor} strokeWidth={sw}
        strokeLinecap="round" strokeDasharray={cOuter} strokeDashoffset={cOuter * (1 - pct / 100)}
        transform={`rotate(-90 ${c} ${c})`} />
      {/* inner: charge / discharge rate */}
      <circle cx={c} cy={c} r={rInner} fill="none" stroke="var(--input-bg)" strokeWidth={swi} />
      {connected && pwFrac > 0 && (
        <circle cx={c} cy={c} r={rInner} fill="none" stroke={innerColor} strokeWidth={swi}
          strokeLinecap="round" strokeDasharray={cInner} strokeDashoffset={cInner * (1 - pwFrac)}
          transform={`rotate(-90 ${c} ${c})`} />
      )}
      <text x="50%" y="50%" textAnchor="middle" dy="0.35em" className="mono"
        fill="var(--text)" fontSize={size * 0.2} fontWeight={700}>
        {connected && soc != null ? `${Math.round(soc)}%` : "—"}
      </text>
    </svg>
  );
}
