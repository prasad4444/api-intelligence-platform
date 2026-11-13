import { useEffect, useState } from "react";
import { Link2, PlugZap, RefreshCw, Unplug } from "lucide-react";
import { del, post, request, pretty, type Row } from "./api";
import { Badge, Field, Panel } from "./components";

const providers = [
  "jira", "github", "github-copilot", "copilot", "devin", "stylus", "custom",
];

export function PersonalConnections({
  connections = [],
  onChange,
  providerFilter,
}: {
  connections?: Row[];
  onChange?: () => void;
  providerFilter?: string;
}) {
  const [provider, setProvider] = useState(providerFilter || "jira");
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [authType, setAuthType] = useState("BEARER");
  const [jiraEmail, setJiraEmail] = useState("");
  const [credential, setCredential] = useState("");
  const [selected, setSelected] = useState("");
  const [discoveryUrl, setDiscoveryUrl] = useState("");
  const [interactive, setInteractive] = useState(false);
  const [session, setSession] = useState<Row>();
  const [saved, setSaved] = useState<Row[]>([]);
  const [replay, setReplay] = useState<Row>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const mine = connections.filter(item => !providerFilter || item.provider === providerFilter);
  const active = mine.find(item => item.id === selected) || mine[0];

  useEffect(() => {
    request("/discovery/requests").then(setSaved).catch(() => setSaved([]));
  }, [connections]);

  async function run(label: string, work: () => Promise<void>) {
    setBusy(label);
    setError("");
    try {
      await work();
      onChange?.();
    } catch (failure) {
      setError(String(failure));
    } finally {
      setBusy("");
    }
  }

  return (
    <section className="personal-connections" aria-label="Personal connections">
      <Panel title="My provider connections" action={<Badge tone="blue">PER-USER</Badge>}>
        <p className="muted">Each person connects their own Jira, GitHub, Copilot, Devin, Stylus or custom account. Shared admin definitions never substitute for your credentials.</p>
        <div className="toolbar">
          {!providerFilter && <Field label="Provider"><select value={provider} onChange={e => setProvider(e.target.value)}>
            {providers.map(item => <option key={item} value={item}>{item}</option>)}
          </select></Field>}
          <Field label="Display name"><input value={name} onChange={e => setName(e.target.value)} placeholder="My Jira" /></Field>
          <Field label="Base URL"><input value={baseUrl} onChange={e => setBaseUrl(e.target.value)} placeholder="https://example.atlassian.net" /></Field>
          <Field label="Auth"><select value={authType} onChange={e => setAuthType(e.target.value)}>
            <option value="BEARER">BEARER</option>
            <option value="JIRA_TOKEN">JIRA_TOKEN</option>
            <option value="AUTHORIZATION">AUTHORIZATION</option>
            <option value="API_KEY">API_KEY</option>
            <option value="NONE">NONE</option>
          </select></Field>
          {authType === "JIRA_TOKEN" && <Field label="Atlassian email"><input value={jiraEmail} onChange={e => setJiraEmail(e.target.value)} /></Field>}
          <Field label="Credential"><input type="password" value={credential} onChange={e => setCredential(e.target.value)} placeholder="PAT or token" /></Field>
          <button disabled={!!busy || !baseUrl} onClick={() => void run("connect", async () => {
            const savedConn = await post("/connections", {
              provider: providerFilter || provider, name, baseUrl, authType, jiraEmail,
              jiraDeployment: authType === "JIRA_TOKEN" ? "cloud" : undefined, credential,
            });
            setSelected(savedConn.id);
            setCredential("");
          })}><PlugZap size={15} /> Connect</button>
        </div>
        <RecordsStates rows={mine} selected={active?.id} onSelect={setSelected} busy={busy}
          onTest={id => void run("test", async () => { await post("/connections/" + id + "/test", { confirmSyntheticPrompt: true }); })}
          onReconnect={id => void run("reconnect", async () => {
            await post("/connections/" + id + "/reconnect", { credential, baseUrl: baseUrl || undefined, authType });
            setCredential("");
          })}
          onDisconnect={id => void run("disconnect", async () => { await del("/connections/" + id); })}
        />
      </Panel>
      {active && <Panel title="Browser discovery and HTTP replay" action={<Badge>Playwright + direct HTTP</Badge>}>
        <p className="muted">Discovery may launch Chromium. Interactive mode is for local Windows SSO/MFA pairing. Normal replay uses direct HTTP and does not start a browser.</p>
        <div className="toolbar">
          <Field label="Target URL"><input value={discoveryUrl} onChange={e => setDiscoveryUrl(e.target.value)} placeholder="https://approved-host.example/app" /></Field>
          <label><input type="checkbox" checked={interactive} onChange={e => setInteractive(e.target.checked)} /> Interactive sign-in</label>
          <button disabled={!!busy || !discoveryUrl} onClick={() => void run("discover", async () => {
            setSession(await post("/discovery/sessions", { connectionId: active.id, url: discoveryUrl, interactive }));
          })}><Link2 size={15} /> Discover</button>
        </div>
        {session?.captures && <div className="ai-review-detail">
          <strong>Session {session.status}</strong>
          <pre className="code output">{pretty(session.captures)}</pre>
          <button disabled={!!busy || !session.captures?.length} onClick={() => void run("save", async () => {
            await post("/discovery/sessions/" + session.id + "/save", { indexes: session.captures.map((_: unknown, index: number) => index) });
            setSaved(await request("/discovery/requests"));
          })}>Save captured requests</button>
        </div>}
        <RecordsReplay rows={saved.filter(item => item.connectionId === active.id)} busy={busy}
          onReplay={(id, confirm) => void run("replay", async () => {
            setReplay(await post("/discovery/requests/" + id + "/replay", { confirmNonIdempotent: confirm }));
          })} />
        {replay && <pre className="code output">{pretty(replay)}</pre>}
      </Panel>}
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  );
}

