import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import type { Row } from "./api";

const apiMock = vi.hoisted(() => ({
  api: {
    validateSplunk: vi.fn(),
    mappingPreview: vi.fn(),
    savedSearches: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    importHar: vi.fn(),
    inferCaptures: vi.fn(),
    connectorVersions: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    saveConnectorVersion: vi.fn((): Promise<Row> => Promise.resolve({})),
    validateVersion: vi.fn((): Promise<Row> => Promise.resolve({})),
    activateVersion: vi.fn((): Promise<Row> => Promise.resolve({})),
    codegen: vi.fn(),
    validateGenerated: vi.fn(),
    proposePr: vi.fn(),
  },
}));

vi.mock("./monitoring-api", async importOriginal => ({
  ...(await importOriginal<typeof import("./monitoring-api")>()),
  api: apiMock.api,
}));
vi.mock("./api", async importOriginal => ({
  ...(await importOriginal<typeof import("./api")>()),
  request: vi.fn((): Promise<Row[]> => Promise.resolve([])),
  post: vi.fn((): Promise<Row> => Promise.resolve({})),
  del: vi.fn((): Promise<Row> => Promise.resolve({})),
}));

import { SplunkPanel, ImportPanel, InferenceReview, CodegenPanel, VersionsPanel } from "./ConnectorBuilder";

const act = vi.fn(async (_l: string, work: () => Promise<unknown>) => { await work(); });
const connection: Row = {
  id: "c1", provider: "splunk", name: "Splunk", state: "CONNECTED",
  baseUrl: "https://splunk:8089",
};

afterEach(cleanup);

describe("SplunkPanel", () => {
  it("shows validate/mapping/saved-search controls and renders validation checks", async () => {
    apiMock.api.validateSplunk.mockResolvedValue({
      authenticate: { ok: true, identity: "svc" },
      search: { ok: true, index: "main" },
      retrieve: { ok: true, rows: 20 },
      version: "9.1.2", apiVersion: "v2",
      quotas: { srchJobsQuota: 10 },
    });
    render(<SplunkPanel connection={connection} onSaved={vi.fn()} act={act} busy="" />);
    expect(screen.getByLabelText("Splunk deployment")).toBeInTheDocument();
    expect(screen.getByLabelText("Approved indexes")).toBeInTheDocument();
    fireEvent.click(screen.getByText("Validate"));
    await waitFor(() => expect(apiMock.api.validateSplunk).toHaveBeenCalledWith("c1"));
    expect(await screen.findByText("Authenticate")).toBeInTheDocument();
    expect(await screen.findByText(/v2/)).toBeInTheDocument();
  });
});

describe("ImportPanel", () => {
  it("imports a HAR file and reports what was sanitized", async () => {
    apiMock.api.importHar.mockResolvedValue({
      captures: [{ method: "POST", url: "https://app/chat", status: 200 }],
      removed: { entries: 5, headers: ["cookie", "authorization"], bodyFields: ["password"] },
    });
    apiMock.api.inferCaptures.mockResolvedValue({ chatPath: "/chat" });
    const onInferred = vi.fn();
    render(<ImportPanel onInferred={onInferred} act={act} busy="" />);
    const file = new File(["{}"], "capture.har", { type: "application/json" });
    fireEvent.change(screen.getByLabelText("HAR file"), { target: { files: [file] } });
    await waitFor(() => expect(apiMock.api.importHar).toHaveBeenCalled());
    expect(await screen.findByText(/Kept 1 captures/)).toBeInTheDocument();
    expect(screen.getByText(/headers \[cookie, authorization\]/)).toBeInTheDocument();
    fireEvent.click(screen.getByText("Infer from HAR"));
    await waitFor(() => expect(onInferred).toHaveBeenCalledWith({ chatPath: "/chat" }));
  });
});

describe("InferenceReview", () => {
  const inferred: Row = {
    steps: [{ method: "POST", path: "/chat" }],
    evidence: {
      chatPath: { confidence: "HIGH", detail: "POST /chat", captureIds: ["c1"] },
      aiResponsePointer: { confidence: "LOW", detail: "/answer guessed", captureIds: ["c2"] },
    },
    notes: ["signed parameter detected"],
  };

  it("flags LOW-confidence fields and requires explicit confirmation + write authorization", async () => {
    const onChange = vi.fn();
    render(<InferenceReview inferred={inferred} config={inferred} onChange={onChange}
      environments={[{ id: "e1", name: "staging" }]} />);
    expect(screen.getByText("LOW")).toBeInTheDocument();
    expect(screen.getByText(/Confirm each LOW-confidence field/)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Confirm aiResponsePointer"));
    fireEvent.click(screen.getByLabelText("Authorize POST /chat"));
    fireEvent.change(screen.getByLabelText("Test environment"), { target: { value: "e1" } });
    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(inferred, {
      confirmedFields: ["aiResponsePointer"],
      authorizedOperations: [{ method: "POST", path: "/chat" }],
      environmentId: "e1",
    }));
  });
});

describe("CodegenPanel", () => {
  it("generates review files and proposes a PR through reviewed-write", async () => {
    apiMock.api.codegen.mockResolvedValue({
      files: [{ path: "connector.json", content: "{}" },
              { path: "AcmeAdapter.java", content: "class AcmeAdapter {}" }],
    });
    apiMock.api.proposePr.mockResolvedValue({ id: "evt1", status: "PENDING",
      action: { body: { branch: "connector/acme-a1b2" } } });
    const conn = { ...connection, provider: "custom", name: "acme" };
    render(<CodegenPanel connection={conn}
      githubConnections={[{ id: "gh1", name: "gh" }]} act={act} busy="" />);
    fireEvent.click(screen.getByText("Generate code"));
    expect(await screen.findByText("AcmeAdapter.java")).toBeInTheDocument();
    expect(screen.getByText(/REVIEW ONLY/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Target repo"), { target: { value: "acme/platform" } });
    fireEvent.click(screen.getByText(/Create PR/));
    await waitFor(() => expect(apiMock.api.proposePr).toHaveBeenCalledWith("c1",
      expect.objectContaining({ githubConnectionId: "gh1", repo: "acme/platform" })));
    expect(await screen.findByText(/PENDING/)).toBeInTheDocument();
  });
});

describe("VersionsPanel", () => {
  it("loads versions and drives draft → validated → active", async () => {
    apiMock.api.connectorVersions.mockResolvedValue([
      { id: "v1", version: 1, status: "DRAFT", schemaHash: "deadbeef" },
      { id: "v2", version: 2, status: "VALIDATED", schemaHash: "cafef00d" },
    ]);
    render(<VersionsPanel connection={connection} />);
    expect(await screen.findByText("v1")).toBeInTheDocument();
    fireEvent.click(screen.getByText("Mark validated"));
    await waitFor(() => expect(apiMock.api.validateVersion).toHaveBeenCalledWith("v1"));
    fireEvent.click(screen.getByText("Activate"));
    await waitFor(() => expect(apiMock.api.activateVersion).toHaveBeenCalledWith("v2"));
  });
});
