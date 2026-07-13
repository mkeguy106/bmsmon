import type { IconName } from "./components/icons";

export type V2View = "command" | "health" | "journey" | "history" | "alerts" | "settings";

export interface NavItem { view: V2View; label: string; icon: IconName; disabled?: boolean; badge?: boolean }
export interface NavGroup { label: string; items: NavItem[] }

export const NAV_GROUPS: NavGroup[] = [
  { label: "MONITOR", items: [
    { view: "command", label: "Command", icon: "grid" },
    { view: "health", label: "Fleet Health", icon: "activity" },
    { view: "journey", label: "Journey", icon: "map-pin" },
    { view: "history", label: "History", icon: "bar-chart" },
  ] },
  { label: "MANAGE", items: [
    { view: "alerts", label: "Alerts", icon: "bell", badge: true },
    { view: "settings", label: "Settings", icon: "gear" },
  ] },
];