function RecordsStates({ rows, selected, onSelect, onTest, onReconnect, onDisconnect, busy }: {
  rows: Row[]; selected?: string; onSelect: (id: string) => void;
  onTest: (id: string) => void; onReconnect: (id: string) => void; onDisconnect: (id: string) => void; busy: string;
}) {
  if (!rows.length) return <p className="muted">No personal connections yet.</p>;
  return <div className="records">
    {rows.map(row => <div key={row.id} className={"record" + (row.id === selected ? " selected" : "")}>
      <button type="button" className="linkish" onClick={() => onSelect(row.id)}>
        <strong>{row.name}</strong> · {row.provider} · {row.siteHost || row.baseUrl}
      </button>
      <Badge tone={row.state === "CONNECTED" ? "green" : row.state === "ERROR" ? "red" : "amber"}>{row.state || "NOT_CONNECTED"}</Badge>
      {row.verifiedIdentity && <span className="muted">{row.verifiedIdentity}</span>}
      <div className="toolbar">
        <button disabled={!!busy} onClick={() => onTest(row.id)}>Test</button>
        <button disabled={!!busy} onClick={() => onReconnect(row.id)}><RefreshCw size={14} /> Reconnect</button>
        <button disabled={!!busy} onClick={() => onDisconnect(row.id)}><Unplug size={14} /> Disconnect</button>
      </div>
    </div>)}
  </div>;
}

function RecordsReplay({ rows, onReplay, busy }: { rows: Row[]; onReplay: (id: string, confirm: boolean) => void; busy: string }) {
  if (!rows.length) return <p className="muted">No saved discovery requests for this connection.</p>;
  return <div className="records">
    {rows.map(row => <div key={row.id} className="record">
      <code>{row.method}</code> {row.url}
      <div className="toolbar">
        <button disabled={!!busy} onClick={() => onReplay(row.id, false)}>Replay</button>
        {!row.idempotent && <button disabled={!!busy} onClick={() => onReplay(row.id, true)}>Confirm non-idempotent replay</button>}
      </div>
    </div>)}
  </div>;
}

export function AdminUsers() {
  const [users, setUsers] = useState<Row[]>([]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState("DEVELOPER");
  const [error, setError] = useState("");
  const reload = () => request("/users").then(setUsers).catch(failure => setError(String(failure)));
  useEffect(() => { reload(); }, []);
  return <Panel title="Platform accounts">
    <p className="muted">Admin provisions individual users. Provider connections remain owned by the person who connects them.</p>
    <div className="toolbar">
      <Field label="Username"><input value={username} onChange={e => setUsername(e.target.value)} /></Field>
      <Field label="Display name"><input value={displayName} onChange={e => setDisplayName(e.target.value)} /></Field>
      <Field label="Password"><input type="password" value={password} onChange={e => setPassword(e.target.value)} /></Field>
      <Field label="Role"><select value={role} onChange={e => setRole(e.target.value)}>
        <option>DEVELOPER</option><option>TESTER</option><option>REVIEWER</option><option>ADMIN</option>
      </select></Field>
      <button onClick={() => void post("/users", { username, password, displayName, roles: [role] }).then(() => {
        setUsername(""); setPassword(""); setDisplayName(""); reload();
      }).catch(failure => setError(String(failure)))}>Provision user</button>
    </div>
    {error && <p className="error">{error}</p>}
    <pre className="code output">{pretty(users)}</pre>
  </Panel>;
}
