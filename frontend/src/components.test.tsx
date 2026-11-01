import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
import { Records, Editor, Badge } from "./components";
import { interpolate, flatten } from "./api";
afterEach(cleanup);
describe("shared workspace components", () => {
  it("shows empty state", () => {
    render(<Records rows={[]} columns={["name"]} />);
    expect(screen.getByText(/No records yet/)).toBeInTheDocument();
  });
  it("renders and edits a record", () => {
    const edit = vi.fn();
    render(
      <Records
        rows={[{ id: "1", name: "Commerce", status: "APPROVED" }]}
        columns={["name", "status"]}
        onEdit={edit}
      />,
    );
    fireEvent.click(screen.getByTitle("Edit record"));
    expect(edit).toHaveBeenCalledWith(expect.objectContaining({ id: "1" }));
  });
  it("rejects invalid JSON before saving", async () => {
    const save = vi.fn();
    render(
      <Editor
        title="Test"
        value={{ name: "Test" }}
        onClose={() => {}}
        onSave={save}
      />,
    );
    fireEvent.change(screen.getByLabelText("Record JSON"), {
      target: { value: "{" },
    });
    fireEvent.click(screen.getByText("Save record"));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(save).not.toHaveBeenCalled();
  });
  it("preserves unresolved environment variables", () => {
    expect(
      interpolate("{{baseUrl}}/{{missing}}", {
        baseUrl: "https://example.com",
      }),
    ).toBe("https://example.com/{{missing}}");
  });
  it("flattens nested collections", () => {
    expect(
      flatten([{ item: [{ name: "a" }] }, { name: "b" }]).map((i) => i.name),
    ).toEqual(["a", "b"]);
  });
  it("renders status", () => {
    render(<Badge tone="green">Connected</Badge>);
    expect(screen.getByText("Connected")).toHaveClass("green");
  });
});
