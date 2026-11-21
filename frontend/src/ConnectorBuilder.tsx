import { useEffect, useState } from "react";
import {
  AlertTriangle, CheckCircle2, FileCode2, FileUp, GitPullRequest, Info,
  Plus, ShieldAlert, Wand2,
} from "lucide-react";
import { post, request, type Row } from "./api";
import { api } from "./monitoring-api";
import { listOperations } from "./endpoint-model";
import { Badge, Field, JsonView, Panel } from "./components";

const CONFIDENCE_TONE: Record<string, string> = { HIGH: "green", MEDIUM: "amber", LOW: "red" };

// ------------------------------------------------------------------ Splunk

/** Splunk connector detail: config, validation checks, mapping preview, saved searches. */
export function SplunkPanel({ connection, onSaved, act, busy }: {
  connection: Row;
  onSaved: () => void;
  act: (label: string, work: () => Promise<unknown>) => Promise<void>;
  busy: string;
}) {
  const [form, setForm] = useState<Row>({
    deployment: connection.deployment || "ENTERPRISE",
    webUrl: connection.webUrl || "",
    splunkApp: connection.splunkApp || "search",
    splunkOwner: connection.splunkOwner || "-",
    defaultTimezone: connection.defaultTimezone || "UTC",
    correlationPath: connection.correlationPath || "",
    approvedIndexes: (connection.approvedIndexes || []).join(","),
    fieldMappings: connection.fieldMappings || {
      timestamp: "_time", service: "svc_name", environment: "env",
      severity: "severity", httpStatus: "status", durationMs: "dur",
      transactionType: "ttype", correlationId: "correlationId", exception: "exception",
    },
    limits: connection.limits || {
      maxWindowHours: 24, maxConcurrentJobs: 2, maxResults: 50000,
      pageSize: 1000, maxJobSeconds: 300,
    },
  });
  const [validation, setValidation] = useState<Row>();
  const [mapping, setMapping] = useState<Row>();
  const [saved, setSaved] = useState<Row[]>([]);
  const [error, setError] = useState("");

  async function save() {
    await act("splunk-save", async () => {
      await post("/connections", {
        id: connection.id, provider: "splunk",
        deployment: form.deployment, webUrl: form.webUrl,
        splunkApp: form.splunkApp, splunkOwner: form.splunkOwner,
        defaultTimezone: form.defaultTimezone, correlationPath: form.correlationPath,
        approvedIndexes: String(form.approvedIndexes || "").split(",").map(s => s.trim()).filter(Boolean),
        fieldMappings: form.fieldMappings, limits: form.limits,
      });
      onSaved();
    });
  }

  const check = (name: string, c?: Row) => (
    <li key={name}>
      {c?.ok ? <CheckCircle2 size={15} color="var(--green)" /> : <AlertTriangle size={15} color="var(--red)" />}
      <b>{name}</b> {c?.ok ? "ok" : <span className="error">{c?.error || "not run"}</span>}
      {c?.identity && <small className="muted"> {c.identity}</small>}
    </li>
  );

  return (
    <>
      <Panel title="Splunk configuration" action={<Badge tone="blue">MGMT :8089</Badge>}>
        <div className="toolbar">
          <Field label="Deployment"><select value={form.deployment} aria-label="Splunk deployment"
            onChange={e => setForm({ ...form, deployment: e.target.value })}>
            <option value="ENTERPRISE">Enterprise</option><option value="CLOUD">Splunk Cloud</option>
          </select></Field>
          <Field label="Web URL (drill-down links)"><input value={form.webUrl} aria-label="Splunk web URL"
            onChange={e => setForm({ ...form, webUrl: e.target.value })} placeholder="https://splunk.example:8000" /></Field>
          <Field label="App namespace"><input value={form.splunkApp} aria-label="App namespace"
            onChange={e => setForm({ ...form, splunkApp: e.target.value })} /></Field>
          <Field label="Owner namespace"><input value={form.splunkOwner} aria-label="Owner namespace"
            onChange={e => setForm({ ...form, splunkOwner: e.target.value })} placeholder="-" /></Field>
          <Field label="Default timezone"><input value={form.defaultTimezone} aria-label="Default timezone"
            onChange={e => setForm({ ...form, defaultTimezone: e.target.value })} placeholder="America/New_York" /></Field>
          <Field label="Approved indexes"><input value={form.approvedIndexes} aria-label="Approved indexes"
            onChange={e => setForm({ ...form, approvedIndexes: e.target.value })} placeholder="main, os" /></Field>
        </div>
        <details>
          <summary>Limits + field mappings</summary>
          <div className="toolbar">
            {["maxWindowHours", "maxConcurrentJobs", "maxResults", "pageSize", "maxJobSeconds"].map(k => (
              <Field key={k} label={k}><input type="number" aria-label={k}
                value={(form.limits || {})[k] ?? ""}
                onChange={e => setForm({ ...form,
                  limits: { ...form.limits, [k]: Number(e.target.value) } })} /></Field>
            ))}
          </div>
          <Field label="Field mappings (JSON)"><textarea className="code" rows={6}
            aria-label="Field mappings"
            value={JSON.stringify(form.fieldMappings || {}, null, 2)}
            onChange={e => { try { setForm({ ...form, fieldMappings: JSON.parse(e.target.value) }); } catch {} }} /></Field>
        </details>
        <Field label="Correlation endpoint (optional GET path)"><input value={form.correlationPath}
          aria-label="Correlation path" placeholder="/api/events?correlationId="
          onChange={e => setForm({ ...form, correlationPath: e.target.value })} /></Field>
        <div className="toolbar">
          <button className="primary" disabled={!!busy} onClick={() => void save()}>Save configuration</button>
          <button disabled={!!busy} onClick={() => act("splunk-validate", async () => {
            setValidation(await api.validateSplunk(connection.id));
          })}>Validate</button>
          <button disabled={!!busy} onClick={() => act("mapping", async () => {
            setMapping(await api.mappingPreview(connection.id, {}));
          })}>Mapping preview</button>
          <button disabled={!!busy} onClick={() => act("searches", async () => {
            setSaved(await api.savedSearches(connection.id));
          })}>Saved searches</button>
        </div>
        {error && <p role="alert" className="error">{error}</p>}
        {validation && (
          <div className="ai-review-detail">
            <ul className="action-list">
              {check("Authenticate", validation.authenticate)}
              {check("Search index " + (validation.search?.index || ""), validation.search)}
              {check("Retrieve results", validation.retrieve)}
            </ul>
            <p className="muted">
              Version {validation.version || "?"} · search API {validation.apiVersion || "?"}
              {validation.quotas ? " · quotas " + JSON.stringify(validation.quotas) : ""}
            </p>
          </div>
        )}
        {mapping && (
          <div className="ai-review-detail">
            <strong>Field mapping preview (sample of 20)</strong>
            <JsonView value={mapping} />
          </div>
        )}
        {saved.length > 0 && (
          <ul className="action-list">
            {saved.map((s, i) => <li key={i}><b>{s.name}</b> <code>{s.search || s.query}</code></li>)}
          </ul>
        )}
      </Panel>
    </>
  );
}

