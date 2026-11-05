import { useState } from "react";
import { ChevronDown, Lock } from "lucide-react";
import { post, pretty, type Row } from "./api";
import { Media, Schema } from "./EndpointBrowser";
import { listOperations, mediaExamples, object, operationParameters, resolve, type Operation } from "./endpoint-model";
import "./swagger-preview.css";

type Run = (fn: () => Promise<unknown>) => Promise<unknown>;
const text = (value: unknown) => typeof value === "string" ? value : "";

function serverUrl(server: Row) {
  let url = text(server.url);
  for (const [name, variable] of Object.entries(object(server.variables))) {
    url = url.replaceAll("{" + name + "}", String(object(variable).default ?? ""));
  }
  return url;
}

function OperationDetails({ item, document, server, run }: {
  item: Operation;
  document: Row;
  server: string;
  run: Run;
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
  const authenticated = Array.isArray(requirements) && requirements.some(group => Object.keys(object(group)).length);
  const [authorization, setAuthorization] = useState("");
  const operationServer = (item.operation.servers ?? item.pathItem.servers)?.[0];
  const base = operationServer ? serverUrl(object(operationServer)) : server;
  const supported = /^https?:\/\//i.test(base) && item.method !== "TRACE";

  function exampleBody(type: string) {
    const value = mediaExamples(object(content[type]), document, "request")[0]?.value;
    return value === undefined ? "" : typeof value === "string" && !type.includes("json") ? value : pretty(value);
  }

  async function execute() {
    setBusy(true);
    setResult(undefined);
    try {
      await run(async () => {
        let path = item.path;
        const headers: Record<string, string> = Object.create(null);
        const query = new URLSearchParams();
        for (const parameter of parameters) {
          const name = text(parameter.name);
          const value = values[parameter.in + ":" + name];
          if (!value && (parameter.required || parameter.in === "path")) {
            throw new Error("Enter the required " + parameter.in + " parameter " + name);
          }
          if (!value) continue;
          if (parameter.in === "path") path = path.replaceAll("{" + name + "}", encodeURIComponent(value));
          if (parameter.in === "query") query.append(name, value);
          if (parameter.in === "header") headers[name] = value;
        }
        const url = new URL(base.replace(/\/$/, "") + path);
        query.forEach((value, name) => url.searchParams.append(name, value));
        if (authorization.trim()) headers.Authorization = authorization.trim();
        if (media && body) headers["Content-Type"] = media;
        setResult(await post("/client/send", { method: item.method, url: url.href, headers, body }));
      });
    } finally {
      setBusy(false);
    }
  }

  return <div className="swagger-operation-body">
    {text(item.operation.description) && <p className="swagger-description">{item.operation.description}</p>}
    <div className="swagger-section-bar">
      <h4>Parameters</h4>
      <button type="button" disabled={!supported} title={supported ? undefined : "An absolute HTTP(S) server and supported method are required"}
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
            {editing && parameter.in && parameter.in !== "cookie" && <input aria-label={name + " " + parameter.in + " parameter"}
              value={values[key] || ""} placeholder={String(parameter.example ?? schema.default ?? name)}
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
        </> : <Media content={content} document={document} mode="request" />}
      </div>
    </>}
    {editing && <div className="swagger-padded">
      {authenticated && <label>Authorization <input type="password" autoComplete="off" value={authorization}
        placeholder="Bearer …" onChange={event => setAuthorization(event.target.value)} /></label>}
      <button type="button" className="swagger-execute" disabled={busy} onClick={() => void execute()}>{busy ? "Executing…" : "Execute"}</button>
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
          {response.content && <Media content={response.content} document={document} mode="response" />}
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

export default function SwaggerPreview({ document, run }: { document: Row; run: Run }) {
  const [filter, setFilter] = useState("");
  const [selectedServer, setSelectedServer] = useState("");
  const servers = Array.isArray(document.servers) ? document.servers.map(value => serverUrl(object(value))).filter(Boolean) : [];
  const server = servers.includes(selectedServer) ? selectedServer : servers[0] || "";
  const tags = new Map<string, Operation[]>();
  for (const item of listOperations(document)) {
    const names = Array.isArray(item.operation.tags) ? item.operation.tags.filter((tag: unknown) => typeof tag === "string") : [];
    for (const tag of names.length ? names : ["default"]) {
      if (!(tag + " " + item.key + " " + text(item.operation.summary)).toLowerCase().includes(filter.trim().toLowerCase())) continue;
      const group = tags.get(tag) ?? [];
      group.push(item);
      tags.set(tag, group);
    }
  }
  const schemas = Object.entries(object(document.components?.schemas));
  return <div className="swagger-preview" aria-label="Swagger-style API documentation">
    <div className="swagger-info">
      <div className="swagger-title"><h2>{text(document.info?.title) || "API documentation"}</h2>
        {document.info?.version && <span className="swagger-version">{String(document.info.version)}</span>}
        <span className="swagger-version swagger-oas">OAS {String(document.openapi).split(".").slice(0, 2).join(".")}</span>
      </div>
      {text(document.info?.description) && <p className="swagger-description">{document.info.description}</p>}
    </div>
    {servers.length > 0 && <div className="swagger-servers">
      <label>Servers<select aria-label="Swagger server" value={server} onChange={event => setSelectedServer(event.target.value)}>
        {servers.map((url, index) => <option key={url + index}>{url}</option>)}
      </select></label>
    </div>}
    <div className="swagger-operations">
      <input aria-label="Filter Swagger operations" placeholder="Filter by tag, method or path" value={filter} onChange={event => setFilter(event.target.value)} />
      {tags.size === 0 && <p>No matching operations</p>}
      {[...tags].map(([tag, items]) => <details key={tag} className="swagger-tag" open>
        <summary><strong>{tag}</strong><span>{text(Array.isArray(document.tags) && document.tags.find((entry: Row) => entry?.name === tag)?.description)}</span><ChevronDown size={20} /></summary>
        {items.map(item => <OperationBlock key={item.key} item={item} document={document} server={server} run={run} />)}
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

function OperationBlock({ item, document, server, run }: Parameters<typeof OperationDetails>[0]) {
  const [open, setOpen] = useState(false);
  const security = item.operation.security ?? document.security;
  const locked = Array.isArray(security) && security.some(group => Object.keys(object(group)).length);
  return <details className={"swagger-operation swagger-" + item.method.toLowerCase()} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary aria-label={"Expand " + item.key}>
      <span className="swagger-method">{item.method}</span>
      <code>{item.path}</code><span className="swagger-summary">{text(item.operation.summary)}</span>
      {locked && <Lock size={15} aria-label="Authorization required" />}<ChevronDown size={18} />
    </summary>
    {open && <OperationDetails item={item} document={document} server={server} run={run} />}
  </details>;
}
