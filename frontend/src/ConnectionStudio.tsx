import { useCallback, useEffect, useRef, useState } from "react";
import {
  CheckCircle2, Chrome, Info, MousePointerClick, Play, Plus, RefreshCw,
  ShieldAlert, Square, Unplug, Wand2,
} from "lucide-react";
import {
  del, post, request, pretty,
  type ActivityEvent, type OAuthStart, type Row, type StudioSession,
} from "./api";
import { Badge, Field, Panel } from "./components";
import type { PageProps } from "./App";

const PROVIDERS = [
  ["jira", "Jira Cloud / Data Center"],
  ["github", "GitHub"],
  ["github-copilot", "GitHub Copilot (GitHub Models)"],
  ["copilot", "Microsoft 365 Copilot"],
  ["devin", "Devin"],
  ["stylus", "Stylus"],
  ["custom", "Custom web app"],
] as const;

const METHODS: Record<string, { id: string; label: string }[]> = {
  jira: [
    { id: "oauth", label: "OAuth 2.0 (3LO) · Jira Cloud" },
    { id: "jira-token", label: "Email + API token · Jira Cloud" },
    { id: "pat-dc", label: "Personal access token · Data Center" },
    { id: "browser", label: "Browser pairing" },
  ],
  github: [{ id: "pat", label: "Personal access token" }],
  "github-copilot": [
    { id: "models-pat", label: "GitHub PAT with models:read · GitHub Models" },
    { id: "browser", label: "Browser pairing" },
  ],
  copilot: [
    { id: "entra", label: "Entra OAuth (delegated work/school)" },
    { id: "browser", label: "Browser pairing" },
  ],
  devin: [{ id: "api-key", label: "API key" }],
  stylus: [
    { id: "bearer", label: "Endpoint configuration + credential" },
    { id: "browser", label: "Browser pairing" },
  ],
  custom: [
    { id: "bearer", label: "Endpoint configuration + credential" },
    { id: "browser", label: "Browser pairing" },
  ],
};

const STEPS = ["OPENING", "WAITING_FOR_SIGN_IN", "READY", "RECORDING", "REVIEW", "VALIDATED"];

const EVENT_TONES: Record<string, string> = {
  BROWSER_OPENED: "blue", WAITING_FOR_SIGN_IN: "amber", IDENTITY_VERIFIED: "green",
  RECORDING_STARTED: "blue", REQUEST_CAPTURED: "purple", CONNECTOR_INFERRED: "purple",
  CONNECTOR_VALIDATED: "green", REFRESH_ATTEMPTED: "amber", REFRESH_SUCCEEDED: "green",
  USER_ACTION_REQUIRED: "amber", CANCELLED: "amber", ERROR: "red", DISCONNECTED: "red",
};

function methodOf(connection: Row): string {
  if (connection.authType === "OAUTH") return "oauth";
  if (connection.authType === "JIRA_TOKEN") return "jira-token";
  if (connection.jiraDeployment === "data-center") return "pat-dc";
  if (connection.provider === "devin") return "api-key";
  if (connection.provider === "github-copilot") return "models-pat";
  if (connection.provider === "copilot" && connection.cloudId === undefined && !connection.hasCredential) return "entra";
  if (connection.hasSession || connection.replayMode === "browser") return "browser";
  return "bearer";
}

export function ConnectionStudio({ data, reload }: PageProps) {
  const connections = data.connections || [];
  const [tab, setTab] = useState("");
  const active = connections.find(item => item.id === tab) || connections[0];
  const refresh = useCallback(() => void reload?.(["connections"]), [reload]);

  useEffect(() => {
    const match = window.location.hash.match(/connected=([0-9a-f-]+)/i);
    if (match) setTab(match[1]);
  }, []);

  return (
    <section className="connection-studio" aria-label="Connection Studio">
      <div className="ai-provider-tabs" role="tablist" aria-label="Your apps">
        {connections.map(item => (
          <button key={item.id} type="button" role="tab" aria-selected={active?.id === item.id}
            onClick={() => setTab(item.id)}>
            <Chrome size={15} /> {item.name}
            <Badge tone={item.state === "CONNECTED" ? "green" : item.state === "ERROR" ? "red" : "amber"}>
              {item.state || "NOT_CONNECTED"}
            </Badge>
          </button>
        ))}
        <button type="button" role="tab" aria-selected={!active || tab === "add"} onClick={() => setTab("add")}>
          <Plus size={15} /> Add app
        </button>
      </div>
      {(!active || tab === "add") ? (
        <AddApp onCreated={id => { setTab(id); refresh(); }} />
      ) : (
        <StudioTab key={active.id + ":" + active.revision} connection={active} onChange={refresh} />
      )}
    </section>
  );
}

