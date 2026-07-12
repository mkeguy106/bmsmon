import { Icon } from "./icons";
import { NAV_GROUPS, type V2View } from "../nav";

const EXPANDED_W = 216;
const COLLAPSED_W = 64;

export function Nav({ view, collapsed, unackedCount, onSelect, onToggleCollapse }: {
  view: V2View; collapsed: boolean; unackedCount: number;
  onSelect: (v: V2View) => void; onToggleCollapse: () => void;
}) {
  return (
    <nav
      style={{
        width: collapsed ? COLLAPSED_W : EXPANDED_W,
        flex: `0 0 ${collapsed ? COLLAPSED_W : EXPANDED_W}px`,
        background: "var(--nav-bg)",
        borderRight: "1px solid var(--border)",
        display: "flex",
        flexDirection: "column",
        height: "100%",
        transition: "width 0.15s ease",
        overflow: "hidden",
      }}
    >
      <div style={{ flex: 1, overflowY: "auto", padding: "12px 8px" }}>
        {NAV_GROUPS.map((group) => (
          <div key={group.label} style={{ marginBottom: 20 }}>
            {!collapsed && (
              <div
                className="eyebrow"
                style={{ padding: "0 8px", marginBottom: 6 }}
              >
                {group.label}
              </div>
            )}
            {group.items.map((item) => {
              const active = !item.disabled && item.view === view;
              const showBadge = item.badge && unackedCount > 0;
              return (
                <button
                  key={item.label}
                  type="button"
                  disabled={item.disabled}
                  onClick={() => !item.disabled && onSelect(item.view)}
                  title={collapsed ? item.label : undefined}
                  style={{
                    position: "relative",
                    display: "flex",
                    alignItems: "center",
                    gap: 10,
                    width: "100%",
                    padding: collapsed ? "10px" : "8px 10px",
                    justifyContent: collapsed ? "center" : "flex-start",
                    marginBottom: 2,
                    border: "none",
                    borderRadius: 6,
                    background: active ? "var(--nav-active)" : "transparent",
                    color: item.disabled ? "var(--text-4)" : active ? "var(--text)" : "var(--text-3)",
                    cursor: item.disabled ? "default" : "pointer",
                    font: "inherit",
                    fontSize: 13,
                    textAlign: "left",
                  }}
                  onMouseEnter={(e) => {
                    if (!item.disabled && !active) e.currentTarget.style.background = "var(--hover)";
                  }}
                  onMouseLeave={(e) => {
                    if (!item.disabled && !active) e.currentTarget.style.background = "transparent";
                  }}
                >
                  {active && (
                    <span
                      style={{
                        position: "absolute",
                        left: -8,
                        top: 4,
                        bottom: 4,
                        width: 3,
                        borderRadius: 2,
                        background: "var(--text)",
                      }}
                    />
                  )}
                  <Icon name={item.icon} size={18} />
                  {!collapsed && (
                    <span style={{ flex: 1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                      {item.label}
                    </span>
                  )}
                  {!collapsed && item.disabled && (
                    <span
                      className="eyebrow"
                      style={{ fontSize: 8.5, color: "var(--text-5)" }}
                    >
                      SOON
                    </span>
                  )}
                  {showBadge && (
                    <span
                      style={{
                        display: "inline-flex",
                        alignItems: "center",
                        justifyContent: "center",
                        minWidth: 16,
                        height: 16,
                        padding: "0 4px",
                        borderRadius: 8,
                        background: "var(--live)",
                        color: "var(--badge-text)",
                        fontSize: 10,
                        fontWeight: 600,
                        position: collapsed ? "absolute" : "static",
                        top: collapsed ? 2 : undefined,
                        right: collapsed ? 2 : undefined,
                      }}
                    >
                      {unackedCount}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        ))}
      </div>
      <div style={{ borderTop: "1px solid var(--border)", padding: 8 }}>
        <button
          type="button"
          onClick={onToggleCollapse}
          aria-label={collapsed ? "Expand navigation" : "Collapse navigation"}
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: collapsed ? "center" : "flex-end",
            width: "100%",
            padding: 8,
            border: "none",
            borderRadius: 6,
            background: "transparent",
            color: "var(--text-3)",
            cursor: "pointer",
          }}
          onMouseEnter={(e) => { e.currentTarget.style.background = "var(--hover)"; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = "transparent"; }}
        >
          <span
            style={{
              display: "inline-flex",
              transform: collapsed ? "rotate(0deg)" : "rotate(180deg)",
              transition: "transform 0.15s ease",
            }}
          >
            <Icon name="chevron" size={16} />
          </span>
        </button>
      </div>
    </nav>
  );
}