// ------------------------------------------------------------ import panel

/** HAR + OpenAPI import into captures/config. */
export function ImportPanel({ spec, onInferred, act, busy }: {
  spec?: Row;
  onInferred: (inferred: Row) => void;
  act: (label: string, work: () => Promise<unknown>) => Promise<void>;
  busy: string;
}) {
  const [marker, setMarker] = useState("");
  const [removed, setRemoved] = useState<Row>();
  const [captures, setCaptures] = useState<Row[]>([]);
  const [operation, setOperation] = useState("");
  const [error, setError] = useState("");

  async function importHarFile(file: File) {
    await act("har", async () => {
      const out = await api.importHar(file);
      setCaptures(out.captures || []);
      setRemoved(out.removed);
    });
  }

  const operations = listOperations(spec?.document);
  return (
    <Panel title="Import" action={<Badge tone="purple">SANITIZED</Badge>}>
      <div className="info-box"><Info size={15} />
        HAR files are sanitized on the server: cookies, authorization and token headers and
        password-like body fields are removed and reported before inference runs.
      </div>
      <div className="toolbar">
        <Field label="HAR file (≤10MB)">
          <input type="file" accept=".har,application/json" aria-label="HAR file"
            onChange={e => { const f = e.target.files?.[0]; if (f) void importHarFile(f); }} />
        </Field>
        <Field label="Demo prompt marker"><input value={marker} aria-label="Prompt marker"
          onChange={e => setMarker(e.target.value)} placeholder="prompt you sent" /></Field>
        <button disabled={!!busy || !captures.length} onClick={() => act("infer-har", async () => {
          onInferred(await api.inferCaptures(captures, marker));
        })}><Wand2 size={14} /> Infer from HAR</button>
      </div>
      {removed && (
        <p className="muted">
          Kept {captures.length} captures · removed {removed.entries} entries,
          headers [{((removed.headers || []) as string[]).join(", ") || "none"}],
          body fields [{((removed.bodyFields || []) as string[]).join(", ") || "none"}]
        </p>
      )}
      {spec && (
        <div className="toolbar">
          <Field label="OpenAPI operation"><select value={operation} aria-label="OpenAPI operation"
            onChange={e => setOperation(e.target.value)}>
            <option value="">Choose from {spec.name}</option>
            {operations.map(op => <option key={op.key} value={op.key}>{op.key}</option>)}
          </select></Field>
          <button disabled={!!busy || !operation} onClick={() => act("spec", async () => {
            const op = operations.find(o => o.key === operation);
            if (!op) return;
            onInferred({
              chatPath: op.path, aiMethod: op.method, aiResponseFormat: "json",
              aiResponsePointer: "", steps: [{ method: op.method, path: op.path, template: {} }],
              notes: ["Declarative connector built from " + spec.name + " " + op.key],
              evidence: { chatPath: { confidence: "HIGH", detail: "OpenAPI " + op.key } },
            });
          })}><FileUp size={14} /> Use operation</button>
        </div>
      )}
      {error && <p role="alert" className="error">{error}</p>}
    </Panel>
  );
}

