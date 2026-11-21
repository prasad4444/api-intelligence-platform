import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import type { Row } from "./api";

const apiMock = vi.hoisted(() => ({
  api: {
    notifications: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    reports: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    splunkTemplates: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    savedSearches: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    previewReport: vi.fn(),
    saveReport: vi.fn(),
    runReport: vi.fn(),
    reportSnapshots: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    schedules: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    saveSchedule: vi.fn(),
    schedulePreview: vi.fn((): Promise<string[]> => Promise.resolve(["2026-03-01T10:00:00Z", "2026-03-01T11:00:00Z"])),
    pauseSchedule: vi.fn(),
    resumeSchedule: vi.fn(),
    runNow: vi.fn(),
    scheduleRuns: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    cancelRun: vi.fn(),
    deleteSchedule: vi.fn(),
    alertRules: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    saveAlertRule: vi.fn(),
    deleteAlertRule: vi.fn(),
    backtestRule: vi.fn(() => Promise.resolve([{ breach: true, entityKey: "svc-a" }])),
    incidents: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    destinations: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    saveDestination: vi.fn(),
    previewDestination: vi.fn(),
    enableDestination: vi.fn(),
    disableDestination: vi.fn(),
    outbox: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    markRead: vi.fn(),
    investigations: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    createInvestigation: vi.fn(),
    investigation: vi.fn(),
    previewInvestigation: vi.fn(),
    jiraDraft: vi.fn(),
    resolveInvestigation: vi.fn(),
    knowledgeSearch: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    knowledgeItem: vi.fn(),
    connectorHealth: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    connectorVersions: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    connectorCatalog: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    connectorDashboard: vi.fn((): Promise<Row> => Promise.resolve({})),
    correlation: vi.fn(),
    playbooks: vi.fn((): Promise<Row[]> => Promise.resolve([])),
    costLimits: vi.fn((): Promise<Row[]> => Promise.resolve([])),
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

import { Monitoring, KnowledgeTab } from "./Monitoring";

const splunkConn: Row = {
  id: "s1", provider: "splunk", name: "Prod Splunk", state: "CONNECTED",
  baseUrl: "https://splunk:8089",
};
const props = { data: { connections: [splunkConn] }, reload: vi.fn(), run: vi.fn(),
  edit: vi.fn(), spec: undefined, setPage: vi.fn() };

const snapshot = {
  id: "snap1", completeness: "COMPLETE",
  resolvedWindows: {
    current: { start: "2026-03-01T10:00:00Z", end: "2026-03-01T11:00:00Z",
      localStart: "10:00", localEnd: "11:00", zone: "UTC", durationSeconds: 3600 },
    baselines: [{ start: "2026-03-01T09:00:00Z", end: "2026-03-01T10:00:00Z",
      localStart: "09:00", localEnd: "10:00", zone: "UTC", durationSeconds: 3600 }],
    notes: [],
  },
  metrics: [
    { name: "error_rate", current: 0.08, baseline: 0.02, absoluteChange: 0.06,
      relativeChange: 3.0, ppChange: 6.0, status: "MEASURED",
      provenance: { query: "search index=main", sid: "abc", apiVersion: "v2" },
      drilldownUrl: "https://splunk/en-US/app/search?q=x" },
    { name: "requests", current: null, baseline: null, status: "MISSING" },
  ],
};

afterEach(cleanup);

describe("Monitoring", () => {
  it("renders the report designer with all comparison presets", async () => {
    render(<Monitoring {...props} />);
    expect(await screen.findByLabelText("Report name")).toBeInTheDocument();
    const preset = screen.getByLabelText("Comparison preset") as HTMLSelectElement;
    const labels = [...preset.options].map(o => o.textContent);
    expect(labels).toContain("Today so far vs last week same weekday");
    expect(labels).toContain("Last 60 min vs same window a week earlier");
    expect(labels).toContain("Current vs N previous weeks");
    expect(screen.getByLabelText("Completed buckets only")).toBeInTheDocument();
  });

  it("previews a comparison and renders metrics with MISSING status", async () => {
    apiMock.api.previewReport.mockResolvedValue(snapshot);
    render(<Monitoring {...props} />);
    fireEvent.click(await screen.findByText("Preview"));
    await waitFor(() => expect(apiMock.api.previewReport).toHaveBeenCalled());
    expect((await screen.findAllByText("error_rate")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("MISSING").length).toBeGreaterThan(0);
    expect(screen.getAllByText("COMPLETE").length).toBeGreaterThan(0);
  });

  it("saves a report definition once a connection is chosen", async () => {
    apiMock.api.saveReport.mockResolvedValue({ id: "r1" });
    render(<Monitoring {...props} />);
    fireEvent.change(await screen.findByLabelText("Report name"), { target: { value: "Health" } });
    const select = screen.getByLabelText("Splunk connections") as HTMLSelectElement;
    (select.options[0] as HTMLOptionElement).selected = true;
    fireEvent.change(select);
    const save = screen.getByText("Save report") as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    fireEvent.click(save);
    await waitFor(() => expect(apiMock.api.saveReport).toHaveBeenCalledWith(
      expect.objectContaining({ name: "Health", connectionIds: ["s1"] })));
  });

  it("previews upcoming schedule runs automatically", async () => {
    render(<Monitoring {...props} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Schedules" }));
    await waitFor(() => expect(apiMock.api.schedulePreview).toHaveBeenCalled());
    expect(await screen.findByText(/Upcoming:/)).toBeInTheDocument();
    expect(screen.getByLabelText("Missed run policy")).toBeInTheDocument();
    expect(screen.getByLabelText("Retry limit")).toBeInTheDocument();
    expect(screen.getByLabelText("Deadline seconds")).toBeInTheDocument();
  });

  it("runs an alert rule backtest against stored snapshots", async () => {
    apiMock.api.alertRules.mockResolvedValue([
      { id: "ar1", name: "error spike", metric: "error_rate", severity: "HIGH",
        conditions: [{ type: "ABSOLUTE", op: ">", value: 0.05 }] },
    ]);
    render(<Monitoring {...props} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Alerts" }));
    fireEvent.click(await screen.findByText("Backtest"));
    await waitFor(() => expect(apiMock.api.backtestRule).toHaveBeenCalledWith("ar1"));
    expect(await screen.findByText("BREACH")).toBeInTheDocument();
  });

  it("requires payload preview before enabling a destination", async () => {
    apiMock.api.destinations.mockResolvedValue([
      { id: "d1", name: "hook", type: "WEBHOOK", enabled: false, hasUrl: true },
    ]);
    apiMock.api.previewDestination.mockResolvedValue({ payload: { text: "hi" } });
    render(<Monitoring {...props} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Notifications" }));
    const enable = await screen.findByText("Enable") as HTMLButtonElement;
    expect(enable.disabled).toBe(true);
    fireEvent.click(screen.getByText("Preview"));
    await waitFor(() => expect(apiMock.api.previewDestination).toHaveBeenCalledWith("d1"));
    await waitFor(() => expect((screen.getByText("Enable") as HTMLButtonElement).disabled).toBe(false));
  });
});

describe("KnowledgeTab", () => {
  it("searches knowledge and shows stale badges", async () => {
    apiMock.api.knowledgeSearch.mockResolvedValue([
      { item: { id: "k1", kind: "INCIDENT_RESOLUTION", title: "DB pool exhausted",
        body: "raised limits" }, stale: false },
      { item: { id: "k2", kind: "CHAT_MESSAGE", title: "chat about deploys",
        body: "..." }, stale: true, reason: "source deleted" },
    ]);
    render(<KnowledgeTab />);
    fireEvent.change(screen.getByLabelText("Knowledge query"), { target: { value: "pool" } });
    fireEvent.click(screen.getByRole("button", { name: /search/i }));
    expect(await screen.findByText("DB pool exhausted")).toBeInTheDocument();
    expect(screen.getByText(/stale/i)).toBeInTheDocument();
    expect(apiMock.api.knowledgeSearch).toHaveBeenCalledWith("pool", expect.anything());
  });
});
