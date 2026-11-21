import { useCallback, useEffect, useRef, useState } from "react";
import {
  Download, FlaskConical, LayoutList, Play, Plus, Search,
  Siren, SquareKanban, Table2, Workflow, X,
} from "lucide-react";
import { pretty, request, type Row } from "./api";
import { api, CHART_TYPES, CONDITION_TYPES, METRIC_CHOICES, PRESETS,
  type MetricRow, type Snapshot, type SplunkValidation } from "./monitoring-api";
import { Badge, Empty, Field, JsonView, Panel } from "./components";
import { ChartByType, MetricTable, WindowsView } from "./Charts";
import type { PageProps } from "./App";

const TABS = ["Reports", "Schedules", "Alerts", "Notifications", "Investigations", "Knowledge", "Ops"] as const;

export function Monitoring({ data }: PageProps) {
  const [tab, setTab] = useState<(typeof TABS)[number]>("Reports");
  const [unread, setUnread] = useState(0);
  const connections = (data.connections || []);
  const splunk = connections.filter(c => c.provider === "splunk");

  useEffect(() => {
    api.notifications().then(rows => setUnread(rows.filter((r: Row) => !r.readAt).length)).catch(() => {});
  }, []);

  return (
    <section aria-label="Monitoring">
      <div className="ai-provider-tabs" role="tablist" aria-label="Monitoring sections">
        {TABS.map(t => (
          <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>
            {t === "Notifications" && unread > 0 ? `${t} (${unread})` : t}
          </button>
        ))}
      </div>
      {tab === "Reports" && <ReportsTab splunk={splunk} />}
      {tab === "Schedules" && <SchedulesTab />}
      {tab === "Alerts" && <AlertsTab />}
      {tab === "Notifications" && <NotificationsTab onRead={() => setUnread(u => Math.max(0, u - 1))} />}
      {tab === "Investigations" && <InvestigationsTab connections={connections} />}
      {tab === "Knowledge" && <KnowledgeTab />}
      {tab === "Ops" && <OpsTab connections={connections} />}
    </section>
  );
}

