import { useState } from "react";
import { request, type Row } from "./api";
import { Field, Panel } from "./components";

const metrics = [
  ["platformRequests", "Platform requests"],
  ["outboundAttempts", "Upstream outcomes"],
  ["platformUnauthorized", "Platform 401s"],
  ["upstreamUnauthorized", "Upstream 401s"],
  ["blockedBeforeSend", "Blocked before send"],
  ["transportFailures", "Transport failures"],
];

export default function UsageHistory() {
  const [requestId, setRequestId] = useState("");
  const [activeFilter, setActiveFilter] = useState("");
  const [result, setResult] = useState<Row>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function load(id: string, beforeId = 0) {
    setBusy(true);
    setError("");
    try {
      const query = new URLSearchParams({limit: "50", beforeId: String(beforeId)});
      if (id.trim()) query.set("requestId", id.trim());
      setResult(await request("/usage?" + query.toString()));
      setActiveFilter(id);
    } catch (failure) {
      setError(String(failure));
    } finally {
      setBusy(false);
    }
  }
  const events: Row[] = result?.events || [];
  return <Panel title="Request and integration usage">
    <p className="muted">Search by the request ID from the response panel or IntelliJ Run console. Results contain diagnostic metadata.</p>
    <form className="toolbar" onSubmit={event => {event.preventDefault();void load(requestId);}}>
      <Field label="Usage request ID"><input value={requestId} onChange={event => setRequestId(event.target.value)} placeholder="Optional request ID" /></Field>
      <button className="primary" disabled={busy}>{busy ? "Loading…" : "Load usage"}</button>
    </form>
    {error && <p className="error" role="alert">{error}</p>}
    {result && <>
      <div className="usage-summary">{metrics.map(([key, title]) => <div key={key}>
        <small>{title}</small><strong>{result.totals?.[key] || 0}</strong>
      </div>)}</div>
      {events.length > 0 ? <div className="usage-table">
        <table><thead><tr><th>When</th><th>Request ID</th><th>Layer</th><th>Source</th><th>Host</th><th>Status</th><th>Diagnostic</th><th>Time</th></tr></thead>
          <tbody>{events.map(event => <tr key={event.id}>
            <td>{new Date(event.createdAt).toLocaleString()}</td>
            <td><button type="button" onClick={() => {setRequestId(event.requestId);void load(event.requestId);}} disabled={busy}><code>{event.requestId}</code></button></td>
            <td>{event.layer}</td><td>{event.method} {event.source}</td><td>{event.host || "—"}</td>
            <td>{event.status || "—"}</td><td>{event.reason}</td><td>{event.durationMs} ms</td>
          </tr>)}</tbody>
        </table>
      </div> : <p className="muted">No events match this request ID.</p>}
      {events.length === 50 && <button disabled={busy} onClick={() => void load(activeFilter, events.at(-1)?.id)}>Older events</button>}
    </>}
  </Panel>;
}
