import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { NotesCard } from "./NotesCard";

// Final review I3: the textarea enforces the server's 4000-character cap and counts toward it,
// so a long maintenance note can no longer fail to save on every try.
describe("NotesCard", () => {
  it("caps the note at the server's limit and shows the count", () => {
    const html = renderToStaticMarkup(<NotesCard baseId="2012" />);
    expect(html).toMatch(/maxLength="4000"/i);
    expect(html).toContain("0/4000");
  });
});
