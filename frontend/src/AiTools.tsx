import { useEffect, useState, type ReactNode } from "react";
import { Bot, CheckCircle2, Circle, PlugZap, Send } from "lucide-react";
import { post, request, pretty, type Row } from "./api";
import { Badge, Field, Panel } from "./components";
import { listOperations } from "./endpoint-model";
import { supportsChat } from "./AiDraftReview";
import type { PageProps } from "./App";

const providers = [
  ["copilot", "Microsoft Copilot"],
  ["devin", "Devin"],
  ["github-copilot", "GitHub Copilot"],
  ["stylus", "Stylus"],
] as const;

export default function AiTools({ data, spec, roles, renderEditor }: Pick<PageProps, "data" | "spec" | "roles"> & {
  renderEditor: (connection: Row, onClose: () => void, onSaved: (saved: Row) => void) => ReactNode;
}) {
  const [active, setActive] = useState<string>(providers[0][0]);
  const [selected, setSelected] = useState<Record<string, string>>({});
  const [editor, setEditor] = useState<Row>();
  const all = data.integrations || [];
  const tabs = [...providers.map(([id, name]) => ({ id, name })), ...Array.from(new Map(
    all.filter(item => supportsChat(item) && !providers.some(([id]) => id === item.provider))
      .map(item => [String(item.provider), {id: String(item.provider), name: String(item.provider)}]),
  ).values())];
  const tab = tabs.find(item => item.id === active) || tabs[0];
  const connections = all.filter(item => item.provider === tab.id);
  const connection = connections.find(item => item.id === selected[tab.id]) || connections.find(item => item.enabled) || connections[0];
  const canEdit = roles?.includes("ROLE_ADMIN");
  const newConnection = () => setEditor({
    provider: tab.id, name: tab.name + " company connection", baseUrl: "", enabled: true,
    authType: "AUTHORIZATION", secretRef: "", allowedRoles: ["ADMIN"], aiChatEnabled: true, aiRequiresApproval: tab.id === "devin",
    aiMethod: "POST", aiHeaders: {}, aiResponseFormat: "json",
  });
  return <section className="ai-tools" aria-label="AI Tools">
    <Panel title="AI Tools" action={<Badge tone="purple">COMPANY CONNECTIONS</Badge>}>
      <div className="ai-provider-tabs" role="tablist" aria-label="AI providers"
        onKeyDown={event => {
          const index = tabs.findIndex(item => item.id === tab.id);
          const next = event.key === "ArrowRight" ? (index + 1) % tabs.length :
            event.key === "ArrowLeft" ? (index + tabs.length - 1) % tabs.length :
            event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : -1;
          if (next < 0) return;
          event.preventDefault();
          setActive(tabs[next].id);
          document.getElementById("ai-provider-" + tabs[next].id)?.focus();
        }}>
        {tabs.map(item => <button type="button" role="tab" key={item.id} id={"ai-provider-" + item.id}
          tabIndex={item.id === tab.id ? 0 : -1} aria-controls="ai-provider-panel" aria-selected={item.id === tab.id}
          onClick={() => setActive(item.id)}><Bot size={16} />{item.name}</button>)}
      </div>
      <div role="tabpanel" id="ai-provider-panel" aria-labelledby={"ai-provider-" + tab.id} className="ai-provider-panel">
        <div className="toolbar ai-provider-actions">
          {connections.length > 0 && <Field label="Company connection"><select value={connection?.id || ""}
            onChange={event => setSelected(old => ({ ...old, [tab.id]: event.target.value }))}>
            {connections.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select></Field>}
          {canEdit && connection && <button onClick={() => setEditor(supportsChat(connection) ? { ...connection, aiChatEnabled: true } : {
            ...connection, baseUrl: "", chatPath: "", aiChatEnabled: true, aiRequiresApproval: tab.id === "devin",
          })}><PlugZap size={15} /> Configure</button>}
          {canEdit && <button onClick={newConnection}>{connection ? "Add another connection" : "Configure " + tab.name}</button>}
        </div>
        <ProviderWorkspace key={tab.id + ":" + connection?.id + ":" + connection?.revision}
          connection={connection} spec={spec} runs={data.runs || []} label={tab.name} />
      </div>
    </Panel>
    {editor && renderEditor(editor, () => setEditor(undefined), saved => {
      setSelected(old => ({ ...old, [String(saved.provider)]: saved.id }));
    })}
  </section>;
}

function ProviderWorkspace({ connection, spec, runs, label }: {connection?: Row; spec?: Row; runs: Row[]; label: string}) {
  const [status, setStatus] = useState<Row>();
  const [prompt, setPrompt] = useState("");
  const [scope, setScope] = useState("none");
  const [operation, setOperation] = useState("");
  const [runIds, setRunIds] = useState<string[]>([]);
  const [preview, setPreview] = useState<Row>();
  const [result, setResult] = useState<Row>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const [confirmTest, setConfirmTest] = useState(false);
  const configured = !!connection && !!supportsChat(connection);
  const enabled = configured && !!connection?.enabled;
  const readOnly = connection?.aiChatEnabled && connection.aiMethod === "GET";
  const needsProbe = connection?.aiRequiresApproval && !connection.aiProbePath;
  const path = "/ai-tools/" + encodeURIComponent(connection?.id || "");
  const state = !configured ? "Not configured" : !enabled ? "Disabled" :
    status?.state === "CONNECTED" ? "Connected" : status?.state === "ERROR" ? "Error" : "Configured, untested";
  const identity = JSON.stringify([connection?.revision, spec?.id, spec?.revision, prompt, scope, operation, runIds]);
  const candidates = runs.filter(run => run.specId === spec?.id).slice(0, 30);
  const requestData = {
    connectionRevision: connection?.revision, specId: spec?.id, specRevision: spec?.revision,
    prompt, scope: readOnly ? "none" : scope, operation, runIds,
  };

  useEffect(() => {
    if (!enabled) return;
    let current = true;
    request(path + "/status").then(value => { if (current) setStatus(value); })
      .catch(failure => { if (current) setError(String(failure)); });
    return () => { current = false; };
  }, [enabled, path]);

  async function perform(action: "test" | "preview" | "send") {
    setBusy(action);
    setError("");
    try {
      if (action === "test") {
        const check = await post(path + "/test", { connectionRevision: connection?.revision, confirmSyntheticPrompt: confirmTest });
        setStatus(check);
        setConfirmTest(false);
      } else if (action === "preview") {
        setPreview({ ...await post(path + "/preview", requestData), identity });
      } else {
        const confirmationId = preview?.confirmationId;
        setPreview(undefined);
        setResult(await post(path + "/send", { ...requestData, confirmationId }));
      }
    } catch (failure) {
      setError(String(failure));
    } finally {
      setBusy("");
    }
  }

  return <>
    <div className="ai-connection-status">
      {state === "Connected" ? <CheckCircle2 size={17} /> : <Circle size={15} />}
      <strong>{state}</strong>
      {connection?.baseUrl && <code>{connection.baseUrl}</code>}
      {status?.testedAt && <span>Last tested {new Date(status.testedAt).toLocaleString()}</span>}
    </div>
    {!enabled && <p className="muted">Configure and enable the {label} endpoint your company provides.</p>}
    {enabled && <>
      <div className="toolbar">
        {!connection?.aiProbePath && !readOnly && !needsProbe && <label>
          <input type="checkbox" checked={confirmTest} onChange={event => setConfirmTest(event.target.checked)} /> Send a synthetic test prompt
        </label>}
        <button disabled={!!busy || !!needsProbe || (!connection?.aiProbePath && !readOnly && !confirmTest)} onClick={() => void perform("test")}>
          {busy === "test" ? "Testing…" : "Test connection"}
        </button>
      </div>
      {needsProbe && <p className="muted">Set a read-only test path to check this task connection.</p>}
      {status?.message && <p className={status.state === "ERROR" ? "error" : "muted"}>{status.message}
        {status.requestId && status.requestId !== "-" && <> · Request ID <code>{status.requestId}</code></>}
      </p>}
      <div className="ai-context-card">
        {!readOnly && <>
          <Field label="Workspace context"><select value={scope} onChange={event => setScope(event.target.value)}>
            <option value="none">Prompt only</option>
            <option value="contract" disabled={!spec}>Current OpenAPI revision</option>
            <option value="operation" disabled={!spec}>Selected endpoint</option>
            <option value="tests" disabled={!spec}>Selected test summaries</option>
          </select></Field>
          {scope === "operation" && <Field label="Endpoint to send"><select value={operation} onChange={event => setOperation(event.target.value)}>
            <option value="">Choose an endpoint</option>
            {listOperations(spec?.document).map(item => <option key={item.key}>{item.key}</option>)}
          </select></Field>}
          {scope === "tests" && <Field label="Test runs to send"><select multiple value={runIds} onChange={event => setRunIds(Array.from(event.target.selectedOptions, option => option.value).slice(0, 10))}>
            {candidates.map(item => <option key={item.id} value={item.id}>{item.status} · {item.caseId} · {item.updatedAt || item.id}</option>)}
          </select></Field>}
          <Field label={label + " prompt"}><textarea rows={4} maxLength={12000} value={prompt} onChange={event => setPrompt(event.target.value)} /></Field>
        </>}
        {readOnly && <p className="muted">This GET endpoint retrieves its configured response without workspace context.</p>}
        <button disabled={!!busy || (!readOnly && (!prompt.trim() || (scope === "operation" && !operation) || (scope === "tests" && !runIds.length)))} onClick={() => void perform("preview")}>Preview request</button>
      </div>
      {preview && preview.identity === identity && <div className="ai-review-detail">
        <strong>Review data before sending to {preview.destination}</strong>
        {!readOnly && <pre className="code output">{preview.prompt}</pre>}
        <div className="toolbar">
          <button className="primary" disabled={!!busy} onClick={() => void perform("send")}><Send size={15} /> Confirm and send</button>
          <button onClick={() => setPreview(undefined)}>Cancel preview</button>
        </div>
      </div>}
    </>}
    {error && <p role="alert" className="error">{error}</p>}
    {result && <div className="ai-review-detail">
      <h3>Provider response</h3>
      <small>Request ID <code>{result.requestId}</code></small>
      {result.proposalId ? <p>Task queued for approval in Integration Hub. Proposal <code>{result.proposalId}</code></p>
        : <pre className="code output">{typeof result.result?.response === "string" ? result.result.response
        : result.result?.choices?.[0]?.message?.content || pretty(result.result?.response ?? result.result)}</pre>}
    </div>}
  </>;
}
