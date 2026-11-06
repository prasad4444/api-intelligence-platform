import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Workspace } from "./pages";
import { post, save } from "./api";
vi.mock("./api", async (original) => ({ ...(await original<typeof import("./api")>()), post: vi.fn(), save: vi.fn() }));
afterEach(() => { cleanup(); vi.clearAllMocks(); });
const document = { openapi: "3.0.3", info: { title: "Original API", version: "1" }, paths: { "/old": { get: { summary: "Old endpoint" } } } };
const spec = { id: "old", revision: 3, document };
const source = `openapi: 3.0.3
info:
  title: New API
  version: '2'
paths:
  /new:
    post:
      summary: New endpoint
`;
const setup = (selected: any = spec) => {
  const selectSpec = vi.fn();
  const run = vi.fn(async (fn: () => Promise<unknown>) => fn());
  const view = render(<Workspace spec={selected} data={{specs: [selected]}} run={run} edit={vi.fn()} reload={vi.fn()} setPage={vi.fn()} selectSpec={selectSpec} />);
  fireEvent.click(screen.getByText("Split source"));
  return { ...view, selectSpec };
};
describe("contract workspace", () => {
  it("loads the selected document and previews pasted YAML immediately", () => {
    setup();
    expect(screen.getByText("/old")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("OpenAPI source"), {target: {value: source}});
    expect(screen.getByText("/new")).toBeInTheDocument();
    expect(screen.queryByText("/old")).not.toBeInTheDocument();
  });
  it("previews a newly opened YAML file", async () => {
    const {container} = setup();
    fireEvent.change(container.querySelector('input[type="file"]')!, {
      target: {files: [{text: async () => source}]},
    });
    await screen.findByText("/new");
    expect(screen.queryByText("/old")).not.toBeInTheDocument();
  });
  it("refreshes the editor when API selection changes", () => {
    const view = setup();
    view.rerender(<Workspace spec={{id: "second", document: {...document, info: {title: "Second", version: "2"}, paths: {"/second": {get: {}}}}}} data={{specs: []}} run={vi.fn()} edit={vi.fn()} reload={vi.fn()} setPage={vi.fn()} />);
    expect(screen.getByText("/second")).toBeInTheDocument();
    expect(screen.queryByText("/old")).not.toBeInTheDocument();
  });
  it("selects the contract returned by import", async () => {
    vi.mocked(post).mockResolvedValue({id: "new"});
    const {selectSpec} = setup();
    fireEvent.click(screen.getByText("Import contract"));
    await waitFor(() => expect(selectSpec).toHaveBeenCalledWith("new"));
  });
  it("saves the YAML inside document while preserving id and revision", async () => {
    vi.mocked(save).mockResolvedValue({id: "old"});
    setup();
    fireEvent.change(screen.getByLabelText("OpenAPI source"), {target: {value: source}});
    fireEvent.click(screen.getByText("Save selected contract"));
    await waitFor(() => expect(save).toHaveBeenCalledWith("specs", expect.objectContaining({id: "old", revision: 3, status: "DRAFT", document: expect.objectContaining({paths: {"/new": {post: {summary: "New endpoint"}}}})})));
  });
  it("shows validation errors without crashing on incomplete YAML", () => {
    setup();
    fireEvent.change(screen.getByLabelText("OpenAPI source"), {target: {value: "paths: ["}});
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("Save selected contract")).toBeDisabled();
  });
  it("survives an existing record with no document", () => {
    setup({id: "broken"});
    expect(screen.getByLabelText("OpenAPI source")).toBeInTheDocument();
  });
});
describe("workspace action buttons",()=>{
 const selected={...spec,status:"DRAFT"};
 function actions(current=selected,roles=["ROLE_ADMIN"]){const setPage=vi.fn();render(<Workspace spec={current} data={{specs:[current]}} run={async fn=>fn()} edit={vi.fn()} reload={vi.fn()} setPage={setPage} roles={roles}/>);fireEvent.click(screen.getByText("Split source"));return setPage;}
 it("requests review with the selected saved revision",async()=>{vi.mocked(post).mockResolvedValue({...selected,status:"IN_REVIEW"});actions();fireEvent.click(screen.getByText("Request review"));await waitFor(()=>expect(post).toHaveBeenCalledWith("/workspace/old/approval",{status:"IN_REVIEW",expectedRevision:3}));});
 it("persists edit-as-draft instead of merely replacing editor text",async()=>{vi.mocked(post).mockResolvedValue(selected);actions({...selected,status:"APPROVED"});fireEvent.click(screen.getByText("Edit as draft"));await waitFor(()=>expect(post).toHaveBeenCalledWith("/workspace/old/draft",{expectedRevision:3}));});
 it("blocks stale unsaved YAML and hides unauthorized approval capability",()=>{actions({...selected,status:"IN_REVIEW"},["ROLE_DEVELOPER"]);expect(screen.getByText("Approve")).toBeDisabled();fireEvent.change(screen.getByLabelText("OpenAPI source"),{target:{value:source}});expect(screen.getByText("Create collection")).toBeDisabled();expect(screen.getByText("Edit as draft")).toBeDisabled();expect(post).not.toHaveBeenCalled();});
 it("shows where a created collection is available",async()=>{vi.mocked(post).mockResolvedValue({id:"c",name:"Ready collection",item:[{}]});const setPage=actions();fireEvent.click(screen.getByText("Create collection"));await screen.findByText(/Collection ready: Ready collection/);fireEvent.click(screen.getByText("Open Request Studio"));expect(setPage).toHaveBeenCalledWith("Request Studio");});
});