function useAsync<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const [value, setValue] = useState<T | undefined>();
  const [error, setError] = useState("");
  const reload = useCallback(() => {
    fn().then(setValue).catch(e => setError(String(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(reload, [reload]);
  return { value, error, reload, setValue };
}

function ErrorNote({ error }: { error: string }) {
  return error ? <p role="alert" className="error">{error}</p> : null;
}

// ---------------------------------------------------------------- Reports

function ReportsTab({ splunk }: { splunk: Row[] }) {
  const reports = useAsync(() => api.reports());
  const [def, setDef] = useState<Row>({
    name: "", connectionIds: [], templateId: "", savedSearch: "",
    filters: {}, metrics: ["requests", "error_rate"], charts: ["TABLE"],
    policy: { preset: "LAST_1H", mode: "ROLLING", zone: "UTC", bucketSeconds: 300,
      lagSeconds: 120, completedBucketsOnly: true, weeks: 1 },
  });
  const [templates, setTemplates] = useState<Row[]>([]);
  const [savedSearches, setSavedSearches] = useState<Row[]>([]);
  const [preview, setPreview] = useState<Snapshot>();
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const [history, setHistory] = useState<Row[]>([]);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<Row>();

  useEffect(() => { api.splunkTemplates().then(setTemplates).catch(() => {}); }, []);
  useEffect(() => {
    const first = def.connectionIds?.[0];
    if (first) api.savedSearches(first).then(setSavedSearches).catch(() => setSavedSearches([]));
  }, [def.connectionIds?.join?.(",")]);

  function patchPolicy(key: string, value: unknown) {
    setDef(d => ({ ...d, policy: { ...d.policy, [key]: value } }));
  }
  function toggle(list: string[], value: string, apply: (next: string[]) => void) {
    apply(list.includes(value) ? list.filter(v => v !== value) : [...list, value]);
  }

  async function doPreview() {
    setBusy("preview"); setError("");
    try { setPreview(await api.previewReport(def)); }
    catch (e) { setError(String(e)); setPreview(undefined); }
    finally { setBusy(""); }
  }
  async function doSave() {
    setBusy("save"); setError("");
    try {
      const saved = await api.saveReport(def);
      setDef(d => ({ ...d, id: saved.id }));
      reports.reload();
    } catch (e) { setError(String(e)); }
    finally { setBusy(""); }
  }
  async function doRun(id: string) {
    setBusy("run" + id); setError("");
    try {
      await api.runReport(id);
      setHistory(await api.reportSnapshots(id));
    } catch (e) { setError(String(e)); }
    finally { setBusy(""); }
  }

  const metrics = (preview?.metrics || snapshot?.metrics || []) as MetricRow[];
  const shown = preview || snapshot;

  return (
    <div className="studio-grid">
      <div className="studio-main">
        <Panel title="Report designer" action={<Badge tone="blue">HALF-OPEN WINDOWS</Badge>}>
          <div className="toolbar">
            <Field label="Name"><input value={def.name || ""} aria-label="Report name"
              onChange={e => setDef({ ...def, name: e.target.value })} placeholder="Service health" /></Field>
            <Field label="Splunk connection(s)">
              <select multiple size={Math.min(3, Math.max(1, splunk.length))}
                value={def.connectionIds || []}
                aria-label="Splunk connections"
                onChange={e => setDef({ ...def,
                  connectionIds: [...e.target.selectedOptions].map(o => o.value) })}>
                {splunk.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="Template"><select value={def.templateId || ""} aria-label="Query template"
              onChange={e => setDef({ ...def, templateId: e.target.value })}>
              <option value="">None</option>
              {templates.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select></Field>
            <Field label="Saved search"><select value={def.savedSearch || ""} aria-label="Saved search"
              onChange={e => setDef({ ...def, savedSearch: e.target.value })}>
              <option value="">None</option>
              {savedSearches.map(s => <option key={s.name} value={s.name}>{s.name}</option>)}
            </select></Field>
          </div>
          <div className="toolbar">
            <Field label="Preset"><select value={def.policy?.preset || "LAST_1H"} aria-label="Comparison preset"
              onChange={e => patchPolicy("preset", e.target.value)}>
              {PRESETS.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
            </select></Field>
            <Field label="Mode"><select value={def.policy?.mode || "ROLLING"} aria-label="Window mode"
              onChange={e => patchPolicy("mode", e.target.value)}>
              <option value="ROLLING">Rolling</option><option value="CALENDAR">Calendar</option>
            </select></Field>
            <Field label="Timezone"><input value={def.policy?.zone || "UTC"} aria-label="Timezone"
              onChange={e => patchPolicy("zone", e.target.value)} placeholder="America/New_York" /></Field>
            <Field label="Bucket (s)"><input type="number" value={def.policy?.bucketSeconds ?? 300}
              aria-label="Bucket seconds"
              onChange={e => patchPolicy("bucketSeconds", Number(e.target.value))} /></Field>
            <Field label="Lag (s)"><input type="number" value={def.policy?.lagSeconds ?? 120}
              aria-label="Lag seconds"
              onChange={e => patchPolicy("lagSeconds", Number(e.target.value))} /></Field>
            {def.policy?.preset === "MULTI_WEEK" && (
              <Field label="Weeks"><input type="number" min={1} value={def.policy?.weeks ?? 1}
                aria-label="Baseline weeks"
                onChange={e => patchPolicy("weeks", Number(e.target.value))} /></Field>)}
            <Field label="Complete buckets">
              <input type="checkbox" checked={def.policy?.completedBucketsOnly !== false}
                aria-label="Completed buckets only"
                onChange={e => patchPolicy("completedBucketsOnly", e.target.checked)} />
            </Field>
          </div>
          <div className="toolbar">
            <Field label="Metrics">
              <span className="capability-chips">
                {METRIC_CHOICES.map(m => (
                  <button key={m} type="button" className="chip"
                    aria-pressed={(def.metrics || []).includes(m)}
                    onClick={() => toggle(def.metrics || [], m, next => setDef({ ...def, metrics: next }))}>
                    {m}
                  </button>
                ))}
              </span>
            </Field>
            <Field label="Charts">
              <span className="capability-chips">
                {CHART_TYPES.map(c => (
                  <button key={c} type="button" className="chip"
                    aria-pressed={(def.charts || []).includes(c)}
                    onClick={() => toggle(def.charts || [], c, next => setDef({ ...def, charts: next }))}>
                    {c}
                  </button>
                ))}
              </span>
            </Field>
          </div>
          <div className="toolbar">
            <button disabled={!!busy} onClick={() => void doPreview()}>
              {busy === "preview" ? "Evaluating…" : "Preview"}
            </button>
            <button className="primary" disabled={!!busy || !def.name || !(def.connectionIds || []).length}
              onClick={() => void doSave()}>
              {busy === "save" ? "Saving…" : "Save report"}
            </button>
          </div>
          <ErrorNote error={error || reports.error || ""} />
        </Panel>

        {preview && (
          <Panel title="Preview" action={<Badge tone={preview.completeness === "COMPLETE" ? "green" : "amber"}>{preview.completeness || "—"}</Badge>}>
            <WindowsView resolved={preview.resolvedWindows} />
            <MetricTable metrics={metrics} />
            {(def.charts || []).map((type: string) => <ChartByType key={type} type={type} snapshot={preview} />)}
          </Panel>
        )}
        {snapshot && <SnapshotView snapshot={snapshot} onClose={() => setSnapshot(undefined)} />}

        <Panel title="Saved reports">
          {(reports.value || []).length === 0 ? <Empty text="No reports yet — design and save one above." /> : (
            <table className="capture-table">
              <thead><tr><th>Name</th><th>Preset</th><th>Version</th><th></th></tr></thead>
              <tbody>
                {(reports.value || []).map(r => (
                  <tr key={r.id}>
                    <td>{r.name}</td>
                    <td><code>{r.policy?.preset}</code> {r.policy?.mode === "CALENDAR" ? "calendar" : "rolling"}</td>
                    <td>v{r.revision || r.version || 1}</td>
                    <td className="row-actions">
                      <button onClick={() => setEditing(r)}>Edit</button>
                      <button disabled={busy === "run" + r.id} onClick={async () => {
                        await doRun(r.id);
                      }}><Play size={13} /> Run now</button>
                      <button onClick={async () => setHistory(await api.reportSnapshots(r.id).catch(() => []))}>
                        <LayoutList size={13} /> Snapshots</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {editing && (
            <ReportEditor report={editing} onClose={() => setEditing(undefined)} onSaved={() => {
              setEditing(undefined); reports.reload();
            }} />
          )}
          {history.length > 0 && (
            <>
              <h4>Snapshot history</h4>
              <table className="capture-table">
                <thead><tr><th>Evaluated</th><th>Version</th><th>Completeness</th><th></th></tr></thead>
                <tbody>
                  {history.map(s => (
                    <tr key={s.id}>
                      <td>{s.evaluated_at || s.evaluatedAt || s.created_at}</td>
                      <td>v{s.report_version || s.reportVersion}</td>
                      <td><Badge tone={s.completeness === "COMPLETE" ? "green" : "amber"}>{s.completeness}</Badge></td>
                      <td className="row-actions">
                        <button onClick={async () => setSnapshot(await api.snapshot(s.id))}>Open</button>
                        <a href={"/api/reports/snapshots/" + s.id + ".csv"} download><Download size={13} /> CSV</a>
                        <a href={"/api/reports/snapshots/" + s.id + ".pdf"} download><Download size={13} /> PDF</a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </Panel>
      </div>
      <aside className="studio-timeline">
        <SplunkTemplates />
      </aside>
    </div>
  );
}

function SplunkTemplates() {
  const templates = useAsync(() => api.splunkTemplates());
  const [form, setForm] = useState<Row>({ name: "", spl: "", connectionId: "" });
  const [error, setError] = useState("");
  return (
    <Panel title="Query templates">
      <Field label="Name"><input value={form.name} aria-label="Template name"
        onChange={e => setForm({ ...form, name: e.target.value })} /></Field>
      <Field label="SPL (&#123;&#123;param&#125;&#125; placeholders)">
        <textarea rows={3} value={form.spl} aria-label="SPL template"
          onChange={e => setForm({ ...form, spl: e.target.value })}
          placeholder="search index={{index}} status>={{status}} | stats count" />
      </Field>
      <button onClick={async () => {
        setError("");
        try { await api.saveSplunkTemplate(form); templates.reload(); setForm({ name: "", spl: "" }); }
        catch (e) { setError(String(e)); }
      }}><Plus size={13} /> Save template</button>
      <ErrorNote error={error} />
      <ul className="action-list">
        {(templates.value || []).map(t => (
          <li key={t.id}>{t.name} <code>{t.spl}</code>
            <button className="icon-button" aria-label="Delete template"
              onClick={() => void api.deleteSplunkTemplate(t.id).then(templates.reload)}><X size={13} /></button>
          </li>
        ))}
      </ul>
    </Panel>
  );
}

function ReportEditor({ report, onClose, onSaved }: {
  report: Row; onClose: () => void; onSaved: () => void;
}) {
  const [text, setText] = useState(pretty(report));
  const [error, setError] = useState("");
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <section className="modal" role="dialog" aria-label="Edit report" onClick={e => e.stopPropagation()}>
        <header><h2>Edit report</h2><button onClick={onClose} aria-label="Close"><X /></button></header>
        <textarea className="code tall" value={text} aria-label="Report JSON" onChange={e => setText(e.target.value)} />
        {error && <p role="alert" className="error">{error}</p>}
        <footer>
          <button onClick={onClose}>Cancel</button>
          <button className="primary" onClick={async () => {
            try { await api.saveReport(JSON.parse(text)); onSaved(); }
            catch (e) { setError(String(e)); }
          }}>Save</button>
        </footer>
      </section>
    </div>
  );
}

export function SnapshotView({ snapshot, onClose }: { snapshot: Snapshot; onClose?: () => void }) {
  return (
    <Panel title={"Snapshot " + snapshot.id?.slice(0, 8)}
      action={<Badge tone={snapshot.completeness === "COMPLETE" ? "green" : "amber"}>
        {snapshot.completeness || "—"} · immutable v{snapshot.reportVersion}
      </Badge>}>
      <div className="studio-facts">
        <span><b>Evaluated</b> {snapshot.evaluatedAt}</span>
        <span><b>Planned</b> {snapshot.plannedAt}</span>
        {snapshot.freshness?.currentLatest && <span><b>Latest event</b> {String(snapshot.freshness.currentLatest)}</span>}
      </div>
      <WindowsView resolved={snapshot.resolvedWindows} />
      <MetricTable metrics={(snapshot.metrics || []) as MetricRow[]} />
      {(snapshot.errors || []).length > 0 && <JsonView value={snapshot.errors} />}
      {snapshot.aiSummary && (
        <div className="ai-review-detail">
          <Badge tone="purple">AI-GENERATED — CITES [M#]</Badge>
          <p>{snapshot.aiSummary}</p>
        </div>
      )}
      {onClose && <button onClick={onClose}>Close</button>}
    </Panel>
  );
}

// ---------------------------------------------------------------- Schedules

function SchedulesTab() {
  const schedules = useAsync(() => api.schedules());
  const reports = useAsync(() => api.reports());
  const [form, setForm] = useState<Row>({ reportId: "", kind: "DAILY", expr: "09:00", zone: "UTC",
    missedPolicy: "RUN_ONCE", maxCatchup: 5, retryLimit: 3, backoffBaseS: 30, deadlineS: 600 });
  const [upcoming, setUpcoming] = useState<string[]>([]);
  const [runs, setRuns] = useState<Row[]>([]);
  const [error, setError] = useState("");

  useEffect(() => {
    api.schedulePreview(form.kind, form.expr, form.zone).then(setUpcoming).catch(() => setUpcoming([]));
  }, [form.kind, form.expr, form.zone]);

  return (
    <div className="studio-grid">
      <div className="studio-main">
        <Panel title="New schedule">
          <div className="toolbar">
            <Field label="Report"><select value={form.reportId} aria-label="Report"
              onChange={e => setForm({ ...form, reportId: e.target.value })}>
              <option value="">Choose a report</option>
              {(reports.value || []).map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
            </select></Field>
            <Field label="Kind"><select value={form.kind} aria-label="Schedule kind"
              onChange={e => setForm({ ...form, kind: e.target.value })}>
              {["INTERVAL", "DAILY", "WEEKLY", "CRON"].map(k => <option key={k}>{k}</option>)}
            </select></Field>
            <Field label={form.kind === "CRON" ? "Cron expression" : form.kind === "INTERVAL" ? "Interval (e.g. PT30M)" : "Time (HH:mm)"}>
              <input value={form.expr} aria-label="Schedule expression"
                onChange={e => setForm({ ...form, expr: e.target.value })} />
            </Field>
            <Field label="Timezone"><input value={form.zone} aria-label="Schedule timezone"
              onChange={e => setForm({ ...form, zone: e.target.value })} /></Field>
            <Field label="Missed policy"><select value={form.missedPolicy} aria-label="Missed run policy"
              onChange={e => setForm({ ...form, missedPolicy: e.target.value })}>
              {["SKIP", "RUN_ONCE", "RUN_ALL_CAPPED"].map(v => <option key={v}>{v}</option>)}
            </select></Field>
            <Field label="Retries"><input type="number" value={form.retryLimit} aria-label="Retry limit"
              onChange={e => setForm({ ...form, retryLimit: Number(e.target.value) })} /></Field>
            <Field label="Backoff (s)"><input type="number" value={form.backoffBaseS} aria-label="Backoff base seconds"
              onChange={e => setForm({ ...form, backoffBaseS: Number(e.target.value) })} /></Field>
            <Field label="Deadline (s)"><input type="number" value={form.deadlineS} aria-label="Deadline seconds"
              onChange={e => setForm({ ...form, deadlineS: Number(e.target.value) })} /></Field>
          </div>
          {upcoming.length > 0 && (
            <p className="muted">Upcoming: {upcoming.slice(0, 5).join(" · ")}</p>
          )}
          <button className="primary" disabled={!form.reportId} onClick={async () => {
            setError("");
            try { await api.saveSchedule(form); schedules.reload(); }
            catch (e) { setError(String(e)); }
          }}><Plus size={14} /> Save schedule</button>
          <ErrorNote error={error} />
        </Panel>

        <Panel title="Schedules">
          {(schedules.value || []).length === 0 ? <Empty text="No schedules yet." /> : (
            <table className="capture-table">
              <thead><tr><th>Report</th><th>Kind</th><th>Expr</th><th>Status</th><th>Next run</th><th>Last success</th><th>Last error</th><th></th></tr></thead>
              <tbody>
                {(schedules.value || []).map(s => (
                  <tr key={s.id}>
                    <td>{(reports.value || []).find(r => r.id === s.report_id || r.id === s.reportId)?.name || s.report_id || s.reportId}</td>
                    <td>{s.kind}</td>
                    <td><code>{s.expr}</code> <small>{s.zone}</small></td>
                    <td><Badge tone={s.status === "ACTIVE" ? "green" : "amber"}>{s.status}</Badge></td>
                    <td>{s.next_run_at || s.nextRunAt}</td>
                    <td>{s.last_success_at || s.lastSuccessAt || "—"}</td>
                    <td className="error">{s.last_error || s.lastError || ""}</td>
                    <td className="row-actions">
                      {s.status === "ACTIVE"
                        ? <button onClick={() => api.pauseSchedule(s.id).then(schedules.reload)}>Pause</button>
                        : <button onClick={() => api.resumeSchedule(s.id).then(schedules.reload)}>Resume</button>}
                      <button onClick={() => api.runNow(s.id).then(schedules.reload)}><Play size={13} /> Run now</button>
                      <button onClick={async () => setRuns(await api.scheduleRuns(s.id))}><Table2 size={13} /> History</button>
                      <button onClick={() => { if (window.confirm("Delete schedule?")) api.deleteSchedule(s.id).then(schedules.reload); }}>Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {runs.length > 0 && (
            <>
              <h4>Run history</h4>
              <table className="capture-table">
                <thead><tr><th>Planned</th><th>Attempt</th><th>Status</th><th>Error</th><th></th></tr></thead>
                <tbody>
                  {runs.map(r => (
                    <tr key={r.id}>
                      <td>{r.planned_at || r.plannedAt}</td>
                      <td>{r.attempt}</td>
                      <td><Badge tone={r.status === "SUCCEEDED" ? "green" : r.status === "DEAD" || r.status === "FAILED" ? "red" : "amber"}>{r.status}</Badge></td>
                      <td>{r.error}</td>
                      <td>{["QUEUED", "LEASED"].includes(r.status)
                        && <button onClick={() => api.cancelRun(r.id).then(() => api.scheduleRuns(r.schedule_id || r.scheduleId).then(setRuns))}>Cancel</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </Panel>
      </div>
      <aside className="studio-timeline">
        <Panel title="How scheduling works">
          <p className="muted">In-process durable scheduler on SQLite leases. Runs only while the app is up;
            missed runs follow the policy on restart. Execution keys dedupe across instances.</p>
        </Panel>
      </aside>
    </div>
  );
}

// ---------------------------------------------------------------- Alerts

function AlertsTab() {
  const rules = useAsync(() => api.alertRules());
  const incidents = useAsync(() => api.incidents());
  const reports = useAsync(() => api.reports());
  const destinations = useAsync(() => api.destinations());
  const [rule, setRule] = useState<Row>({ name: "", reportId: "", metric: "error_rate", groupBy: "none",
    conditions: [{ type: "ABSOLUTE", metric: "error_rate", op: ">", value: 0.05 }],
    minSamples: 1, consecutive: 1, severity: "HIGH", cooldownMinutes: 60,
    notifyRecovery: true, destinations: [] });
  const [backtest, setBacktest] = useState<{ id: string; rows: Row[] }>();
  const [error, setError] = useState("");
  const [investigateId, setInvestigateId] = useState("");

  function patchCond(index: number, key: string, value: unknown) {
    const conditions = (rule.conditions || []).map((c: Row, i: number) =>
      i === index ? { ...c, [key]: value } : c);
    setRule({ ...rule, conditions });
  }

  return (
    <div className="studio-grid">
      <div className="studio-main">
        <Panel title="Rule builder">
          <div className="toolbar">
            <Field label="Name"><input value={rule.name} aria-label="Rule name"
              onChange={e => setRule({ ...rule, name: e.target.value })} /></Field>
            <Field label="Report"><select value={rule.reportId} aria-label="Report for rule"
              onChange={e => setRule({ ...rule, reportId: e.target.value })}>
              <option value="">Choose a report</option>
              {(reports.value || []).map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
            </select></Field>
            <Field label="Metric"><input value={rule.metric} aria-label="Metric"
              onChange={e => setRule({ ...rule, metric: e.target.value })} /></Field>
            <Field label="Group by"><select value={rule.groupBy} aria-label="Group by"
              onChange={e => setRule({ ...rule, groupBy: e.target.value })}>
              {["none", "service", "environment"].map(v => <option key={v}>{v}</option>)}
            </select></Field>
            <Field label="Severity"><select value={rule.severity} aria-label="Severity"
              onChange={e => setRule({ ...rule, severity: e.target.value })}>
              {["LOW", "MEDIUM", "HIGH", "CRITICAL"].map(v => <option key={v}>{v}</option>)}
            </select></Field>
            <Field label="Consecutive"><input type="number" min={1} value={rule.consecutive}
              aria-label="Consecutive breaches"
              onChange={e => setRule({ ...rule, consecutive: Number(e.target.value) })} /></Field>
            <Field label="Min samples"><input type="number" min={1} value={rule.minSamples}
              aria-label="Minimum samples"
              onChange={e => setRule({ ...rule, minSamples: Number(e.target.value) })} /></Field>
            <Field label="Cooldown (min)"><input type="number" value={rule.cooldownMinutes}
              aria-label="Cooldown minutes"
              onChange={e => setRule({ ...rule, cooldownMinutes: Number(e.target.value) })} /></Field>
            <Field label="Recovery notify"><input type="checkbox" checked={!!rule.notifyRecovery}
              aria-label="Notify on recovery"
              onChange={e => setRule({ ...rule, notifyRecovery: e.target.checked })} /></Field>
          </div>
          <h4>Conditions (all must match)</h4>
          {(rule.conditions || []).map((c: Row, i: number) => (
            <div className="toolbar" key={i}>
              <Field label="Type"><select value={c.type} aria-label={"Condition type " + i}
                onChange={e => patchCond(i, "type", e.target.value)}>
                {CONDITION_TYPES.map(t => <option key={t}>{t}</option>)}
              </select></Field>
              <Field label="Metric"><input value={c.metric || ""} aria-label={"Condition metric " + i}
                onChange={e => patchCond(i, "metric", e.target.value)} /></Field>
              {["ABSOLUTE", "RATE_CHANGE", "BASELINE_DEVIATION"].includes(c.type) && <>
                <Field label="Op"><select value={c.op || ">"} aria-label={"Operator " + i}
                  onChange={e => patchCond(i, "op", e.target.value)}>
                  {[">", ">=", "<", "<=", "==", "!="].map(o => <option key={o}>{o}</option>)}
                </select></Field>
                <Field label="Value"><input type="number" step="any" value={c.value ?? ""}
                  aria-label={"Condition value " + i}
                  onChange={e => patchCond(i, "value", Number(e.target.value))} /></Field>
              </>}
              {c.type === "MISSING_TRAFFIC" && (
                <Field label="Min expected"><input type="number" value={c.minExpected ?? ""}
                  aria-label={"Min expected " + i}
                  onChange={e => patchCond(i, "minExpected", Number(e.target.value))} /></Field>)}
              <button className="icon-button" aria-label="Remove condition" onClick={() =>
                setRule({ ...rule, conditions: (rule.conditions || []).filter((_: Row, j: number) => j !== i) })}>
                <X size={14} /></button>
            </div>
          ))}
          <div className="toolbar">
            <button onClick={() => setRule({ ...rule,
              conditions: [...(rule.conditions || []), { type: "ABSOLUTE", metric: "requests", op: ">=", value: 200 }] })}>
              <Plus size={13} /> Add condition</button>
            <Field label="Destinations">
              <select multiple size={Math.min(3, Math.max(1, (destinations.value || []).length))}
                value={rule.destinations || []} aria-label="Alert destinations"
                onChange={e => setRule({ ...rule,
                  destinations: [...e.target.selectedOptions].map(o => o.value) })}>
                {(destinations.value || []).map(d =>
                  <option key={d.id} value={d.id}>{d.name || d.type}{d.enabled ? "" : " (disabled)"}</option>)}
              </select>
            </Field>
            <button className="primary" disabled={!rule.name || !rule.reportId} onClick={async () => {
              setError("");
              try { await api.saveAlertRule(rule); rules.reload(); }
              catch (e) { setError(String(e)); }
            }}>Save rule</button>
          </div>
          <ErrorNote error={error} />
        </Panel>

        <Panel title="Rules">
          {(rules.value || []).length === 0 ? <Empty text="No alert rules yet." /> : (
            <table className="capture-table">
              <thead><tr><th>Name</th><th>Metric</th><th>Conditions</th><th>Severity</th><th></th></tr></thead>
              <tbody>
                {(rules.value || []).map(r => (
                  <tr key={r.id}>
                    <td>{r.name}</td>
                    <td>{r.metric}</td>
                    <td><code>{(r.conditions || []).map((c: Row) => c.type + (c.op ? c.op + c.value : "")).join(" & ")}</code></td>
                    <td><Badge tone={r.severity === "CRITICAL" || r.severity === "HIGH" ? "red" : "amber"}>{r.severity}</Badge></td>
                    <td className="row-actions">
                      <button onClick={async () => setBacktest({ id: r.id, rows: await api.backtestRule(r.id).catch(() => []) })}>
                        <FlaskConical size={13} /> Backtest</button>
                      <button onClick={() => { if (window.confirm("Delete rule?")) api.deleteAlertRule(r.id).then(rules.reload); }}>Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {backtest && (
            <>
              <h4>Backtest — evaluates stored snapshots, sends nothing</h4>
              {backtest.rows.length === 0 ? <p className="muted">No matching evaluations.</p> : (
                <table className="capture-table">
                  <thead><tr><th>Snapshot</th><th>Entity</th><th>Result</th><th>Detail</th></tr></thead>
                  <tbody>
                    {backtest.rows.map((r, i) => (
                      <tr key={i}>
                        <td>{r.snapshotId || r.evaluatedAt}</td>
                        <td>{r.entityKey}</td>
                        <td><Badge tone={r.breach || r.fires ? "red" : "green"}>{r.breach || r.fires ? "BREACH" : "OK"}</Badge></td>
                        <td>{r.reason || r.value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </Panel>

        <Panel title="Incidents">
          {(incidents.value || []).length === 0 ? <Empty text="No incidents — rules evaluate after each snapshot." /> : (
            <table className="capture-table">
              <thead><tr><th>Entity</th><th>Kind</th><th>State</th><th>Episode</th><th>Opened</th><th></th></tr></thead>
              <tbody>
                {(incidents.value || []).map(i => (
                  <tr key={i.id}>
                    <td>{i.entity_key || i.entityKey}</td>
                    <td><Badge tone={i.kind === "MISSING_DATA" ? "amber" : "red"}>{i.kind}</Badge></td>
                    <td><Badge tone={i.state === "FIRING" ? "red" : i.state === "RESOLVED" ? "green" : "amber"}>{i.state}</Badge></td>
                    <td>{i.episode}</td>
                    <td>{i.opened_at || i.openedAt}</td>
                    <td className="row-actions">
                      <button onClick={async () => {
                        const inv = await api.createInvestigation({ incidentId: i.id });
                        setInvestigateId(inv.id);
                      }}><Siren size={13} /> Investigate in AI</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {investigateId && <p className="muted">Investigation {investigateId.slice(0, 8)} created — open the Investigations tab.</p>}
        </Panel>
      </div>
      <aside className="studio-timeline">
        <Panel title="Dedup model">
          <p className="muted">One notification per episode (rule · entity · episode). Cooldown applies after
            resolve; consecutive breaches required before firing; maintenance windows suppress.</p>
        </Panel>
      </aside>
    </div>
  );
}

// ---------------------------------------------------------------- Notifications

function NotificationsTab({ onRead }: { onRead?: () => void }) {
  const destinations = useAsync(() => api.destinations());
  const outbox = useAsync(() => api.outbox());
  const inbox = useAsync(() => api.notifications());
  const [dest, setDest] = useState<Row>({ type: "WEBHOOK", name: "", url: "", recipients: "" });
  const [previews, setPreviews] = useState<Record<string, Row>>({});
  const [error, setError] = useState("");

  return (
    <div className="studio-grid">
      <div className="studio-main">
        <Panel title="Destinations">
          <div className="toolbar">
            <Field label="Type"><select value={dest.type} aria-label="Destination type"
              onChange={e => setDest({ ...dest, type: e.target.value })}>
              {["IN_APP", "EMAIL", "SLACK", "TEAMS", "WEBHOOK"].map(t => <option key={t}>{t}</option>)}
            </select></Field>
            <Field label="Name"><input value={dest.name} aria-label="Destination name"
              onChange={e => setDest({ ...dest, name: e.target.value })} /></Field>
            {["WEBHOOK", "SLACK", "TEAMS"].includes(dest.type) && (
              <Field label="Webhook URL"><input value={dest.url} type="password" aria-label="Webhook URL"
                placeholder="Stored in the vault, never returned"
                onChange={e => setDest({ ...dest, url: e.target.value })} /></Field>)}
            {dest.type === "EMAIL" && (
              <Field label="Recipients"><input value={dest.recipients} aria-label="Email recipients"
                placeholder="comma-separated emails"
                onChange={e => setDest({ ...dest, recipients: e.target.value })} /></Field>)}
            <button className="primary" disabled={!dest.name} onClick={async () => {
              setError("");
              try {
                await api.saveDestination({ ...dest,
                  recipients: String(dest.recipients || "").split(",").map(s => s.trim()).filter(Boolean) });
                destinations.reload(); setDest({ type: dest.type, name: "", url: "", recipients: "" });
              } catch (e) { setError(String(e)); }
            }}><Plus size={13} /> Add destination</button>
          </div>
          <ErrorNote error={error} />
          <table className="capture-table">
            <thead><tr><th>Name</th><th>Type</th><th>Enabled</th><th>Verified</th><th></th></tr></thead>
            <tbody>
              {(destinations.value || []).map(d => (
                <tr key={d.id}>
                  <td>{d.name || d.id}</td>
                  <td>{d.type}{d.hasUrl ? " 🔒" : ""}</td>
                  <td><Badge tone={d.enabled ? "green" : "amber"}>{d.enabled ? "enabled" : "disabled"}</Badge></td>
                  <td>{d.verifiedAt || "—"}</td>
                  <td className="row-actions">
                    <button onClick={async () =>
                      setPreviews({ ...previews, [d.id]: await api.previewDestination(d.id).catch(e => ({ error: String(e) })) })}>
                      Preview</button>
                    {!d.enabled
                      ? <button disabled={!previews[d.id]} title="Preview first, then enable"
                          onClick={() => api.enableDestination(d.id).then(destinations.reload)}>Enable</button>
                      : <button onClick={() => api.disableDestination(d.id).then(destinations.reload)}>Disable</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {Object.entries(previews).map(([id, p]) => (
            <div key={id} className="ai-review-detail">
              <strong>Payload preview</strong>
              {p.error ? <p className="error">{p.error}</p> : <JsonView value={p} />}
              <small className="muted">Enabling approves this destination's host for outbound delivery.</small>
            </div>
          ))}
        </Panel>

        <Panel title="Outbox delivery history">
          {(outbox.value || []).length === 0 ? <Empty text="Nothing sent yet." /> : (
            <table className="capture-table">
              <thead><tr><th>When</th><th>Type</th><th>Status</th><th>Attempts</th><th>Last error</th></tr></thead>
              <tbody>
                {(outbox.value || []).map((o, i) => (
                  <tr key={o.id || i}>
                    <td>{o.created_at || o.createdAt}</td>
                    <td>{o.destination_type || o.type || o.destination_id}</td>
                    <td><Badge tone={o.status === "SENT" ? "green" : o.status === "DEAD" ? "red" : "amber"}>{o.status}</Badge></td>
                    <td>{o.attempts}</td>
                    <td>{o.last_error || o.lastError || ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
      </div>
      <aside className="studio-timeline">
        <Panel title="In-app notifications">
          {(inbox.value || []).length === 0 ? <p className="muted">No notifications.</p> : (
            <ul className="action-list">
              {(inbox.value || []).map((n, i) => (
                <li key={n.id || i} className={n.readAt || n.read_at ? "" : "unread"}>
                  <b>{n.title}</b> <span>{n.body}</span>
                  <small className="muted">{n.created_at || n.createdAt}</small>
                  {!(n.readAt || n.read_at) && (
                    <button onClick={() => { api.markRead(String(n.id)).then(() => { inbox.reload(); onRead?.(); }); }}>Mark read</button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </aside>
    </div>
  );
}

// ---------------------------------------------------------------- Investigations

function InvestigationsTab({ connections }: { connections: Row[] }) {
  const list = useAsync(() => api.investigations());
  const [active, setActive] = useState<Row>();
  const [providers, setProviders] = useState<Row[]>([]);
  const [playbooks, setPlaybooks] = useState<Row[]>([]);

  useEffect(() => {
    request("/chat/providers").then(setProviders).catch(() => {});
    api.playbooks().then(setPlaybooks).catch(() => {});
  }, []);

  return (
    <div className="studio-grid">
      <div className="studio-main">
        <Panel title="Investigations"
          action={<button onClick={async () => setActive(await api.createInvestigation({}))}><Plus size={13} /> New</button>}>
          {(list.value || []).length === 0 ? <Empty text="No investigations — open one from an incident or snapshot." /> : (
            <table className="capture-table">
              <thead><tr><th>Service</th><th>Evidence</th><th>Status</th><th>Created</th><th></th></tr></thead>
              <tbody>
                {(list.value || []).map(i => (
                  <tr key={i.id}>
                    <td>{i.service || "—"}</td>
                    <td>{(i.evidence || []).length} items · {i.evidenceBytes}B</td>
                    <td><Badge tone={i.status === "RESOLVED" ? "green" : "amber"}>{i.status}</Badge></td>
                    <td>{i.createdAt}</td>
                    <td><button onClick={async () => setActive(await api.investigation(i.id))}>Open</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
        {active && (
          <InvestigationView investigation={active} providers={providers}
            playbooks={playbooks} onReload={async () => {
              const fresh = await api.investigation(active.id);
              setActive(fresh); list.reload();
            }} onClose={() => setActive(undefined)} />
        )}
      </div>
      <aside className="studio-timeline">
        <Panel title="Evidence protocol">
          <p className="muted">Evidence is bounded and redacted. The AI must answer in
            Observations / Possible causes / Verification steps and cite [E#];
            uncited claims are flagged after send.</p>
        </Panel>
      </aside>
    </div>
  );
}

function InvestigationView({ investigation, providers, playbooks, onReload, onClose }: {
  investigation: Row; providers: Row[]; playbooks: Row[];
  onReload: () => Promise<void>; onClose: () => void;
}) {
  const inv = investigation;
  const [connectionId, setConnectionId] = useState(providers[0]?.connectionId || "");
  const [preview, setPreview] = useState<Row>();
  const [streamText, setStreamText] = useState("");
  const [streamDone, setStreamDone] = useState(false);
  const [jiraProposal, setJiraProposal] = useState<Row>();
  const [resolution, setResolution] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const abort = useRef<AbortController | undefined>(undefined);
  const evidence: Row[] = inv.evidence || [];
  const check = inv.citationCheck;

  async function doSend() {
    setError(""); setStreamText(""); setStreamDone(false);
    const controller = new AbortController();
    abort.current = controller;
    try {
      const { streamSse } = await import("./api");
      await streamSse("/investigations/" + inv.id + "/send", {
        connectionId, confirmationId: preview?.confirmationId,
      }, controller.signal, event => {
        if (event.type === "delta") setStreamText(t => t + event.data.text);
        if (event.type === "error") setError(event.data.message);
      });
      setStreamDone(true);
      await onReload();
    } catch (e) { setError(String(e)); }
  }

  const cited = (text: string) => text.split(/(\[E\d+\])/g).map((part, i) =>
    /^\[E\d+\]$/.test(part)
      ? <button key={i} className="cite" title={evidence.find(e => e.id === part.slice(1, -1))?.summary || part}>{part}</button>
      : <span key={i}>{part}</span>);

  return (
    <Panel title={"Investigation " + inv.id.slice(0, 8)}
      action={<Badge tone={inv.status === "RESOLVED" ? "green" : "amber"}>{inv.status || "OPEN"}</Badge>}>
      <h4>Evidence ({evidence.length})</h4>
      <table className="capture-table">
        <thead><tr><th>Id</th><th>Type</th><th>Summary</th></tr></thead>
        <tbody>
          {evidence.map(e => (
            <tr key={e.id}>
              <td><Badge tone="purple">{e.id}</Badge></td>
              <td>{e.type}{e.stale ? " ⚠stale" : ""}</td>
              <td>{e.summary}<details><summary>data</summary><JsonView value={e.data} /></details></td>
            </tr>
          ))}
        </tbody>
      </table>

      {playbooks.length > 0 && (
        <div className="toolbar">
          <Field label="Run playbook"><select id="pb" aria-label="Run playbook" defaultValue="">
            <option value="">Choose…</option>
            {playbooks.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select></Field>
          <button disabled={busy === "playbook"} onClick={async () => {
            const el = document.getElementById("pb") as HTMLSelectElement;
            if (!el?.value) return;
            setBusy("playbook");
            try { await api.runPlaybook(inv.id, el.value); await onReload(); }
            catch (e) { setError(String(e)); }
            finally { setBusy(""); }
          }}><Workflow size={13} /> Run</button>
        </div>
      )}

      <h4>AI analysis</h4>
      <div className="toolbar">
        <Field label="AI provider"><select value={connectionId} aria-label="Investigation provider"
          onChange={e => setConnectionId(e.target.value)}>
          {providers.map(p => <option key={p.connectionId} value={p.connectionId}>{p.name} · {p.host}</option>)}
        </select></Field>
        <button disabled={!!busy || !connectionId} onClick={async () => {
          setBusy("preview"); setError("");
          try { setPreview(await api.previewInvestigation(inv.id, connectionId)); }
          catch (e) { setError(String(e)); }
          finally { setBusy(""); }
        }}>Preview context</button>
      </div>
      {preview && (
        <div className="ai-review-detail chat-confirm" role="dialog" aria-label="Confirm investigation send">
          <strong>Send {preview.evidenceCount} evidence items to {preview.destination || preview.provider}?</strong>
          <pre className="code output">{preview.prompt}</pre>
          <div className="toolbar">
            <button className="primary" onClick={() => void doSend()}>Confirm and send</button>
            <button onClick={() => setPreview(undefined)}>Cancel</button>
          </div>
        </div>
      )}
      {(streamText || streamDone) && (
        <div className="ai-review-detail">
          <strong>Analysis</strong>
          <p>{cited(streamText)}</p>
        </div>
      )}
      {check && (
        <div className={"notice " + (check.ok ? "" : "error")}>
          {check.ok
            ? "Citation check passed — every claim cites existing evidence."
            : <>
                {(check.unknownCitations || []).length > 0 &&
                  <p>Unknown citations: {check.unknownCitations.join(", ")}</p>}
                {(check.uncitedObservationSentences || []).length > 0 &&
                  <p>Uncited observation sentences: {check.uncitedObservationSentences.join(" · ")}</p>}
              </>}
        </div>
      )}
      <ErrorNote error={error} />

      <div className="toolbar">
        <button disabled={!!busy} onClick={async () => {
          setBusy("jira"); setError("");
          try { setJiraProposal(await api.jiraDraft(inv.id, {})); }
          catch (e) { setError(String(e)); }
          finally { setBusy(""); }
        }}><SquareKanban size={14} /> Draft Jira incident</button>
        <Field label="Resolution"><input value={resolution} aria-label="Resolution"
          onChange={e => setResolution(e.target.value)} placeholder="What fixed it — saved to knowledge" /></Field>
        <button disabled={!resolution.trim()} onClick={async () => {
          try { await api.resolveInvestigation(inv.id, resolution); await onReload(); }
          catch (e) { setError(String(e)); }
        }}>Mark resolved</button>
        <button onClick={onClose}>Close</button>
      </div>
      {jiraProposal && (
        <div className="ai-review-detail">
          <strong>Jira draft — pending approval</strong>
          <p className="muted">Proposal {jiraProposal.id} ({jiraProposal.status}) created through the reviewed
            write workflow — approve it under the integrations/events approvals before it executes.</p>
          <JsonView value={jiraProposal.action?.body?.fields || jiraProposal.action} />
        </div>
      )}
    </Panel>
  );
}

// ---------------------------------------------------------------- Knowledge

export function KnowledgeTab({ initialQuery = "", onOpenConversation }: {
  initialQuery?: string; onOpenConversation?: (id: string) => void;
}) {
  const [q, setQ] = useState(initialQuery);
  const [kinds, setKinds] = useState("");
  const [results, setResults] = useState<Row[]>();
  const [error, setError] = useState("");

  useEffect(() => { if (initialQuery) void search(); }, []);
  async function search() {
    setError("");
    try { setResults(await api.knowledgeSearch(q, kinds)); }
    catch (e) { setError(String(e)); }
  }

  return (
    <Panel title="Knowledge search" action={<Badge tone="blue">FTS5</Badge>}>
      <div className="toolbar">
        <Field label="Search"><input value={q} aria-label="Knowledge query"
          onChange={e => setQ(e.target.value)}
          onKeyDown={e => { if (e.key === "Enter") void search(); }}
          placeholder="error signature, service, resolution, chat history…" /></Field>
        <Field label="Kinds"><select value={kinds} aria-label="Knowledge kinds"
          onChange={e => setKinds(e.target.value)}>
          <option value="">All</option>
          {["REPORT_SUMMARY", "INCIDENT_RESOLUTION", "INVESTIGATION", "CHAT_MESSAGE"].map(k =>
            <option key={k} value={k}>{k.replace(/_/g, " ")}</option>)}
        </select></Field>
        <button className="primary" onClick={() => void search()}><Search size={14} /> Search</button>
      </div>
      <ErrorNote error={error} />
      {results && (
        results.length === 0 ? <p className="muted">No knowledge matched.</p> : (
          <table className="capture-table">
            <thead><tr><th>Kind</th><th>Title</th><th>Sources</th><th>Freshness</th></tr></thead>
            <tbody>
              {results.map((r, i) => {
                const item = r.item || r;
                const chatRef = (item.sourceRefs || item.source_refs || []).find?.((s: Row) => s.type === "CHAT" || s.type === "CHAT_MESSAGE" || s.type === "CONVERSATION");
                return (
                  <tr key={item.id || i}>
                    <td><Badge tone={item.kind === "CHAT_MESSAGE" ? "blue" : "purple"}>{String(item.kind || "").replace(/_/g, " ")}</Badge></td>
                    <td>
                      {chatRef && onOpenConversation
                        ? <button className="linkish" onClick={() => onOpenConversation(String(chatRef.id))}>{item.title}</button>
                        : item.title}
                      {item.body && <div className="muted">{String(item.body).slice(0, 160)}</div>}
                    </td>
                    <td><code>{(item.sourceRefs || item.source_refs || []).map?.((s: Row) => s.type + ":" + String(s.id || "").slice(0, 8)).join(", ")}</code></td>
                    <td>{r.stale || item.stale
                      ? <Badge tone="amber">stale{r.reason ? " · " + r.reason : ""}</Badge>
                      : <Badge tone="green">fresh</Badge>}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )
      )}
    </Panel>
  );
}

// ---------------------------------------------------------------- Ops

function OpsTab({ connections }: { connections: Row[] }) {
  const health = useAsync(() => api.connectorHealth());
  const catalog = useAsync(() => api.connectorCatalog());
  const limits = useAsync(() => api.costLimits());
  const playbooks = useAsync(() => api.playbooks());
  const [pb, setPb] = useState<Row>({ name: "", stepsText: "" });
  const [service, setService] = useState("");
  const [dash, setDash] = useState<Row>();
  const [corrId, setCorrId] = useState("");
  const [corr, setCorr] = useState<Row>();
  const [limit, setLimit] = useState<Row>({ scope: "user", targetId: "",
    limits: { maxWindowHours: "", maxConcurrentJobs: "", maxResults: "", retentionDays: "", aiContextBytes: "" } });
  const [error, setError] = useState("");

  return (
    <div className="studio-grid">
      <div className="studio-main">
        <Panel title="Service dashboard">
          <div className="toolbar">
            <Field label="Service"><input value={service} aria-label="Service name"
              onChange={e => setService(e.target.value)} placeholder="checkout" /></Field>
            <button onClick={() => api.connectorDashboard(service).then(setDash).catch(e => setError(String(e)))}>Load</button>
          </div>
          {dash && <JsonView value={dash} />}
        </Panel>

        <Panel title="Correlation ID search">
          <div className="toolbar">
            <Field label="Correlation id"><input value={corrId} aria-label="Correlation id"
              onChange={e => setCorrId(e.target.value)} /></Field>
            <button onClick={() => api.correlation(corrId).then(setCorr).catch(e => setError(String(e)))}>
              <Search size={13} /> Search connectors</button>
          </div>
          {corr && <JsonView value={corr} />}
        </Panel>

        <Panel title="New playbook" action={<Badge tone="blue">ORDERED EVIDENCE STEPS</Badge>}>
          <div className="toolbar">
            <Field label="Name"><input value={pb.name} aria-label="Playbook name"
              onChange={e => setPb({ ...pb, name: e.target.value })} placeholder="Triage p95 latency" /></Field>
          </div>
          <Field label="Steps (JSON — types: RUN_TEMPLATE, FETCH_EVENTS, JIRA_JQL, GITHUB_COMMITS)">
            <textarea className="code" rows={5} value={pb.stepsText} aria-label="Playbook steps"
              onChange={e => setPb({ ...pb, stepsText: e.target.value })}
              placeholder={'[{"type":"RUN_TEMPLATE","templateId":"…","connectionId":"…"},'
                + '\n {"type":"FETCH_EVENTS","connectionId":"…","correlationField":"correlationId"},'
                + '\n {"type":"JIRA_JQL","connectionId":"…","jql":"project = OPS"}]'} />
          </Field>
          <button className="primary" disabled={!pb.name} onClick={async () => {
            setError("");
            try {
              const steps = JSON.parse(pb.stepsText || "[]");
              await api.savePlaybook({ name: pb.name, steps });
              setPb({ name: "", stepsText: "" });
              playbooks.reload();
            } catch (e) { setError(String(e)); }
          }}>Save playbook</button>
          {(playbooks.value || []).map(p => (
            <div key={p.id} className="ai-review-detail">
              <b>{p.name}</b> <small className="muted">{(p.steps || []).length} steps</small>
              <JsonView value={p.steps} />
            </div>
          ))}
        </Panel>

        <Panel title="Cost limits (server-enforced)">
          <div className="toolbar">
            <Field label="Scope"><select value={limit.scope} aria-label="Limit scope"
              onChange={e => setLimit({ ...limit, scope: e.target.value })}>
              <option value="user">This user</option><option value="connection">Connection</option>
            </select></Field>
            {limit.scope === "connection" && (
              <Field label="Connection"><select value={limit.targetId} aria-label="Limit connection"
                onChange={e => setLimit({ ...limit, targetId: e.target.value })}>
                <option value="">Choose…</option>
                {connections.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select></Field>)}
            {["maxWindowHours", "maxConcurrentJobs", "maxResults", "retentionDays", "aiContextBytes"].map(key => (
              <Field key={key} label={key}><input type="number" value={(limit.limits || {})[key] ?? ""}
                aria-label={key}
                onChange={e => setLimit({ ...limit,
                  limits: { ...limit.limits, [key]: e.target.value === "" ? "" : Number(e.target.value) } })} /></Field>
            ))}
            <button className="primary" onClick={async () => {
              setError("");
              try {
                const clean = Object.fromEntries(Object.entries(limit.limits || {})
                  .filter(([, v]) => v !== "" && v != null));
                await api.saveCostLimit({ scope: limit.scope, targetId: limit.targetId, limits: clean });
                limits.reload();
              } catch (e) { setError(String(e)); }
            }}>Save limit</button>
          </div>
          <ErrorNote error={error} />
          {(limits.value || []).map(l => (
            <div key={l.id} className="ai-review-detail">
              <code>{l.scope}:{l.targetId || "user"}</code> <JsonView value={l.limits} />
              <button onClick={() => api.deleteCostLimit(l.id).then(limits.reload)}>Delete</button>
            </div>
          ))}
        </Panel>
      </div>
      <aside className="studio-timeline">
        <Panel title="Connector health">
          {(health.value || []).length === 0 ? <p className="muted">No connectors yet.</p> : (
            <ul className="action-list">
              {(health.value || []).map(h => (
                <li key={h.connectionId}>
                  <b>{h.name}</b> <Badge tone={h.health === "OK" ? "green" : "red"}>{h.health}</Badge>
                  {h.driftDetected && <Badge tone="red">drift</Badge>}
                  <small className="muted">{h.executions} runs · {Math.round((h.errorRate || 0) * 100)}% errors · last ok {h.lastSuccessAt || "—"}</small>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel title="Template catalog">
          {(catalog.value || []).map(t => (
            <div key={t.id} className="ai-review-detail">
              <b>{t.name}</b><p className="muted">{t.description}</p>
              <button onClick={async () => { await api.applyCatalog(t.id, {}); }}>Apply</button>
            </div>
          ))}
        </Panel>
      </aside>
    </div>
  );
}