// ----------------------------------------------------- inference review

/** Evidence/confidence review: highlight LOW fields, confirm them, authorize
 * non-GET steps and choose a test environment before validating. */
export function InferenceReview({ inferred, config, onChange, environments }: {
  inferred: Row;
  config: Row;
  onChange: (config: Row, extras: { confirmedFields: string[]; authorizedOperations: Row[]; environmentId: string }) => void;
  environments: Row[];
}) {
  const evidence = (inferred.evidence || {}) as Record<string, Row>;
  const [confirmed, setConfirmed] = useState<string[]>([]);
  const [authorized, setAuthorized] = useState<string[]>([]);
  const [environmentId, setEnvironmentId] = useState("");
  const lowFields = Object.keys(evidence).filter(k => evidence[k]?.confidence === "LOW");
  const writeSteps = ((config.steps || inferred.steps || []) as Row[])
    .filter(s => (s.method || "GET") !== "GET")
    .map(s => (s.method || "GET") + " " + s.path);

  useEffect(() => {
    onChange(config, {
      confirmedFields: confirmed,
      authorizedOperations: writeSteps
        .filter(op => authorized.includes(op))
        .map(op => { const [method, ...rest] = op.split(" "); return { method, path: rest.join(" ") }; }),
      environmentId,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [confirmed, authorized, environmentId]);

  return (
    <div className="ai-review-detail">
      <strong>Inference evidence</strong>
      <table className="capture-table">
        <thead><tr><th>Field</th><th>Value</th><th>Confidence</th><th>Evidence</th><th></th></tr></thead>
        <tbody>
          {Object.entries(evidence).map(([field, ev]) => (
            <tr key={field} className={ev.confidence === "LOW" ? "low-confidence" : ""}>
              <td><code>{field}</code></td>
              <td>{ev.detail || "—"}</td>
              <td><Badge tone={CONFIDENCE_TONE[ev.confidence] || "neutral"}>{ev.confidence}</Badge></td>
              <td>{(ev.captureIds || []).join(", ") || "—"}</td>
              <td>{ev.confidence === "LOW" && (
                <label><input type="checkbox" aria-label={"Confirm " + field}
                  checked={confirmed.includes(field)}
                  onChange={e => setConfirmed(e.target.checked
                    ? [...confirmed, field] : confirmed.filter(f => f !== field))} /> confirm</label>
              )}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {lowFields.length > 0 && confirmed.length < lowFields.length && (
        <p className="error"><ShieldAlert size={14} /> Confirm each LOW-confidence field before validating.</p>
      )}
      {writeSteps.length > 0 && (
        <>
          <strong>Non-GET operations — authorize explicitly</strong>
          {writeSteps.map(op => (
            <label key={op} className="authorize-op">
              <input type="checkbox" aria-label={"Authorize " + op}
                checked={authorized.includes(op)}
                onChange={e => setAuthorized(e.target.checked
                  ? [...authorized, op] : authorized.filter(o => o !== op))} />
              I authorize <code>{op}</code> against the selected environment
            </label>
          ))}
        </>
      )}
      {environments.length > 0 && (
        <Field label="Test environment"><select value={environmentId} aria-label="Test environment"
          onChange={e => setEnvironmentId(e.target.value)}>
          <option value="">None (recorded host)</option>
          {environments.map(env => <option key={env.id} value={env.id}>{env.name}</option>)}
        </select></Field>
      )}
      {(inferred.notes || []).length > 0 && (
        <ul className="muted">{inferred.notes.map((n: string, i: number) => <li key={i}>{n}</li>)}</ul>
      )}
    </div>
  );
}

// -------------------------------------------------------------- codegen

/** Codegen review: file tree + read-only diff, contract validation, PR proposal. */
export function CodegenPanel({ connection, githubConnections, act, busy }: {
  connection: Row;
  githubConnections: Row[];
  act: (label: string, work: () => Promise<unknown>) => Promise<void>;
  busy: string;
}) {
  const [generated, setGenerated] = useState<Row>();
  const [file, setFile] = useState(0);
  const [validation, setValidation] = useState<Row>();
  const [pr, setPr] = useState<Row>();
  const [repo, setRepo] = useState("");
  const [ghConnection, setGhConnection] = useState(githubConnections[0]?.id || "");
  const [error, setError] = useState("");

  useEffect(() => { if (!ghConnection && githubConnections.length) setGhConnection(githubConnections[0].id); },
    [githubConnections]);

  return (
    <Panel title="Generated code" action={<Badge tone="amber">REVIEW ONLY — never loaded at runtime</Badge>}>
      <div className="toolbar">
        <button disabled={!!busy} onClick={() => act("codegen", async () => {
          const out = await api.codegen(connection.id, {
            name: (connection.name || "connector").replace(/[^A-Za-z0-9_-]/g, "-"),
            config: connection.connectorConfig || connection,
          });
          setGenerated(out); setFile(0); setValidation(undefined);
        })}><FileCode2 size={14} /> Generate code</button>
        {generated && <>
          <button disabled={!!busy} onClick={() => act("codegen-check", async () => {
            setValidation(await api.validateGenerated(connection.id, {
              config: connection.connectorConfig || connection, fixture: {},
            }));
          })}>Validate generated</button>
        </>}
      </div>
      {generated && (
        <div className="codegen-view">
          <div className="codegen-tree">
            {(generated.files || []).map((f: Row, i: number) => (
              <button key={f.path} className={i === file ? "active" : ""}
                onClick={() => setFile(i)}>{f.path}</button>
            ))}
          </div>
          <pre className="code output codegen-file">{(generated.files || [])[file]?.content}</pre>
        </div>
      )}
      {validation && (
        <p className={validation.ok ? "muted" : "error"}>
          Contract check: {validation.ok ? "passed" : "failed — " + validation.error}
          {validation.extracted ? " (extracted: " + validation.extracted + ")" : ""}
        </p>
      )}
      {generated && (
        <div className="toolbar">
          <Field label="GitHub connection"><select value={ghConnection} aria-label="GitHub connection"
            onChange={e => setGhConnection(e.target.value)}>
            {githubConnections.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select></Field>
          <Field label="Repo (owner/name)"><input value={repo} aria-label="Target repo"
            onChange={e => setRepo(e.target.value)} placeholder="acme/platform" /></Field>
          <button disabled={!!busy || !ghConnection || !repo} onClick={() => act("pr", async () => {
            setPr(await api.proposePr(connection.id, {
              githubConnectionId: ghConnection, repo,
              name: (connection.name || "connector").replace(/[^a-z0-9-]/g, "-"),
              title: "Generated connector: " + connection.name,
              files: generated.files,
            }));
          })}><GitPullRequest size={14} /> Create PR (proposal)</button>
        </div>
      )}
      {pr && (
        <div className="ai-review-detail">
          <strong>PR proposal {pr.id} — {pr.status}</strong>
          <p className="muted">Goes through the reviewed-write flow: approve it under approvals,
            then execution creates branch <code>{pr.action?.body?.branch}</code>, commits files and opens the PR.</p>
        </div>
      )}
      {error && <p role="alert" className="error">{error}</p>}
    </Panel>
  );
}

// ------------------------------------------------------------- versions

export function VersionsPanel({ connection }: { connection: Row }) {
  const [versions, setVersions] = useState<Row[]>([]);
  const [error, setError] = useState("");
  const reload = () => api.connectorVersions(connection.id).then(setVersions).catch(() => {});
  useEffect(() => { void reload(); }, [connection.id]);
  return (
    <Panel title="Connector versions" action={<Badge tone="blue">DECLARATIVE</Badge>}>
      <div className="toolbar">
        <button onClick={async () => {
          setError("");
          try {
            await api.saveConnectorVersion(connection.id, {
              config: connection.connectorConfig || {
                chatPath: connection.chatPath, aiMethod: connection.aiMethod,
                aiResponseFormat: connection.aiResponseFormat,
                aiResponsePointer: connection.aiResponsePointer,
                aiRequestTemplate: connection.aiRequestTemplate, steps: connection.steps,
              },
            });
            await reload();
          } catch (e) { setError(String(e)); }
        }}><Plus size={13} /> Save current as version</button>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      <table className="capture-table">
        <thead><tr><th>Version</th><th>Status</th><th>Schema</th><th>Created</th><th></th></tr></thead>
        <tbody>
          {versions.map(v => (
            <tr key={v.id}>
              <td>v{v.version}</td>
              <td><Badge tone={v.status === "ACTIVE" ? "green" : v.status === "VALIDATED" ? "blue" : "neutral"}>{v.status}</Badge></td>
              <td><code>{(v.schemaHash || "").slice(0, 8) || "—"}</code></td>
              <td>{v.createdAt}</td>
              <td className="row-actions">
                {v.status === "DRAFT" && (
                  <button onClick={() => api.validateVersion(v.id).then(reload)}>Mark validated</button>)}
                {v.status === "VALIDATED" && (
                  <button onClick={() => api.activateVersion(v.id).then(reload)}>Activate</button>)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}

