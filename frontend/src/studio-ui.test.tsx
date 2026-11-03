import { afterEach, describe, it, expect, vi } from "vitest";
import {
  cleanup,
  render,
  screen,
  fireEvent,
  waitFor,
  within,
} from "@testing-library/react";
import RequestStudio from "./RequestStudio";
import TestGeneration from "./TestGeneration";
import { request, save, post } from "./api";
vi.mock("./api", async (original) => ({
  ...(await original<typeof import("./api")>()),
  request: vi.fn(),
  save: vi.fn(),
  post: vi.fn(),
}));
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});
const collection = {
  id: "c",
  name: "Orders",
  revision: 1,
  item: [
    {
      name: "Read orders",
      request: {
        method: "GET",
        url: "{{baseUrl}}/orders",
        header: [{ key: "X-Team", value: "test" }],
      },
    },
  ],
};
const props = {
  data: { collections: [collection], history: [] },
  spec: undefined,
  reload: vi.fn(async () => {}),
  run: vi.fn(),
  edit: vi.fn(),
  setPage: vi.fn(),
};
describe("Request Studio interactions", () => {
  it("preserves separate request state across tabs", () => {
    render(<RequestStudio {...props} />);
    fireEvent.change(screen.getByLabelText("Request URL"), {
      target: { value: "https://example.com/first" },
    });
    fireEvent.click(screen.getByLabelText("New request tab"));
    fireEvent.change(screen.getByLabelText("Request URL"), {
      target: { value: "https://example.com/second" },
    });
    fireEvent.click(screen.getByRole("tab", { name: /GET New request/ }));
    expect(screen.getByLabelText("Request URL")).toHaveValue(
      "https://example.com/first",
    );
  });
  it("loads saved headers and displays real response assertions", async () => {
    vi.mocked(request).mockResolvedValue({
      status: 200,
      body: '{"ok":true}',
      headers: { "content-type": "application/json" },
      durationMs: 12,
    });
    render(<RequestStudio {...props} />);
    fireEvent.click(screen.getByText("Read orders"));
    fireEvent.click(screen.getByRole("tab", { name: /^Headers/ }));
    expect(screen.getByLabelText("Header 1 key")).toHaveValue("X-Team");
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await screen.findByText("200", { selector: "b" });
    expect(request).toHaveBeenCalledWith(
      "/client/send",
      expect.objectContaining({
        body: expect.stringContaining("https://httpbin.org/orders"),
      }),
    );
    fireEvent.click(screen.getByRole("button", { name: /Test Results/ }));
    expect(screen.getByText("PASS")).toBeInTheDocument();
  });
  it("shows unresolved-variable errors without sending", async () => {
    render(<RequestStudio {...props} />);
    fireEvent.change(screen.getByLabelText("Request URL"), {
      target: { value: "{{missing}}/orders" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await screen.findByText(/Unresolved variable in request/);
    expect(request).not.toHaveBeenCalled();
  });
  it("updates an existing request without duplication", async () => {
    vi.mocked(save).mockResolvedValue(collection);
    render(<RequestStudio {...props} />);
    fireEvent.click(screen.getByText("Read orders"));
    fireEvent.change(screen.getByLabelText("Request name"), {
      target: { value: "Updated orders" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^Save / }));
    fireEvent.click(screen.getByRole("button", { name: "Save request" }));
    await waitFor(() =>
      expect(save).toHaveBeenCalledWith(
        "collections",
        expect.objectContaining({
          id: "c",
          item: [expect.objectContaining({ name: "Updated orders" })],
        }),
      ),
    );
  });
  it("imports curl into a new request tab", async () => {
    render(<RequestStudio {...props} />);
    fireEvent.click(screen.getByRole("button", { name: "Import" }));
    fireEvent.change(screen.getByLabelText("Import content"), {
      target: {
        value:
          "curl 'https://example.com/create' -H 'Content-Type: application/json' --data-raw '{\"id\":1}'",
      },
    });
    fireEvent.click(screen.getAllByRole("button", { name: "Import" })[1]);
    await waitFor(() =>
      expect(screen.getByLabelText("Request URL")).toHaveValue(
        "https://example.com/create",
      ),
    );
    expect(screen.getByLabelText("HTTP method")).toHaveValue("POST");
  });
});
describe("save request to another collection",()=>{
 it("copies a saved request using a fresh identity and preserves its documented examples",async()=>{vi.mocked(save).mockResolvedValue({id:"other"});const original={...collection,item:[{...collection.item[0],"x-studio-id":"saved-item",response:[{name:"OK",code:200,body:'{"ok":true}'}]}]};render(<RequestStudio {...props} data={{collections:[original,{id:"other",name:"Other",item:[],revision:1}],history:[]}}/>);fireEvent.click(screen.getByText("Read orders"));fireEvent.click(screen.getByRole("button",{name:/^Save /}));fireEvent.change(screen.getByLabelText("Save collection"),{target:{value:"other"}});fireEvent.click(screen.getByRole("button",{name:"Save request"}));await waitFor(()=>expect(save).toHaveBeenCalled());const sent=vi.mocked(save).mock.calls[0][1];expect(sent.id).toBe("other");expect(sent.item[0]["x-studio-id"]).not.toBe("saved-item");expect(sent.item[0].response[0].code).toBe(200);});
});
describe("collection management dialogs",()=>{
 it("requires confirmation before deleting a collection and detaches open requests",async()=>{vi.mocked(request).mockResolvedValue(null);render(<RequestStudio {...props}/>);fireEvent.click(screen.getByText("Read orders"));fireEvent.click(screen.getByLabelText("Actions for Orders"));fireEvent.click(screen.getByText("Delete collection"));expect(request).not.toHaveBeenCalled();expect(screen.getByRole("dialog",{name:"Delete collection"})).toBeInTheDocument();fireEvent.click(within(screen.getByRole("dialog",{name:"Delete collection"})).getByRole("button",{name:"Delete"}));await waitFor(()=>expect(request).toHaveBeenCalledWith("/records/collections/c",{method:"DELETE"}));expect(screen.getByLabelText("Request name")).toHaveValue("Read orders");});
});
describe("API-linked test generation", () => {
  const spec = {
    id: "s",
    name: "Orders API",
    version: "1",
    revision: 2,
    document: { paths: { "/orders": { get: {} } } },
  };
  it("generates from the selected saved API", async () => {
    vi.mocked(post).mockResolvedValue({ cases: [], suites: [], plan: {} });
    render(<TestGeneration data={{}} spec={spec} reload={props.reload} />);
    fireEvent.click(screen.getByText("Generate cases, suites & plan"));
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith("/tests/generate/s/package"),
    );
  });
  it("requires consent and a separate save after AI preview", async () => {
    const bundle = { cases: [], suites: [], plans: [] };
    vi.mocked(post)
      .mockResolvedValueOnce({
        specId: "s",
        specRevision: 2,
        integrationId: "ai",
        bundle,
      })
      .mockResolvedValueOnce({ cases: [], suites: [], plans: [] });
    render(
      <TestGeneration
        data={{
          integrations: [
            {
              id: "ai",
              name: "Approved Stylus gateway",
              enabled: true,
              testGenerationEnabled: true,
            },
          ],
        }}
        spec={spec}
        reload={props.reload}
      />,
    );
    fireEvent.change(screen.getByLabelText("Test generation provider"), {
      target: { value: "ai" },
    });
    expect(screen.getByText("Preview AI test package")).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByText("Preview AI test package"));
    await screen.findByLabelText("Generated test package");
    expect(post).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByText("Save reviewed package"));
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        "/tests/generate/s/accept",
        expect.objectContaining({ specId: "s", bundle }),
      ),
    );
  });
});
