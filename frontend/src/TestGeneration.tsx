import { useEffect, useState } from "react";
import { Sparkles, Layers, ShieldCheck } from "lucide-react";
import { Panel, Badge } from "./components";
import { post, pretty, download, type Row } from "./api";
import type { PageProps } from "./App";
export default function TestGeneration({
  data,
  spec,
  reload,
}: {
  data: PageProps["data"];
  spec: PageProps["spec"];
  reload: PageProps["reload"];
}) {
  const [engine, setEngine] = useState("local"),
    [consent, setConsent] = useState(false),
    [busy, setBusy] = useState(false),
    [preview, setPreview] = useState<Row | null>(null),
    [text, setText] = useState(""),
    [error, setError] = useState(""),
    [result, setResult] = useState<Row | null>(null);
  const connection = data.integrations?.find((c) => c.id === engine);
  useEffect(() => {
    setPreview(null);
    setText("");
    setResult(null);
    setConsent(false);
    setError("");
  }, [spec?.id, spec?.revision, engine, connection?.revision]);
  async function generate() {
    if (!spec) return;
    setBusy(true);
    setError("");
    setResult(null);
    try {
      if (engine === "local") {
        const r = await post(`/tests/generate/${spec.id}/package`);
        await reload();
        setResult(r);
      } else {
        const p = await post(`/tests/generate/${spec.id}/preview`, {
          integrationId: engine,
          consent,
          specRevision: spec.revision,
          integrationRevision: connection?.revision,
        });
        setPreview(p);
        setConsent(false);
        setText(pretty(p.bundle));
      }
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }
  async function accept() {
    if (!spec || !preview) return;
    setBusy(true);
    setError("");
    try {
      const saved = await post(`/tests/generate/${spec.id}/accept`, {
        ...preview,
        bundle: JSON.parse(text),
      });
      await reload();
      setPreview(null);
      setText("");
      setResult(saved);
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Panel
      title="Generate a test package"
      action={<Badge tone="purple">API WORKSPACE → TEST LIBRARY</Badge>}
    >
      <div className="test-generation-context">
        <Layers size={20} />
        <div>
          <strong>{spec?.name || "Select an API in the top bar"}</strong>
          <p className="muted">
            Version {spec?.version || "—"} · Saved revision{" "}
            {spec?.revision || "—"} ·{" "}
            {Object.keys(spec?.document?.paths || {}).length} paths
          </p>
        </div>
      </div>
      <p className="muted">
        Uses the saved YAML/JSON from API Workspace. Save editor changes first.
        Cases, suites and plans stay linked to this API; runs appear only after
        execution.
      </p>
      <div className="toolbar">
        <select
          aria-label="Test generation provider"
          value={engine}
          disabled={busy}
          onChange={(e) => setEngine(e.target.value)}
        >
          <option value="local">Built-in · no AI credentials</option>
          {(data.integrations || [])
            .filter((c) => c.enabled && c.testGenerationEnabled)
            .map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} · AI
              </option>
            ))}
        </select>
        <button
          className="primary"
          disabled={
            busy || !spec?.document?.paths || (engine !== "local" && !consent)
          }
          onClick={() => void generate()}
        >
          <Sparkles size={15} />
          {busy
            ? "Working…"
            : engine === "local"
              ? "Generate cases, suites & plan"
              : "Preview AI test package"}
        </button>
      </div>
      {engine !== "local" && (
        <label className="test-generation-consent">
          <input
            type="checkbox"
            checked={consent}
            disabled={busy}
            onChange={(e) => setConsent(e.target.checked)}
          />{" "}
          Send {spec?.name || "the selected contract"} revision {spec?.revision || "—"} to {connection?.name} at {connection?.baseUrl}.
        </label>
      )}
      <p className="muted test-generation-note">
        Configure Friday, Stylus or a Microsoft-approved gateway in Integration
        Hub and enable test generation. Enterprise chat websites and SSO
        sessions are not used automatically.
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {preview && (
        <div className="test-generation-preview">
          <div className="toolbar spread">
            <h3>Review proposed package</h3>
            <Badge>NOT SAVED · NOT EXECUTED</Badge>
          </div>
          <p className="muted">
            Review payloads and expected results. Operations and case/suite
            references are validated against the selected contract.
          </p>
          <div className="ai-context-card">
            <strong>{preview.bundle?.cases?.length || 0} proposed cases</strong>
            <ul>{(preview.bundle?.cases || []).slice(0, 12).map((item: Row) =>
              <li key={item.key}><code>{item.operation}</code> · {item.title}</li>)}</ul>
          </div>
          <textarea
            aria-label="Generated test package"
            className="code tall"
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
          <div className="toolbar">
            <button
              className="primary"
              disabled={busy}
              onClick={() => void accept()}
            >
              <ShieldCheck size={15} /> Save reviewed package
            </button>
            <button
              disabled={busy}
              onClick={() => {
                setPreview(null);
                setText("");
              }}
            >
              Discard preview
            </button>
            <button
              onClick={() => download("proposed-test-package.json", text)}
            >
              Download draft
            </button>
          </div>
        </div>
      )}
      {result && (
        <div className="test-generation-summary" role="status">
          <strong>Package saved</strong>
          <span>{result.cases?.length || 0} cases</span>
          <span>{result.suites?.length || 0} suites</span>
          <span>{result.plans?.length || (result.plan ? 1 : 0)} plans</span>
          <span>No runs created before execution</span>
        </div>
      )}
    </Panel>
  );
}
