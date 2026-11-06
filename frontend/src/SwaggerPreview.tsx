import { useEffect, useMemo, useState } from "react";
import { ChevronDown, Lock } from "lucide-react";
import { post, pretty, type Row } from "./api";
import { Media, Schema } from "./EndpointBrowser";
import { explorerModel, mediaExamples, object, operationParameters, operationServers, resolve, type Operation } from "./endpoint-model";
import "./swagger-preview.css";

const text = (value: unknown) => typeof value === "string" ? value : "";

type ExecutionContext = {
  spec?: Row;
  profiles: Row[];
  enabled: boolean;
};

function OperationDetails({ item, document, execution }: {
  item: Operation;
  document: Row;
  execution: ExecutionContext;
}) {
  const parameters = operationParameters(item, document);
  const request = resolve(item.operation.requestBody, document);
  const content = object(request.content);
  const [editing, setEditing] = useState(false);
  const [values, setValues] = useState<Record<string, string>>({});
  const [media, setMedia] = useState(Object.keys(content)[0] || "");
  const [body, setBody] = useState(() => exampleBody(Object.keys(content)[0]));
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<Row>();
  const requirements = item.operation.security ?? document.security;
  const [environmentId, setEnvironmentId] = useState("");
  const [securityIndex, setSecurityIndex] = useState(0);
  const [credentials, setCredentials] = useState<Record<string, {secret?: string; username?: string}>>({});
  const [serverIndex, setServerIndex] = useState(0);
  const [error, setError] = useState("");
  const security = Array.isArray(requirements) ? requirements : [];
  const schemes = Object.keys(object(security[securityIndex]));
  const profile = execution.profiles.find(entry => entry.id === environmentId);
  const variables: Row[] = (profile?.values || []).filter((entry: Row) => entry.enabled && entry.hasValue);
  const servers = operationServers(item, document);
  const base = servers[serverIndex]?.url || "";
  const available = execution.enabled && servers.some(entry => /^https?:\/\//i.test(entry.url)) && item.method !== "TRACE";
  const supported = available && /^https?:\/\//i.test(base);
  function updateCredential(name: string, field: "secret" | "username", value: string) {
    setCredentials(current => ({ ...current, [name]: { ...current[name], [field]: value } }));
  }

  function exampleBody(type: string) {
    const value = mediaExamples(object(content[type]), document, "request")[0]?.value;
    return value === undefined ? "" : typeof value === "string" && !type.includes("json") ? value : pretty(value);
  }

  async function execute() {
    if (!supported || !execution.spec) return;
    setBusy(true);
    setError("");
    setResult(undefined);
    try {
      const supplied: Record<string, unknown> = {};
      for (const parameter of parameters) {
        const key = parameter.in + ":" + parameter.name;
        const value = values[key];
        if (!value?.trim()) continue;
        const schema = resolve(parameter.schema, document);
        supplied[key] = ["array", "object"].includes(schema.type) ? JSON.parse(value) : value;
      }
      const spec = execution.spec;
      const response = await post("/workspace/" + encodeURIComponent(spec.id) + "/operations/execute", {
        expectedRevision: spec.revision, operation: item.key, serverIndex, parameters: supplied,
        mediaType: media, body, environmentId, securityIndex, credentials,
      });
      setResult(response);
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  return <div className="swagger-operation-body">
    {text(item.operation.description) && <p className="swagger-description">{item.operation.description}</p>}
    <div className="swagger-section-bar">
      <h4>Parameters</h4>
      <button type="button" disabled={!available} title={available ? undefined : "Use the latest saved contract with an absolute HTTP(S) server and supported method"}
        onClick={() => setEditing(!editing)}>{editing ? "Cancel" : "Try it out"}</button>
    </div>
    {parameters.length ? <table className="swagger-table">
      <thead><tr><th>Name</th><th>Description</th></tr></thead>
      <tbody>{parameters.map((parameter, index) => {
        const name = text(parameter.name) || text(parameter.$ref);
        const key = parameter.in + ":" + name;
        const schema = resolve(parameter.schema, document);
        return <tr key={key + index}>
          <td><strong>{name}</strong> {parameter.required && <small className="swagger-required">* required</small>}
            <code>{text(schema.type) || "string"}</code><i>({parameter.in || "reference"})</i></td>
          <td>{text(parameter.description) || text(parameter["x-preview-warning"])}
            {editing && parameter.in && parameter.in !== "cookie" && !/^(authorization|cookie)$/i.test(name) && <input aria-label={name + " " + parameter.in + " parameter"}
              value={values[key] || ""} placeholder={["array", "object"].includes(schema.type) ? "Enter JSON " + schema.type : String(parameter.example ?? schema.default ?? name)}
              onChange={event => setValues({ ...values, [key]: event.target.value })} />}
          </td>
        </tr>;
      })}</tbody>
    </table> : <p className="swagger-padded">No parameters</p>}
    {item.operation.requestBody && <>
      <div className="swagger-section-bar"><h4>Request body {request.required && <small className="swagger-required">required</small>}</h4></div>
      <div className="swagger-padded">
        {text(request.description) && <p>{request.description}</p>}
        {editing && Object.keys(content).length ? <>
          <select aria-label="Request media type" value={media} onChange={event => {
            setMedia(event.target.value);
            setBody(exampleBody(event.target.value));
          }}>{Object.keys(content).map(type => <option key={type}>{type}</option>)}</select>
          <textarea aria-label="Swagger request body" value={body} onChange={event => setBody(event.target.value)} />
        </> : <Media content={content} document={document} mode="request" tabs />}
      </div>
    </>}
    {editing && <div className="swagger-padded swagger-execution">
      <label>Server<select aria-label="Operation server" value={serverIndex} onChange={e => setServerIndex(Number(e.target.value))}>
        {servers.map(entry => <option key={entry.index} value={entry.index}>{entry.url} {entry.description}</option>)}
      </select></label>
      {security.length > 0 && <label>Security alternative<select value={securityIndex} onChange={e => setSecurityIndex(Number(e.target.value))}>
        {security.map((alternative, index) => <option key={index} value={index}>{Object.keys(object(alternative)).join(" + ") || "Anonymous"}</option>)}
      </select></label>}
      {schemes.length > 0 && <label>Credential environment<select value={environmentId} onChange={e => {setEnvironmentId(e.target.value);setCredentials({});}}>
        <option value="">Select a saved environment</option>
        {execution.profiles.map(entry => <option key={entry.id} value={entry.id}>{entry.name}</option>)}
      </select></label>}
      {schemes.map(name => {
        const scheme = resolve(document.components?.securitySchemes?.[name], document);
        return <fieldset key={name}><legend>{name} · {scheme.scheme || scheme.type || "unresolved"}</legend>
          {scheme.scheme === "basic" && <label>Username variable<select value={credentials[name]?.username || ""} onChange={e => updateCredential(name, "username", e.target.value)}>
            <option value="">Select variable</option>
            {variables.map(v => <option key={v.key}>{v.key}</option>)}
          </select></label>}
          <label>Secret variable<select value={credentials[name]?.secret || ""} onChange={e => updateCredential(name, "secret", e.target.value)}>
            <option value="">Select vault variable</option>
            {variables.filter(v => v.secret).map(v => <option key={v.key}>{v.key}</option>)}
          </select></label>
        </fieldset>;
      })}
      <div className="swagger-request-preview"><small>Request preview · credentials resolved by Spring Boot</small><code>{item.method} {base}{item.path}</code></div>
      {error && <p role="alert" className="error">{error}</p>}
      <button type="button" className="swagger-execute" disabled={busy || !supported || schemes.some(name => !credentials[name]?.secret)} onClick={() => void execute()}>{busy ? "Executing…" : "Execute"}</button>
    </div>}
    <div className="swagger-section-bar"><h4>Responses</h4></div>
    {result && <div className="swagger-padded"><h4>Server response</h4>
      <p>{result.status || "Request failed"} · {result.durationMs} ms</p>
      <pre>{result.body || result.error}</pre>
      {result.headers && <details><summary>Response headers</summary><pre>{pretty(result.headers)}</pre></details>}
    </div>}
    <table className="swagger-table">
      <thead><tr><th>Code</th><th>Description</th><th>Links</th></tr></thead>
      <tbody>{Object.entries(object(item.operation.responses)).map(([status, value]) => {
        const response = resolve(value, document);
        return <tr key={status}><td>{status}</td><td>
          <p>{text(response.description) || text(response["x-preview-warning"])}</p>
          {response.content && <Media content={response.content} document={document} mode="response" tabs />}
          {response.headers && <details><summary>Headers</summary>
            {Object.entries(object(response.headers)).map(([name, value]) => {
              const header = resolve(value, document);
              return <div key={name}><strong>{name}</strong><p>{text(header.description)}</p>
                <Schema value={header.schema} document={document} name={name} /></div>;
            })}
          </details>}
        </td><td>{Object.keys(object(response.links)).join(", ") || "No links"}</td></tr>;
      })}</tbody>
    </table>
  </div>;
}

export default function SwaggerPreview({ document, execution, focus }: { document: Row; execution: ExecutionContext; focus?: {operation: string} }) {
  const [filter, setFilter] = useState("");
  const [active, setActive] = useState(() => new URLSearchParams(window.location.search).get("operation") || "");
  useEffect(() => { if (focus) { setFilter(""); setActive(focus.operation); } }, [focus]);
  const model = useMemo(() => explorerModel(document), [document]);
  const query = filter.trim().toLowerCase();
  const tags = [...model.tags].map(([tag, items]) => [tag, items.filter(item =>
    (tag + " " + item.key + " " + text(item.operation.summary)).toLowerCase().includes(query))] as const).filter(([, items]) => items.length > 0);
  const schemas = model.schemas;
  const executionKey = (execution.spec?.id || "draft") + ":" + (execution.spec?.revision || 0) + ":" + execution.enabled;
  function toggle(key: string) {
    setActive(key);
    const url = new URL(window.location.href);
    if (key) url.searchParams.set("operation", key);
    else url.searchParams.delete("operation");
    window.history.replaceState(null, "", url);
  }
  return <div className="swagger-preview" aria-label="Swagger-style API documentation">
    <div className="swagger-info">
      <div className="swagger-title"><h2>{text(document.info?.title) || "API documentation"}</h2>
        {document.info?.version && <span className="swagger-version">{String(document.info.version)}</span>}
        <span className="swagger-version swagger-oas">OAS {String(document.openapi).split(".").slice(0, 2).join(".")}</span>
      </div>
      {text(document.info?.description) && <p className="swagger-description">{document.info.description}</p>}
    </div>
    <div className="swagger-operations">
      <input aria-label="Filter Swagger operations" placeholder="Filter by tag, method or path" value={filter} onChange={event => setFilter(event.target.value)} />
      {tags.length === 0 && <p>No matching operations</p>}
      {[...tags].map(([tag, items]) => <details key={tag} className="swagger-tag" open>
        <summary><strong>{tag}</strong><span>{text(Array.isArray(document.tags) && document.tags.find((entry: Row) => entry?.name === tag)?.description)}</span><ChevronDown size={20} /></summary>
        {items.map(item => <OperationBlock key={item.key} item={item} document={document} execution={execution} open={active === item.key} toggle={toggle} executionKey={executionKey} />)}
      </details>)}
      {schemas.length > 0 && <details className="swagger-schemas" open>
        <summary>Schemas <ChevronDown size={18} /></summary>
        {schemas.map(([name, value]) => <details key={name} className="swagger-schema">
          <summary>{name} <ChevronDown size={15} /></summary>
          <Schema value={value} document={document} name={name} />
        </details>)}
      </details>}
    </div>
  </div>;
}

function OperationBlock({ item, document, execution, open, toggle, executionKey }:
  Parameters<typeof OperationDetails>[0] & { open: boolean; toggle: (key: string) => void; executionKey: string }) {
  const security = item.operation.security ?? document.security;
  const locked = Array.isArray(security) && security.some(group => Object.keys(object(group)).length);
  return <details className={"swagger-operation swagger-" + item.method.toLowerCase()} open={open}>
    <summary aria-label={"Expand " + item.key} onClick={event => {event.preventDefault();toggle(open ? "" : item.key);}}>
      <span className="swagger-method">{item.method}</span>
      <code>{item.path}</code><span className="swagger-summary">{text(item.operation.summary)}</span>
      {locked && <Lock size={15} aria-label="Authorization required" />}<ChevronDown size={18} />
    </summary>
    {open && <OperationDetails key={executionKey} item={item} document={document} execution={execution} />}
  </details>;
}
