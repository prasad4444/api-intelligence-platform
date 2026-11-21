import { del, post, request, type Row } from "./api";

// ------------------------------------------------------------- observability

export type SplunkValidation = {
  authenticate?: { ok: boolean; identity?: string; error?: string };
  search?: { index?: string; ok?: boolean; error?: string };
  retrieve?: { ok?: boolean; rows?: number; error?: string };
  version?: string;
  apiVersion?: string;
  quotas?: Row;
  error?: string;
};

export type ResolvedWindow = {
  start: string;
  end: string;
  localStart: string;
  localEnd: string;
  zone: string;
  durationSeconds: number;
  partial?: boolean;
  startEpoch?: number;
  endEpoch?: number;
};

export type MetricRow = {
  id?: string;
  name?: string;
  metric?: string;
  label?: string;
  current?: number | null;
  baseline?: number | null;
  absoluteChange?: number | null;
  relativeChange?: number | null;
  ppChange?: number | null;
  ratePerMinute?: number | null;
  unit?: string;
  status?: "MEASURED" | "MISSING" | "PARTIAL";
  provenance?: Row;
  drilldownUrl?: string;
  splunkWebUrl?: string;
  [key: string]: unknown;
};

export type Snapshot = {
  id: string;
  reportId: string;
  reportVersion?: number;
  executionKey?: string;
  plannedAt?: string;
  evaluatedAt?: string;
  resolvedWindows?: { current: ResolvedWindow; baselines: ResolvedWindow[]; notes?: string[]; [k: string]: unknown };
  metrics?: MetricRow[];
  series?: Row;
  freshness?: Row;
  completeness?: string;
  errors?: Row[];
  aiSummary?: string;
  createdAt?: string;
};

export const PRESETS: { id: string; label: string }[] = [
  { id: "LAST_5M", label: "Last 5 min vs preceding" },
  { id: "LAST_15M", label: "Last 15 min vs preceding" },
  { id: "LAST_30M", label: "Last 30 min vs preceding" },
  { id: "LAST_1H", label: "Last 1 h vs previous" },
  { id: "LAST_6H", label: "Last 6 h vs previous" },
  { id: "LAST_24H", label: "Last 24 h vs previous" },
  { id: "TODAY_SO_FAR", label: "Today so far vs last week same weekday" },
  { id: "LAST_60M", label: "Last 60 min vs same window a week earlier" },
  { id: "CUSTOM", label: "Custom range vs custom baseline" },
  { id: "MULTI_WEEK", label: "Current vs N previous weeks" },
];

export const METRIC_CHOICES = [
  "requests", "error_rate", "errors", "latency_p50", "latency_p95", "latency_p99",
  "distinct_services", "top_exceptions",
] as const;

export const CHART_TYPES = ["TIMESERIES", "OVERLAY", "TABLE", "TOP_ERRORS"] as const;

export const CONDITION_TYPES = [
  "ABSOLUTE", "RATE_CHANGE", "BASELINE_DEVIATION", "NEW_ERROR_SIGNATURE",
  "MISSING_TRAFFIC", "CONNECTOR_FAILURE", "REPORT_FAILURE",
] as const;

