import { useState } from "react";
import { Download } from "lucide-react";
import { download, post, pretty, type Row } from "./api";
import { Badge, Panel } from "./components";
import { listOperations } from "./endpoint-model";
import { documentChanges } from "./ContractCompare";

export function RevisionEvidence({ spec, collections, tests, runs, openOperation }: {
  spec: Row; collections: Row[]; tests: Row[]; runs: Row[]; openOperation: (operation: string) => void;
}) {
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const linked = collections.filter(item => item.sourceSpecId === spec.id &&
    (spec.contractHash ? item.sourceContractHash === spec.contractHash : item.sourceSpecRevision === spec.revision));
  const cases = tests.filter(item => item.specId === spec.id &&
    (spec.contractHash ? item.contractHash === spec.contractHash : item.specRevision === spec.revision));
  const byOperation = listOperations(spec.document).map(operation => {
    const matching = cases.filter(test => test.operation === operation.key);
    const evidence = runs.filter(run => matching.some(test => test.id === run.caseId && test.revision === run.caseRevision));
    return { operation: operation.key, cases: matching.length, runs: evidence.length, latest: evidence[0]?.status || "Not run" };
  });
  async function exportReview() {
    setBusy(true);
    setError("");
    try {
      const lint: Row[] = await post("/workspace/lint", {source: spec.source || pretty(spec.document)});
      download("contract-review-r" + spec.revision + ".json", pretty({
        contract: {name: spec.name, version: spec.version, revision: spec.revision, status: spec.status},
        linkedCollections: linked.map(collection => collection.name), coverage: byOperation,
        findings: lint.map(finding => ({severity: finding.severity, path: finding.path, message: finding.message})),
      }), "application/json");
    } catch (e) {setError(String(e));}
    finally {setBusy(false);}
  }
  return <Panel title="Revision evidence" action={<button disabled={busy} onClick={() => void exportReview()}><Download size={14} /> Export review summary</button>}>
    <p className="muted">{linked.length} linked collections · {cases.length} linked cases · {byOperation.filter(item => !item.cases).length} operations without cases</p>
    {error && <p className="error" role="alert">{error}</p>}
    <div className="evidence-list">
      {byOperation.map(item => <button key={item.operation} onClick={() => openOperation(item.operation)}>
        <code>{item.operation}</code><span>{item.cases} cases · {item.runs} runs</span><Badge>{item.latest}</Badge>
      </button>)}
    </div>
  </Panel>;
}

export function RunComparison({ runs }: {runs: Row[]}) {
  const [baselineId, setBaselineId] = useState("");
  const [currentId, setCurrentId] = useState("");
  const baseline = runs.find(run => run.id === baselineId);
  const current = runs.find(run => run.id === currentId && run.caseId === baseline?.caseId);
  const candidates = runs.filter(run => run.caseId === baseline?.caseId && run.id !== baselineId);
  const parseBody = (value: unknown) => {
    if (typeof value !== "string") return value;
    try {return JSON.parse(value);} catch {return value;}
  };
  const changes = baseline && current ? documentChanges(parseBody(baseline.response?.body), parseBody(current.response?.body)) : [];
  return <details className="run-compare">
    <summary>Compare two executions</summary>
    <div className="toolbar">
      <select aria-label="Baseline execution" value={baselineId} onChange={event => {setBaselineId(event.target.value);setCurrentId("");}}>
        <option value="">Choose baseline run</option>
        {runs.map(run => <option key={run.id} value={run.id}>{run.status} · {run.updatedAt || run.id}</option>)}
      </select>
      <select aria-label="Comparison execution" disabled={!baseline} value={currentId} onChange={event => setCurrentId(event.target.value)}>
        <option value="">Choose another run of the same case</option>
        {candidates.map(run => <option key={run.id} value={run.id}>{run.status} · {run.updatedAt || run.id}</option>)}
      </select>
    </div>
    {baseline && current && <>
      <p>HTTP {baseline.response?.status} → {current.response?.status} · {baseline.status} → {current.status} · {changes.length} body changes</p>
      <details><summary>Inspect body changes</summary><pre className="code output">{pretty(changes.slice(0, 100))}</pre></details>
    </>}
  </details>;
}
