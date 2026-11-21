import { useCallback, useEffect, useRef, useState } from "react";
import {
  ExternalLink, ListChecks, MessageSquarePlus,
  Pencil, Paperclip, Send, Square, Trash2, X,
} from "lucide-react";
import {
  post, request, del, streamSse, pretty,
  type ChatConversation, type ChatMessage, type ChatPreview, type ChatProvider,
  type ChatStreamEvent, type JiraSearchResult, type Row,
} from "./api";
import { Badge, Field, Panel } from "./components";
import { listOperations } from "./endpoint-model";
import { api as monitoringApi } from "./monitoring-api";

type ContextForm = {
  scope: string;
  operation: string;
  runIds: string[];
  jiraConnectionId: string;
  jql: string;
  issueKeys: string[];
  githubConnectionId: string;
  repo: string;
  path: string;
  captureId: string;
};

const emptyContext: ContextForm = {
  scope: "none", operation: "", runIds: [], jiraConnectionId: "", jql: "",
  issueKeys: [], githubConnectionId: "", repo: "", path: "", captureId: "",
};

function metaOf(message: ChatMessage): Row {
  if (!message.meta) return {};
  if (typeof message.meta === "string") {
    try { return JSON.parse(message.meta); } catch { return {}; }
  }
  return message.meta as Row;
}