export const api = {
  // splunk
  validateSplunk: (connectionId: string): Promise<SplunkValidation> =>
    post("/splunk/" + connectionId + "/validate", {}),
  savedSearches: (connectionId: string): Promise<Row[]> =>
    request("/splunk/" + connectionId + "/saved-searches"),
  mappingPreview: (connectionId: string, input: Row = {}): Promise<Row> =>
    post("/splunk/" + connectionId + "/mapping-preview", input),
  saveSplunkTemplate: (input: Row): Promise<Row> => post("/splunk/templates", input),
  splunkTemplates: (): Promise<Row[]> => request("/splunk/templates"),
  deleteSplunkTemplate: (id: string) => del("/splunk/templates/" + id),
  // reports
  reports: (): Promise<Row[]> => request("/reports"),
  saveReport: (input: Row): Promise<Row> => post("/reports", input),
  previewReport: (input: Row): Promise<Snapshot> => post("/reports/preview", input),
  runReport: (id: string): Promise<Row> => post("/reports/" + id + "/run", {}),
  reportSnapshots: (id: string): Promise<Row[]> => request("/reports/" + id + "/snapshots"),
  snapshot: (id: string): Promise<Snapshot> => request("/reports/snapshots/" + id),
  // schedules
  schedules: (): Promise<Row[]> => request("/schedules"),
  saveSchedule: (input: Row): Promise<Row> => post("/schedules", input),
  schedulePreview: (kind: string, expr: string, zone: string, count = 5): Promise<string[]> =>
    request("/schedules/preview?kind=" + kind + "&expr=" + encodeURIComponent(expr)
      + "&zone=" + encodeURIComponent(zone) + "&count=" + count),
  pauseSchedule: (id: string): Promise<Row> => post("/schedules/" + id + "/pause", {}),
  resumeSchedule: (id: string): Promise<Row> => post("/schedules/" + id + "/resume", {}),
  runNow: (id: string): Promise<Row> => post("/schedules/" + id + "/run-now", {}),
  scheduleRuns: (id: string): Promise<Row[]> => request("/schedules/" + id + "/runs"),
  cancelRun: (runId: string): Promise<Row> => post("/schedules/runs/" + runId + "/cancel", {}),
  deleteSchedule: (id: string) => del("/schedules/" + id),
  // alerts
  alertRules: (): Promise<Row[]> => request("/alerts/rules"),
  saveAlertRule: (input: Row): Promise<Row> => post("/alerts/rules", input),
  deleteAlertRule: (id: string) => del("/alerts/rules/" + id),
  backtestRule: (id: string): Promise<Row[]> => post("/alerts/rules/" + id + "/backtest", {}),
  incidents: (): Promise<Row[]> => request("/alerts/incidents"),
  incident: (id: string): Promise<Row> => request("/alerts/incidents/" + id),
  // notifications
  destinations: (): Promise<Row[]> => request("/notify/destinations"),
  saveDestination: (input: Row): Promise<Row> => post("/notify/destinations", input),
  previewDestination: (id: string): Promise<Row> => post("/notify/destinations/" + id + "/preview", {}),
  enableDestination: (id: string): Promise<Row> =>
    post("/notify/destinations/" + id + "/enable", { confirm: true }),
  disableDestination: (id: string): Promise<Row> => post("/notify/destinations/" + id + "/disable", {}),
  outbox: (): Promise<Row[]> => request("/notify/outbox"),
  notifications: (): Promise<Row[]> => request("/notifications"),
  markRead: (id: string) => post("/notifications/" + id + "/read", {}),
  // investigations
  investigations: (): Promise<Row[]> => request("/investigations"),
  createInvestigation: (input: Row): Promise<Row> => post("/investigations", input),
  investigation: (id: string): Promise<Row> => request("/investigations/" + id),
  previewInvestigation: (id: string, connectionId: string): Promise<Row> =>
    post("/investigations/" + id + "/preview", { connectionId }),
  jiraDraft: (id: string, input: Row = {}): Promise<Row> =>
    post("/investigations/" + id + "/jira-draft", input),
  resolveInvestigation: (id: string, resolution: string): Promise<Row> =>
    post("/investigations/" + id + "/resolve", { resolution }),
  // knowledge
  knowledgeSearch: (q: string, kinds = ""): Promise<Row[]> =>
    request("/knowledge/search?q=" + encodeURIComponent(q)
      + (kinds ? "&kinds=" + encodeURIComponent(kinds) : "")),
  knowledgeItem: (id: string): Promise<Row> => request("/knowledge/" + id),
  // connector builder
  connectorHealth: (): Promise<Row[]> => request("/connectors/health"),
  connectorVersions: (connectionId: string): Promise<Row[]> =>
    request("/connectors/" + connectionId + "/versions"),
  saveConnectorVersion: (connectionId: string, input: Row): Promise<Row> =>
    post("/connectors/" + connectionId + "/versions", input),
  validateVersion: (id: string): Promise<Row> => post("/connectors/versions/" + id + "/validate", {}),
  activateVersion: (id: string): Promise<Row> => post("/connectors/versions/" + id + "/activate", {}),
  codegen: (connectionId: string, input: Row): Promise<Row> =>
    post("/connectors/" + connectionId + "/codegen", input),
  validateGenerated: (connectionId: string, input: Row): Promise<Row> =>
    post("/connectors/" + connectionId + "/codegen/validate", input),
  proposePr: (connectionId: string, input: Row): Promise<Row> =>
    post("/connectors/" + connectionId + "/codegen/pr", input),
  connectorCatalog: (): Promise<Row[]> => request("/connectors/catalog"),
  applyCatalog: (id: string, input: Row = {}): Promise<Row> =>
    post("/connectors/catalog/" + id + "/apply", input),
  connectorDashboard: (service = ""): Promise<Row> =>
    request("/connectors/dashboard" + (service ? "?service=" + encodeURIComponent(service) : "")),
  correlation: (correlationId: string): Promise<Row> =>
    request("/connectors/correlation/" + encodeURIComponent(correlationId)),
  playbooks: (): Promise<Row[]> => request("/connectors/playbooks"),
  savePlaybook: (input: Row): Promise<Row> => post("/connectors/playbooks", input),
  runPlaybook: (investigationId: string, playbookId: string): Promise<Row> =>
    post("/connectors/investigations/" + investigationId + "/playbooks/" + playbookId + "/run", {}),
  costLimits: (): Promise<Row[]> => request("/connectors/cost-limits"),
  saveCostLimit: (input: Row): Promise<Row> => post("/connectors/cost-limits", input),
  deleteCostLimit: (id: string) => del("/connectors/cost-limits/" + id),
  // studio har / inference
  importHar: (file: File): Promise<Row> => {
    const form = new FormData();
    form.append("file", file);
    return request("/studio/har", { method: "POST", body: form });
  },
  inferCaptures: (captures: Row[], promptMarker: string): Promise<Row> =>
    post("/studio/har/infer", { captures, promptMarker }),
};
