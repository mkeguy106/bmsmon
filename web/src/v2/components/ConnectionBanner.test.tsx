import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { connectionBanner } from "../model/connection";
import { ConnectionBanner } from "./ConnectionBanner";

const render = (s: "expired" | "forbidden" | "unreachable") =>
  renderToStaticMarkup(<ConnectionBanner banner={connectionBanner(s)!} />);

describe("ConnectionBanner", () => {
  it("announces a critical banner assertively", () => {
    expect(render("expired")).toContain('role="alert"');
    expect(render("forbidden")).toContain('role="alert"');
  });
  it("keeps a warning a polite status", () => {
    const html = render("unreachable");
    expect(html).toContain('role="status"');
    expect(html).not.toContain('role="alert"');
  });
});