export function ChatPanel({ spec }: { spec?: Row }) {
  const [providers, setProviders] = useState<ChatProvider[]>([]);
  const [conversations, setConversations] = useState<ChatConversation[]>([]);
  const [activeId, setActiveId] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [connectionId, setConnectionId] = useState("");
  const [prompt, setPrompt] = useState("");
  const [context, setContext] = useState<ContextForm>(emptyContext);
  const [attachOpen, setAttachOpen] = useState(false);
  const [preview, setPreview] = useState<ChatPreview>();
  const [pendingBody, setPendingBody] = useState<Row>();
  const [transfer, setTransfer] = useState<{ preview: ChatPreview; body: Row }>();
  const [streaming, setStreaming] = useState<{ text: string; task?: Row; messageId?: string }>();
  const [streamError, setStreamError] = useState<{ code: string; message: string; action: string }>();
  const [renaming, setRenaming] = useState("");
  const [renameText, setRenameText] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const [connections, setConnections] = useState<Row[]>([]);
  const [captures, setCaptures] = useState<Row[]>([]);
  const [related, setRelated] = useState<Row[]>([]);
  const abort = useRef<AbortController | undefined>(undefined);

  const conversation = conversations.find(item => item.id === activeId);
  const selected = providers.find(item => item.connectionId === connectionId);
  const boundConnectionId = String(conversation?.connection_id || "");
  const switching = !!activeId && !!boundConnectionId && !!connectionId && boundConnectionId !== connectionId;

  const reloadConversations = useCallback(async () => {
    setConversations(await request("/chat/conversations"));
  }, []);

  useEffect(() => {
    request("/chat/providers").then((rows: ChatProvider[]) => {
      setProviders(rows);
      if (!connectionId && rows.length) setConnectionId(rows[0].connectionId);
    }).catch(failure => setError(String(failure)));
    reloadConversations().catch(failure => setError(String(failure)));
    request("/connections").then(setConnections).catch(() => setConnections([]));
    request("/discovery/requests").then(setCaptures).catch(() => setCaptures([]));
  }, []);

  useEffect(() => {
    if (!activeId) { setMessages([]); return; }
    request("/chat/conversations/" + activeId + "/messages").then(setMessages).catch(() => {});
    const bound = conversations.find(item => item.id === activeId)?.connection_id;
    if (bound) setConnectionId(String(bound));
  }, [activeId]);

  function contextBody(): Row {
    const body: Row = { scope: context.scope };
    if (context.scope === "operation") body.operation = context.operation;
    if (context.scope === "tests") body.runIds = context.runIds;
    if (context.scope === "jira") {
      body.jiraConnectionId = context.jiraConnectionId;
      body.issueKeys = context.issueKeys;
    }
    if (context.scope === "github") {
      body.githubConnectionId = context.githubConnectionId;
      body.repo = context.repo;
      body.path = context.path;
    }
    if (context.scope === "replay") body.captureId = context.captureId;
    return body;
  }

  async function startConversation(): Promise<string> {
    setError("");
    try {
      const created = await post("/chat/conversations", { connectionId, title: "New chat" });
      await reloadConversations();
      setActiveId(created.id);
      setPreview(undefined); setStreamError(undefined);
      return created.id;
    } catch (failure) { setError(String(failure)); return ""; }
  }

  async function doPreview(transferHistory?: boolean) {
    setBusy("preview"); setError(""); setStreamError(undefined);
    monitoringApi.knowledgeSearch(prompt, "INCIDENT_RESOLUTION,INVESTIGATION")
      .then(setRelated).catch(() => {});
    try {
      const target = activeId || await startConversation();
      if (!target) return;
      const body = {
        prompt, connectionId, ...contextBody(),
        ...(transferHistory ? { transferHistory: true } : {}),
      };
      const result: ChatPreview = await post("/chat/conversations/" + target + "/preview", body);
      if (result.historyTransfer?.length && !transferHistory) {
        setTransfer({ preview: result, body });
        return;
      }
      setPreview(result);
      setPendingBody(body);
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(""); }
  }

  function handleEvent(event: ChatStreamEvent) {
    if (event.type === "meta") {
      setStreaming(current => ({ text: current?.text || "", task: current?.task, messageId: event.data.messageId }));
    } else if (event.type === "delta") {
      setStreaming(current => ({ text: (current?.text || "") + event.data.text, task: current?.task, messageId: current?.messageId }));
    } else if (event.type === "task") {
      setStreaming(current => ({ text: current?.text || "", task: event.data, messageId: current?.messageId }));
    } else if (event.type === "error") {
      setStreamError(event.data);
    }
  }

  async function doSend(previewData: ChatPreview, body: Row) {
    setBusy("send"); setError(""); setStreamError(undefined);
    setStreaming({ text: "" });
    const controller = new AbortController();
    abort.current = controller;
    try {
      await streamSse("/chat/conversations/" + activeId + "/send",
        { ...body, confirmationId: previewData.confirmationId },
        controller.signal, handleEvent);
      const reloaded = await request("/chat/conversations/" + activeId + "/messages");
      setMessages(reloaded);
      reloadConversations();
    } catch (failure) {
      if (!controller.signal.aborted) setStreamError({ code: "REQUEST_FAILED", message: String(failure), action: "RETRY" });
      try { setMessages(await request("/chat/conversations/" + activeId + "/messages")); } catch {}
    } finally {
      setStreaming(undefined);
      setPreview(undefined);
      setPendingBody(undefined);
      setBusy("");
      abort.current = undefined;
    }
  }

  async function stop() {
    abort.current?.abort();
    try { await post("/chat/conversations/" + activeId + "/cancel"); } catch {}
    try { setMessages(await request("/chat/conversations/" + activeId + "/messages")); } catch {}
    setStreaming(undefined);
  }

  async function rename(id: string) {
    if (!renameText.trim()) { setRenaming(""); return; }
    try {
      await request("/chat/conversations/" + id, { method: "PATCH", body: JSON.stringify({ title: renameText }) });
      await reloadConversations();
    } catch (failure) { setError(String(failure)); }
    setRenaming("");
  }

  async function remove(id: string) {
    if (!window.confirm("Delete this conversation and its messages?")) return;
    try {
      await del("/chat/conversations/" + id);
      if (activeId === id) setActiveId("");
      await reloadConversations();
    } catch (failure) { setError(String(failure)); }
  }

  const jiraConnections = connections.filter(item => item.provider === "jira");
  const githubConnections = connections.filter(item => item.provider === "github");
  const grouped = providers.reduce<Record<string, ChatProvider[]>>((all, item) => {
    (all[item.provider] ||= []).push(item);
    return all;
  }, {});

  return (
    <div className="chat-layout">
      <aside className="chat-sidebar">
        <button className="primary" onClick={() => void startConversation()} disabled={!connectionId}>
          <MessageSquarePlus size={15} /> New chat
        </button>
        <div className="chat-conversation-list">
          {conversations.map(item => (
            <div key={item.id} className={"chat-conversation" + (item.id === activeId ? " active" : "")}>
              {renaming === item.id ? (
                <input autoFocus value={renameText} aria-label="Rename conversation"
                  onChange={e => setRenameText(e.target.value)}
                  onKeyDown={e => { if (e.key === "Enter") void rename(item.id); if (e.key === "Escape") setRenaming(""); }}
                  onBlur={() => void rename(item.id)} />
              ) : (
                <button type="button" className="linkish" onClick={() => { setActiveId(item.id); setPreview(undefined); setStreamError(undefined); }}>
                  {item.title || "New chat"}
                </button>
              )}
              <button aria-label="Rename" onClick={() => { setRenaming(item.id); setRenameText(item.title || ""); }}><Pencil size={13} /></button>
              <button aria-label="Delete" onClick={() => void remove(item.id)}><Trash2 size={13} /></button>
            </div>
          ))}
          {!conversations.length && <p className="muted">No conversations yet.</p>}
        </div>
      </aside>
      <div className="chat-main">
        <div className="chat-provider-bar">
          <Field label="Provider">
            <select value={connectionId} onChange={e => setConnectionId(e.target.value)} aria-label="Chat provider">
              {Object.entries(grouped).map(([provider, items]) => (
                <optgroup key={provider} label={provider}>
                  {items.map(item => (
                    <option key={item.connectionId} value={item.connectionId}>
                      {item.name}{item.account ? " · " + item.account : ""} · {item.host}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </Field>
          {selected && <>
            <Badge tone={selected.state === "CONNECTED" ? "green" : "amber"}>{selected.state}</Badge>
            <span className="capability-chips">
              <Badge>{selected.capabilities.mode === "task" ? "Task" : "Chat"}</Badge>
              {selected.capabilities.streaming && <Badge tone="blue">Streaming</Badge>}
              {selected.capabilities.browserBacked && <Badge tone="purple">Browser-backed</Badge>}
            </span>
          </>}
          {switching && <Badge tone="amber">Switching provider — history transfer needs confirmation</Badge>}
        </div>

        <div className="chat-thread" aria-label="Conversation">
          {messages.map(message => {
            const meta = metaOf(message);
            return (
              <div key={message.id} className={"chat-message " + message.role}>
                <div className="chat-bubble">
                  {message.content}
                  {meta.task && <div className="chat-task-card">
                    <ListChecks size={15} /> Task {meta.task.status}
                    {meta.task.url && <a href={meta.task.url} target="_blank" rel="noreferrer">Open session <ExternalLink size={12} /></a>}
                  </div>}
                </div>
                {message.role === "assistant" &&
                  <small className="muted">{meta.provider || message.provider}{meta.account || message.account ? " · " + (meta.account || message.account) : ""}{message.status !== "complete" ? " · " + message.status : ""}</small>}
              </div>
            );
          })}
          {streaming && (
            <div className="chat-message assistant">
              <div className="chat-bubble">
                {streaming.text || "…"}
                {streaming.task && <div className="chat-task-card">
                  <ListChecks size={15} /> Task {streaming.task.status}
                  {streaming.task.url && <a href={streaming.task.url} target="_blank" rel="noreferrer">Open session <ExternalLink size={12} /></a>}
                </div>}
              </div>
              <small className="muted">{selected?.provider} · {selected?.account} · streaming</small>
            </div>
          )}
        </div>

        {streamError && <div className="chat-error" role="alert">
          <strong>{streamError.code}</strong> {streamError.message}
          {streamError.action === "RECONNECT" && <a href="#connection-studio">Reconnect in Connection Studio</a>}
          {streamError.action === "FIX_CONFIG" && <span className="muted">Review the connector configuration.</span>}
          {streamError.action === "RETRY" && <button onClick={() => void doPreview()}>Retry</button>}
        </div>}
        {error && <p role="alert" className="error">{error}</p>}

        {preview && !transfer && (
          <div className="ai-review-detail chat-confirm" role="dialog" aria-label="Confirm send">
            <strong>Review before sending to {preview.destination}</strong>
            <small className="muted">{preview.provider}{preview.account ? " · " + preview.account : ""}</small>
            <pre className="code output">{preview.prompt}</pre>
            {preview.context != null && <pre className="code output">{pretty(preview.context)}</pre>}
            <div className="toolbar">
              <button className="primary" disabled={busy === "send"} onClick={() => void doSend(preview, pendingBody || {})}>
                <Send size={15} /> Confirm and send
              </button>
              <button onClick={() => { setPreview(undefined); setPendingBody(undefined); }}>Cancel preview</button>
            </div>
          </div>
        )}

        <div className="chat-composer">
          <div className="toolbar">
            <button aria-label="Attach context" onClick={() => setAttachOpen(open => !open)}><Paperclip size={15} /> Context</button>
            {context.scope !== "none" && <Badge tone="blue">{context.scope} attached</Badge>}
          </div>
          {attachOpen && (
            <div className="ai-context-card">
              <Field label="Attach">
                <select value={context.scope} onChange={e => setContext({ ...emptyContext, scope: e.target.value })}>
                  <option value="none">Nothing</option>
                  <option value="contract" disabled={!spec}>Current OpenAPI revision</option>
                  <option value="operation" disabled={!spec}>Selected endpoint</option>
                  <option value="tests" disabled={!spec}>Selected test summaries</option>
                  <option value="jira">Jira issues</option>
                  <option value="github">GitHub file</option>
                  <option value="replay" disabled={!captures.length}>Captured API result</option>
                </select>
              </Field>
              {context.scope === "operation" && (
                <Field label="Endpoint"><select value={context.operation} onChange={e => setContext({ ...context, operation: e.target.value })}>
                  <option value="">Choose an endpoint</option>
                  {listOperations(spec?.document).map(item => <option key={item.key}>{item.key}</option>)}
                </select></Field>
              )}
              {context.scope === "tests" && (
                <Field label="Test run ids"><input value={context.runIds.join(",")} placeholder="comma separated run ids"
                  onChange={e => setContext({ ...context, runIds: e.target.value.split(",").map(v => v.trim()).filter(Boolean) })} /></Field>
              )}
              {context.scope === "jira" && <JiraPicker
                connections={jiraConnections}
                context={context}
                onChange={next => setContext(next)} />}
              {context.scope === "github" && <>
                <Field label="GitHub connection"><select value={context.githubConnectionId}
                  onChange={e => setContext({ ...context, githubConnectionId: e.target.value })}>
                  <option value="">Choose a connection</option>
                  {githubConnections.map(item => <option key={item.id} value={item.id}>{item.name}{item.verifiedIdentity ? " · " + item.verifiedIdentity : ""}</option>)}
                </select></Field>
                <Field label="Repository"><input value={context.repo} placeholder="owner/repo" onChange={e => setContext({ ...context, repo: e.target.value })} /></Field>
                <Field label="Path"><input value={context.path} placeholder="src/file.ts" onChange={e => setContext({ ...context, path: e.target.value })} /></Field>
              </>}
              {context.scope === "replay" && (
                <Field label="Captured request"><select value={context.captureId} onChange={e => setContext({ ...context, captureId: e.target.value })}>
                  <option value="">Choose a capture</option>
                  {captures.map(item => <option key={item.id} value={item.id}>{item.method} {item.url}</option>)}
                </select></Field>
              )}
            </div>
          )}
          <div className="chat-input-row">
            <textarea rows={3} maxLength={12000} value={prompt} aria-label="Message"
              placeholder={activeId ? "Message " + (selected?.name || "the provider") : "Start a conversation…"}
              onChange={e => setPrompt(e.target.value)}
              onKeyDown={e => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) void doPreview(); }} />
            {streaming ? (
              <button aria-label="Stop" onClick={() => void stop()}><Square size={15} /> Stop</button>
            ) : (
              <button className="primary" disabled={!!busy || !prompt.trim() || !connectionId}
                onClick={() => void doPreview()}>
                {busy === "preview" ? "Preparing…" : <><Send size={15} /> Send</>}
              </button>
            )}
          </div>
        </div>
      </div>

      {related.length > 0 && (
        <aside className="related-incidents" aria-label="Related earlier incidents">
          <Panel title="Related earlier incidents">
            <ul className="action-list">
              {related.slice(0, 8).map((item, i) => {
                const it = item.item || item;
                return (
                  <li key={it.id || i}>
                    <Badge tone={it.kind === "INCIDENT_RESOLUTION" ? "green" : "purple"}>
                      {String(it.kind || "").replace(/_/g, " ")}
                    </Badge>
                    <b>{it.title}</b>
                    {it.stale || item.stale ? <Badge tone="amber">stale</Badge> : null}
                    {it.body && <small className="muted">{String(it.body).slice(0, 120)}</small>}
                  </li>
                );
              })}
            </ul>
          </Panel>
        </aside>
      )}

      {transfer && (
        <div className="modal-backdrop" onClick={() => setTransfer(undefined)}>
          <div className="command-palette chat-transfer-modal" role="dialog" aria-label="Transfer history"
            onClick={e => e.stopPropagation()}>
            <strong>Send conversation history to {transfer.preview.provider}?</strong>
            <p className="muted">This conversation belongs to a different provider. The messages below would be shared with {transfer.preview.provider}{transfer.preview.account ? " · " + transfer.preview.account : ""}.</p>
            <div className="chat-transfer-list">
              {(transfer.preview.historyTransfer || []).map((turn, index) => (
                <div key={index} className={"chat-message " + turn.role}>
                  <div className="chat-bubble"><small>{turn.role}</small> {turn.content}</div>
                </div>
              ))}
            </div>
            <div className="toolbar">
              <button className="primary" onClick={() => {
                const confirmed = transfer;
                setTransfer(undefined);
                void (async () => {
                  setBusy("preview");
                  try {
                    const body = { ...confirmed.body, transferHistory: true };
                    const result = await post("/chat/conversations/" + activeId + "/preview", body);
                    await doSend(result, body);
                  } catch (failure) { setError(String(failure)); }
                  finally { setBusy(""); }
                })();
              }}>Send history to {transfer.preview.provider}</button>
              <button onClick={() => { setTransfer(undefined); void startConversation(); }}>Start new conversation</button>
              <button onClick={() => setTransfer(undefined)}><X size={14} /> Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function JiraPicker({ connections, context, onChange }: {
  connections: Row[];
  context: ContextForm;
  onChange: (next: ContextForm) => void;
}) {
  const [results, setResults] = useState<JiraSearchResult["issues"]>([]);
  const [nextPageToken, setNextPageToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function search(token?: string) {
    if (!context.jiraConnectionId || !context.jql.trim()) return;
    setBusy(true); setError("");
    try {
      const result: JiraSearchResult = await request(
        "/jira/" + context.jiraConnectionId + "/search?jql=" + encodeURIComponent(context.jql)
          + (token ? "&nextPageToken=" + encodeURIComponent(token) : ""));
      setResults(token ? [...results, ...(result.issues || [])] : result.issues || []);
      setNextPageToken(result.nextPageToken || "");
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }

  return <>
    <Field label="Jira connection">
      <select value={context.jiraConnectionId} onChange={e => onChange({ ...context, jiraConnectionId: e.target.value, issueKeys: [] })}>
        <option value="">Choose a connection</option>
        {connections.map(item => <option key={item.id} value={item.id}>{item.name}{item.verifiedIdentity ? " · " + item.verifiedIdentity : ""}</option>)}
      </select>
    </Field>
    <Field label="JQL">
      <input value={context.jql} placeholder="project = ABC" onChange={e => onChange({ ...context, jql: e.target.value })} />
    </Field>
    <div className="toolbar">
      <button disabled={busy || !context.jiraConnectionId || !context.jql.trim()} onClick={() => void search()}>Search</button>
      {nextPageToken && <button disabled={busy} onClick={() => void search(nextPageToken)}>Load more</button>}
    </div>
    {error && <p className="error">{error}</p>}
    <div className="jira-issue-picker">
      {results.map(issue => (
        <label key={issue.key} className="jira-issue">
          <input type="checkbox" checked={context.issueKeys.includes(issue.key)}
            onChange={e => onChange({
              ...context,
              issueKeys: e.target.checked
                ? [...context.issueKeys, issue.key]
                : context.issueKeys.filter(key => key !== issue.key),
            })} />
          {issue.key} <span className="muted">{issue.summary || ""}</span>
        </label>
      ))}
    </div>
  </>;
}
