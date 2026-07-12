import { Icon } from "./icons";
import { NAV_GROUPS, type V2View } from "../nav";

const BAR_H = 56;

/** All six real views, in NAV_GROUPS order (the disabled "Devices" placeholder is skipped). */
const TAB_ITEMS = NAV_GROUPS.flatMap((g) => g.items).filter((i) => !i.disabled);

export function BottomTabs({ view, unackedCount, onSelect }: {
  view: V2View; unackedCount: number; onSelect: (v: V2View) => void;
}) {
  return (
    <nav
      style={{
        position: "fixed",
        left: 0,
        right: 0,
        bottom: 0,
        height: BAR_H,
        display: "flex",
        background: "var(--nav-bg)",
        borderTop: "1px solid var(--border)",
        zIndex: 10,
      }}
    >
      {TAB_ITEMS.map((item) => {
        const active = item.view === view;
        const showBadge = item.badge && unackedCount > 0;
        return (
          <button
            key={item.view}
            type="button"
            onClick={() => onSelect(item.view)}
            aria-label={item.label}
            aria-current={active ? "page" : undefined}
            style={{
              position: "relative",
              flex: 1,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 3,
              border: "none",
              background: "transparent",
              color: active ? "var(--text)" : "var(--text-4)",
              cursor: "pointer",
              font: "inherit",
            }}
          >
            <span style={{ position: "relative" }}>
              <Icon name={item.icon} size={18} />
              {showBadge && (
                <span
                  style={{
                    position: "absolute",
                    top: -4,
                    right: -8,
                    display: "inline-flex",
                    alignItems: "center",
                    justifyContent: "center",
                    minWidth: 14,
                    height: 14,
                    padding: "0 3px",
                    borderRadius: 7,
                    background: "var(--live)",
                    color: "#fff",
                    fontSize: 9,
                    fontWeight: 600,
                  }}
                >
                  {unackedCount}
                </span>
              )}
            </span>
            <span style={{ fontSize: 9.5, whiteSpace: "nowrap" }}>{item.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