function AddApp({ onCreated }: { onCreated: (id: string) => void }) {
  const [provider, setProvider] = useState<string>("jira");
  const [method, setMethod] = useState("oauth");
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [credential, setCredential] = useState("");
  const [jiraEmail, setJiraEmail] = useState("");
  const [clientId, setClientId] = useState("");
  const [tenant, setTenant] = useState("common");
  const [requirement, setRequirement] = useState<OAuthStart["requirement"]>();
  const [authorizeUrl, setAuthorizeUrl] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const methods = METHODS[provider] || METHODS.custom;
  useEffect(() => { setMethod((METHODS[provider] || METHODS.custom)[0].id); setRequirement(undefined); }, [provider]);

  function payload(): Row {
    const body: Row = { provider, name, baseUrl };
    switch (method) {
      case "oauth":
        return { ...body, authType: "NONE", jiraDeployment: "cloud", clientId };
      case "jira-token":
        return { ...body, authType: "JIRA_TOKEN", jiraDeployment: "cloud", jiraEmail, credential };
      case "pat-dc":
        return { ...body, authType: "BEARER", jiraDeployment: "data-center", credential };
      case "models-pat":
        return { ...body, authType: "BEARER", credential, modelsBaseUrl: "https://models.github.ai/inference" };
      case "entra":
        return { ...body, authType: "NONE", clientId, tenant };
      case "api-key":
        return { ...body, authType: "BEARER", credential };
      case "browser":
        return { ...body, authType: "NONE" };
      default:
        return { ...body, authType: "BEARER", credential };
    }
  }

  async function connect() {
    setBusy(true); setError(""); setRequirement(undefined); setAuthorizeUrl("");
    try {
      const saved = await post("/connections", payload());
      if (method === "oauth" || method === "entra") {
        const start: OAuthStart = await post("/oauth/" + saved.id + "/start");
        if (start.requirement) {
          setRequirement(start.requirement);
          return;
        } else if (start.authorizeUrl) {
          setAuthorizeUrl(start.authorizeUrl);
        }
      }
      onCreated(saved.id);
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }

  return (
    <Panel title="Connect a new app" action={<Badge tone="blue">PER-USER</Badge>}>
      <div className="info-box"><Info size={15} />
        Browser pairing opens the provider in a separate, controlled Chromium window on this machine —
        providers block embedding in other pages, so the studio drives a real browser with its own profile.
      </div>
      <div className="toolbar">
        <Field label="Provider">
          <select value={provider} onChange={e => setProvider(e.target.value)}>
            {PROVIDERS.map(([id, label]) => <option key={id} value={id}>{label}</option>)}
          </select>
        </Field>
        <Field label="Method">
          <select value={method} onChange={e => setMethod(e.target.value)}>
            {methods.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
          </select>
        </Field>
        <Field label="Display name"><input value={name} onChange={e => setName(e.target.value)} placeholder="My account" /></Field>
        <Field label="URL"><input value={baseUrl} onChange={e => setBaseUrl(e.target.value)} placeholder="https://provider.example" /></Field>
      </div>
      <div className="toolbar">
        {method === "jira-token" && <Field label="Atlassian email"><input value={jiraEmail} onChange={e => setJiraEmail(e.target.value)} /></Field>}
        {(method === "oauth" || method === "entra") && <>
          <Field label="OAuth client id"><input value={clientId} onChange={e => setClientId(e.target.value)} placeholder="Registered app client id" /></Field>
          {method === "entra" && <Field label="Tenant"><input value={tenant} onChange={e => setTenant(e.target.value)} /></Field>}
        </>}
        {!["oauth", "entra", "browser"].includes(method) &&
          <Field label="Credential"><input type="password" value={credential} onChange={e => setCredential(e.target.value)} placeholder="Token or API key" /></Field>}
        <button className="primary" disabled={busy || !baseUrl} onClick={() => void connect()}>
          {busy ? "Connecting…" : "Connect"}
        </button>
      </div>
      {requirement && (
        <div className="requirement-card" role="alert">
          <ShieldAlert size={17} />
          <div>
            <strong>{requirement.code}</strong>
            <p>{requirement.message}</p>
            {(requirement.alternatives || []).includes("JIRA_TOKEN") &&
              <button onClick={() => { setRequirement(undefined); setMethod("jira-token"); }}>Use email + API token instead</button>}
            {(requirement.alternatives || []).includes("CONNECTION_STUDIO") &&
              <button onClick={() => { setRequirement(undefined); setMethod("browser"); }}>Use browser pairing instead</button>}
          </div>
        </div>
      )}
      {authorizeUrl && <p>Complete sign-in: <a href={authorizeUrl} target="_blank" rel="noreferrer">Authorize in the provider window</a></p>}
      {error && <p role="alert" className="error">{error}</p>}
    </Panel>
  );
}

function StudioTab({ connection, onChange }: { connection: Row; onChange: () => void }) {
  const [session, setSession] = useState<StudioSession>();
  const [marker, setMarker] = useState("");
  const [inferred, setInferred] = useState<Row>();
  const [config, setConfig] = useState<Row>();
  const [timeline, setTimeline] = useState<ActivityEvent[]>([]);
  const [sites, setSites] = useState<Row[]>(connection.jiraSites || []);
  const [cloudId, setCloudId] = useState(connection.cloudId || "");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState("");
  const [expanded, setExpanded] = useState<number | undefined>(undefined);
  const poll = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
  const method = methodOf(connection);

  const refreshTimeline = useCallback(() => {
    request("/studio/timeline?connectionId=" + connection.id).then(setTimeline).catch(() => {});
  }, [connection.id]);

  useEffect(() => {
    refreshTimeline();
    return () => { if (poll.current) clearInterval(poll.current); };
  }, [connection.id]);

  function watch(id: string) {
    if (poll.current) clearInterval(poll.current);
    poll.current = setInterval(async () => {
      try {
        const state: StudioSession = await request("/studio/sessions/" + id);
        setSession(state);
        refreshTimeline();
        if (["VALIDATED", "CANCELLED", "ERROR"].includes(state.status) && poll.current) {
          clearInterval(poll.current);
          poll.current = undefined;
        }
      } catch { /* session gone */ }
    }, 1200);
  }

  async function act(label: string, work: () => Promise<unknown>) {
    setBusy(label); setError(""); setNotice("");
    try {
      await work();
      onChange();
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(""); }
  }

  const status = session?.status || "";
  const stepIndex = STEPS.indexOf(status === "CANCELLED" || status === "ERROR" ? "VALIDATED" : status);

  return (
    <div className="studio-grid">
      <div className="studio-main">
        <Panel title={connection.name} action={
          <Badge tone={connection.state === "CONNECTED" ? "green" : connection.state === "ERROR" ? "red" : "amber"}>
            {connection.state || "NOT_CONNECTED"}
          </Badge>}>
          <div className="studio-facts">
            <span><b>Account</b> {connection.verifiedIdentity || "—"}</span>
            <span><b>Host</b> <code>{connection.siteHost || connection.baseUrl}</code></span>
            <span><b>Method</b> {(METHODS[connection.provider] || []).find(item => item.id === method)?.label || method}</span>
          </div>
          <div className="toolbar">
            <button disabled={!!busy} onClick={() => act("test", async () => {
              const result = await post("/connections/" + connection.id + "/test", { confirmSyntheticPrompt: true });
              setNotice("Test: " + (result.state || "done") + (result.lastError ? " · " + result.lastError : ""));
            })}><Play size={14} /> Test</button>
            <button disabled={!!busy} onClick={() => act("reconnect", async () => {
              await post("/connections/" + connection.id + "/reconnect", { id: connection.id });
            })}><RefreshCw size={14} /> Reconnect</button>
            <button disabled={!!busy} onClick={() => {
              if (window.confirm("Disconnect " + connection.name + "? Stored credentials, sessions and captures are deleted.")) {
                void act("disconnect", async () => { await del("/connections/" + connection.id); setSession(undefined); });
              }
            }}><Unplug size={14} /> Disconnect</button>
          </div>
          {connection.provider === "jira" && connection.authType === "OAUTH" && sites.length > 0 && (
            <div className="toolbar">
              <Field label="Jira site">
                <select value={cloudId} onChange={e => setCloudId(e.target.value)}>
                  <option value="">Choose a site</option>
                  {sites.map((site: Row) => <option key={site.id} value={site.id}>{site.name || site.url}</option>)}
                </select>
              </Field>
              <button disabled={!!busy || !cloudId} onClick={() => act("site", async () => {
                await post("/connections/" + connection.id + "/jira-site", { cloudId });
                setNotice("Jira site selected");
              })}>Select site</button>
            </div>
          )}
          {notice && <p className="muted">{notice}</p>}
          {connection.lastError && <p className="error">{connection.lastError}</p>}
        </Panel>

        <Panel title="Browser pairing" action={<Badge tone="purple">CONTROLLED CHROMIUM</Badge>}>
          <div className="info-box"><Info size={15} />
            The provider page opens in a separate, controlled Chromium window on this machine —
            providers block embedding, so pairing happens in a real browser profile that stays yours.
          </div>
          {!session && (
            <button className="primary" disabled={!!busy} onClick={() => act("open", async () => {
              const started: StudioSession = await post("/studio/sessions", {
                connectionId: connection.id, url: connection.baseUrl,
              });
              setSession(started);
              watch(started.id);
            })}><Chrome size={15} /> Open browser</button>
          )}
          {session && <>
            <ol className="studio-stepper" aria-label="Pairing progress">
              {STEPS.map((step, index) => (
                <li key={step} className={index < stepIndex ? "done" : index === stepIndex ? "current" : ""}>
                  {step.replace(/_/g, " ")}
                </li>
              ))}
            </ol>
            {session.error && <p className="error">{session.error}</p>}
            <div className="toolbar">
              {status === "WAITING_FOR_SIGN_IN" && (
                <button className="primary" disabled={!!busy} onClick={() => act("signed-in", async () => {
                  setSession(await post("/studio/sessions/" + session.id + "/signed-in"));
                })}>I've signed in</button>
              )}
              {status === "READY" && <>
                <Field label="Demo prompt">
                  <input value={marker} onChange={e => setMarker(e.target.value)}
                    placeholder="Type this into the provider chat, e.g. studio-probe-123" />
                </Field>
                <button disabled={!!busy || !marker.trim()} onClick={() => act("record", async () => {
                  setSession(await post("/studio/sessions/" + session.id + "/record/start", { promptMarker: marker }));
                })}><MousePointerClick size={14} /> Start recording</button>
              </>}
              {status === "RECORDING" && (
                <button disabled={!!busy} onClick={() => act("stop", async () => {
                  setSession(await post("/studio/sessions/" + session.id + "/record/stop"));
                })}><Square size={14} /> Stop recording</button>
              )}
              {status === "REVIEW" && !inferred && (
                <button className="primary" disabled={!!busy} onClick={() => act("infer", async () => {
                  const result = await post("/studio/sessions/" + session.id + "/infer", { promptMarker: marker });
                  setInferred(result);
                  setConfig(result);
                })}><Wand2 size={15} /> Infer connector</button>
              )}
              {status !== "VALIDATED" && (
                <button disabled={!!busy} onClick={() => act("cancel", async () => {
                  setSession(await post("/studio/sessions/" + session.id + "/cancel"));
                })}>Cancel</button>
              )}
            </div>

            {session.captures?.length > 0 && (
              <table className="capture-table">
                <thead><tr><th>Method</th><th>Host / path</th><th>Status</th><th>Type</th></tr></thead>
                <tbody>
                  {session.captures.map((capture, index) => (
                    <tr key={index} onClick={() => setExpanded(expanded === index ? undefined : index)}>
                      <td><code>{capture.method}</code></td>
                      <td>
                        {capture.url}
                        {expanded === index && <pre className="code output">{pretty({ body: capture.body, response: capture.responsePreview?.slice(0, 2000) })}</pre>}
                      </td>
                      <td>{capture.status}</td>
                      <td>{capture.contentType}{capture.sse ? " · SSE" : ""}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {session.actions?.length > 0 && (
              <ul className="action-list">
                {session.actions.map((action, index) => (
                  <li key={index}>{action.description} {action.locator && <code>{action.locator}</code>}
                    {action.valueLength != null && <span className="muted"> ({action.valueLength} chars)</span>}</li>
                ))}
              </ul>
            )}

            {config && <ConnectorReview config={config} onChange={setConfig}
              onValidate={() => act("validate", async () => {
                await post("/studio/sessions/" + session.id + "/validate", { config });
                setSession(await request("/studio/sessions/" + session.id));
                setNotice("Connector validated — it is now available in AI chat providers.");
              })} busy={busy} />}
          </>}
        </Panel>
      </div>
      <aside className="studio-timeline">
        <Panel title="Activity">
          <ol className="timeline">
            {timeline.map(event => (
              <li key={event.id}>
                <Badge tone={EVENT_TONES[event.type] || "blue"}>{event.type.replace(/_/g, " ")}</Badge>
                <span>{event.message}</span>
                <small className="muted">{event.created_at}</small>
              </li>
            ))}
            {!timeline.length && <p className="muted">No activity yet.</p>}
          </ol>
        </Panel>
      </aside>
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}

function ConnectorReview({ config, onChange, onValidate, busy }: {
  config: Row; onChange: (next: Row) => void; onValidate: () => void; busy: string;
}) {
  const set = (key: string, value: unknown) => onChange({ ...config, [key]: value });
  const template = typeof config.aiRequestTemplate === "string"
    ? config.aiRequestTemplate : pretty(config.aiRequestTemplate || {});
  const steps = typeof config.steps === "string" ? config.steps : pretty(config.steps || []);
  return (
    <div className="ai-review-detail connector-review">
      <strong>Review the inferred connector</strong>
      <div className="toolbar">
        <Field label="Request path"><input value={config.chatPath || ""} onChange={e => set("chatPath", e.target.value)} /></Field>
        <Field label="Method"><select value={config.aiMethod || "POST"} onChange={e => set("aiMethod", e.target.value)}>
          <option>POST</option><option>GET</option>
        </select></Field>
        <Field label="Response format"><select value={config.aiResponseFormat || "json"} onChange={e => set("aiResponseFormat", e.target.value)}>
          <option value="json">JSON</option><option value="sse">SSE</option>
        </select></Field>
        <Field label="Response pointer"><input value={config.aiResponsePointer || ""} onChange={e => set("aiResponsePointer", e.target.value)} placeholder="/choices/0/delta/content" /></Field>
        <Field label="Replay mode"><select value={config.replayMode || "http"} onChange={e => set("replayMode", e.target.value)}>
          <option value="http">HTTP</option><option value="browser">Browser</option>
        </select></Field>
      </div>
      <Field label="Request template ({{prompt}} and {{conversationId}} placeholders)">
        <textarea rows={6} value={template} onChange={e => {
          try { set("aiRequestTemplate", JSON.parse(e.target.value)); }
          catch { set("aiRequestTemplate", e.target.value); }
        }} />
      </Field>
      <Field label="Steps (multi-request protocols)">
        <textarea rows={4} value={steps} onChange={e => {
          try { set("steps", JSON.parse(e.target.value)); }
          catch { set("steps", e.target.value); }
        }} />
      </Field>
      <button className="primary" disabled={!!busy} onClick={onValidate}><CheckCircle2 size={15} /> Validate & activate</button>
    </div>
  );
}
