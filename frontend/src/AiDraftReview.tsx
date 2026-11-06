import { useEffect, useRef, useState } from "react";
import { Badge, Field, Panel } from "./components";
import { listOperations, object } from "./endpoint-model";
import { post, pretty, type Row } from "./api";
import ContractCompare from "./ContractCompare";
import type { PageProps } from "./App";

const kinds = [
  ["contract", "Contract suggestion"],
  ["diagram", "Architecture diagram"],
  ["story", "Jira story draft"],
] as const;

export function supportsChat(connection: Row) {
  return connection.aiChatEnabled || ["openai", "azure", "anthropic", "ollama"].includes(connection.provider);
}

export default function AiDraftReview({ data, spec, reload, setPage }: PageProps) {
  const [kind, setKind] = useState("contract");
  const [connectorId, setConnectorId] = useState("");
  const [operation, setOperation] = useState("");
  const [instruction, setInstruction] = useState("Identify and address gaps in the selected API.");
  const [consent, setConsent] = useState("");
  const [draft, setDraft] = useState<Row>();
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [jiraConnector, setJiraConnector] = useState("");
  const [jiraProject, setJiraProject] = useState("");
  const [diagramSvg, setDiagramSvg] = useState("");
  const [diagramError, setDiagramError] = useState("");
  const diagramSequence = useRef(0);
  const connector = (data.integrations || []).find(item => item.id === connectorId && item.enabled && supportsChat(item));
  const key = JSON.stringify([spec?.id, spec?.revision, connector?.id, connector?.revision, kind, operation, instruction]);
  const stale = draft && (draft.specId !== spec?.id || draft.specRevision !== spec?.revision);
  const operations = listOperations(spec?.document);
  const drafts = data["ai-drafts"] || [];
  let artifact: Row = {};
  try { artifact = object(JSON.parse(text)); } catch {}
  const source = draft?.kind === "diagram" && typeof artifact.source === "string" ? artifact.source : "";

  useEffect(() => {
    setDiagramSvg("");
    setDiagramError("");
    if (!source || !/^(flowchart|graph|sequenceDiagram|classDiagram)\b/.test(source) || /%%\{|<|javascript:/i.test(source)) return;
    let current = true;
    const timer = window.setTimeout(async () => {
      try {
        const {default: mermaid} = await import("mermaid");
        if (!current) return;
        mermaid.initialize({startOnLoad: false, securityLevel: "strict", theme: "neutral"});
        const {svg} = await mermaid.render("ai-draft-" + Date.now() + "-" + ++diagramSequence.current, source);
        if (current) setDiagramSvg(svg);
      } catch {
        if (current) setDiagramError("Diagram could not render. Review the Mermaid syntax.");
      }
    }, 250);
    return () => {current = false;window.clearTimeout(timer);};
  }, [source]);

  function open(d: Row) {
    setDraft(d);
    setText(pretty(d.artifact));
    setNotice("");
    setError("");
  }
  async function generate() {
    if (!spec || !connector || consent !== key) return;
    setBusy(true);
    setError("");
    try {
      const result = await post("/ai-drafts/preview", {
        specId: spec.id, specRevision: spec.revision, integrationId: connector.id,
        integrationRevision: connector.revision, operation: kind === "contract" ? "" : operation,
        kind, prompt: instruction, consent: true,
      });
      open(result);
      setConsent("");
      await reload(["ai-drafts"]);
    } catch (e) { setError(String(e)); }
    finally { setBusy(false); }
  }
  async function accept() {
    if (!draft || stale || draft.status !== "DRAFT") return;
    setBusy(true);
    setError("");
    try {
      const result = await post("/ai-drafts/" + encodeURIComponent(draft.id) + "/accept", {
        expectedRevision: draft.revision, artifact: JSON.parse(text),
      });
      open(result.draft);
      setNotice(draft.kind === "contract" ? "Saved a new contract draft." :
        draft.kind === "diagram" ? "Saved to Architecture." : "Saved as a local Jira story draft.");
      await reload(["ai-drafts", ...(draft.kind === "contract" ? ["specs"] : [])]);
    } catch (e) { setError(String(e)); }
    finally { setBusy(false); }
  }
  async function proposeStory() {
    if (!draft || draft.status !== "ACCEPTED" || draft.jiraProposalId) return;
    setBusy(true);
    setError("");
    try {
      open(await post("/ai-drafts/" + encodeURIComponent(draft.id) + "/jira-proposal", {
        expectedRevision: draft.revision, integrationId: jiraConnector, project: jiraProject,
      }));
      setNotice("Jira issue creation is pending in the approval queue.");
      await reload(["ai-drafts"]);
    } catch (e) {setError(String(e));}
    finally {setBusy(false);}
  }
  return <Panel title="AI artifact review" action={<Badge tone="purple">REVIEW BEFORE APPLYING</Badge>}>
    <div className="two-cols compact">
      <Field label="Artifact"><select value={kind} onChange={e => {setKind(e.target.value);setOperation("");}}>
        {kinds.map(([value,label]) => <option value={value} key={value}>{label}</option>)}
      </select></Field>
      <Field label="Artifact provider"><select value={connectorId} onChange={e => setConnectorId(e.target.value)}>
        <option value="">Select an enabled AI connector</option>
        {(data.integrations || []).filter(item => item.enabled && supportsChat(item)).map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
      </select></Field>
      {kind !== "contract" && <Field label="Operation context"><select value={operation} onChange={e => setOperation(e.target.value)}>
        <option value="">Entire saved contract</option>
        {operations.map(item => <option key={item.key}>{item.key}</option>)}
      </select></Field>}
    </div>
    <Field label="Artifact instruction"><textarea value={instruction} maxLength={2000} onChange={e => setInstruction(e.target.value)} /></Field>
    <div className="ai-context-card">
      <strong>{spec?.name || "Choose an API in the top bar"} {spec && "· revision " + spec.revision}</strong>
      <span>{operation ? operation + " and shared components" : "Entire saved contract"} → {connector?.baseUrl || "select destination"}</span>
      <label><input type="checkbox" disabled={!spec || !connector || busy} checked={consent === key} onChange={e => setConsent(e.target.checked ? key : "")} />
        Confirm sending this saved context to this provider
      </label>
    </div>
    <div className="toolbar">
      <button className="primary" disabled={busy || !spec || !connector || consent !== key || !instruction.trim()} onClick={() => void generate()}>
        {busy ? "Working…" : "Generate review draft"}
      </button>
      <button onClick={() => setPage("Integration Hub")}>Manage providers</button>
      <button onClick={() => setPage("Test Library")}>Generate test drafts</button>
      {drafts.length > 0 && <select aria-label="Saved AI drafts" value={draft?.id || ""} onChange={e => {
        const selected = drafts.find(item => item.id === e.target.value);
        if (selected) open(selected);
      }}>
        <option value="">Open a saved review draft</option>
        {drafts.map(item => <option value={item.id} key={item.id}>{item.specName} · {item.kind} · {item.status}</option>)}
      </select>}
    </div>
    {error && <p role="alert" className="error">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {draft && <div className="ai-review-detail">
      <h3>{kinds.find(([value]) => value === draft.kind)?.[1]} <Badge>{draft.status}</Badge></h3>
      <p className="muted">{draft.specName} · revision {draft.specRevision} · {draft.provider}</p>
      {stale && draft.status === "DRAFT" && <p className="error">Select the original API revision or generate a fresh draft.</p>}
      <Field label="Editable artifact JSON"><textarea className="code tall" value={text} readOnly={draft.status !== "DRAFT"} onChange={e => setText(e.target.value)} /></Field>
      {draft.kind === "contract" && !stale && spec?.document && artifact.paths && <ContractCompare source={text} document={artifact} contracts={[spec]} currentId={spec.id} />}
      {diagramError && <p className="error">{diagramError}</p>}
      {diagramSvg && <div className="diagram" aria-label="AI diagram preview" dangerouslySetInnerHTML={{__html: diagramSvg}} />}
      {draft.kind === "story" && artifact.summary && <div className="ai-context-card"><strong>{String(artifact.summary)}</strong>
        <p>{String(artifact.description || "")}</p>
        <ul>{Array.isArray(artifact.acceptanceCriteria) && artifact.acceptanceCriteria.map((value: unknown, index: number) => <li key={index}>{String(value)}</li>)}</ul>
      </div>}
      {draft.kind === "story" && draft.status === "ACCEPTED" && (draft.jiraProposalId
        ? <button onClick={() => setPage("Integration Hub")}>Open approval queue in Integration Hub</button>
        : <div className="toolbar">
          <select aria-label="Jira story connector" value={jiraConnector} onChange={e => setJiraConnector(e.target.value)}>
            <option value="">Choose Jira connection</option>
            {(data.integrations || []).filter(item => item.enabled && item.provider === "jira").map(item =>
              <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
          <input aria-label="Jira project key" placeholder="Project key" value={jiraProject} onChange={e => setJiraProject(e.target.value)} />
          <button disabled={busy || !jiraConnector || !jiraProject.trim()} onClick={() => void proposeStory()}>Propose Jira issue</button>
        </div>)}
      <button className="primary" disabled={busy || !!stale || draft.status !== "DRAFT" || !Object.keys(artifact).length} onClick={() => void accept()}>Apply reviewed draft</button>
    </div>}
  </Panel>;
}
