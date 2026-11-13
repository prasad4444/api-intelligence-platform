import { useEffect, useState, useCallback, useRef } from "react";
import {
  Activity,
  LayoutDashboard,
  Braces,
  Github,
  Send,
  Columns3,
  Users,
  FlaskConical,
  ShieldCheck,
  Network,
  Sparkles,
  Wrench,
  Settings,
  Search,
  Sun,
  Moon,
  LogOut,
  PlugZap,
  ArrowUpRight,
  Command,
  RefreshCw,
} from "lucide-react";
import { request, post, save, resetCsrf, pretty, type Row } from "./api";
import { listOperations } from "./endpoint-model";
import { Badge, Panel, Editor, Records, Field } from "./components";
import {
  Workspace,
  Client,
  TestLibrary,
  Capacity,
  Jira,
  Architecture,
  Copilot,
  Utilities,
  Integrations,
  GitHubPage,
  Admin,
} from "./pages";
const nav = [
  ["Dashboard", LayoutDashboard],
  ["API Workspace", Braces],
  ["Request Studio", Send],
  ["Test Library", FlaskConical],
  ["GitHub", Github],
  ["Jira", Columns3],
  ["Team Capacity", Users],
  ["Governance", ShieldCheck],
  ["Architecture", Network],
  ["AI Copilot", Sparkles],
  ["Dev Utilities", Wrench],
  ["Integration Hub", PlugZap],
  ["Admin", Settings],
] as const;
const descriptions: Record<string, string> = {
  Dashboard: "Your delivery health, connected.",
  "API Workspace": "Design, review and evolve your API contracts.",
  "Request Studio": "Build requests. Validate behavior. Ship with confidence.",
  "Test Library": "Reusable cases with version history and execution evidence.",
  GitHub: "Explore repositories and approve changes before they run.",
  Jira: "Turn delivery signals into a focused sprint.",
  "Team Capacity": "Plan with real availability across regions and roles.",
  Governance: "Keep every contract measurable and reviewable.",
  Architecture: "Make the system visible, one diagram at a time.",
  "AI Copilot": "Bring your own intelligence. Keep your context.",
  "Dev Utilities": "The small tools that keep engineering moving.",
  "Integration Hub":
    "One control plane for your tools, credentials and agents.",
  Admin: "Access policies, encrypted secrets and traceable changes.",
};
const pageKinds: Record<string, string[]> = {
  Dashboard: ["tests", "runs", "stories", "integrations", "events", "connections"],
  "API Workspace": ["environments", "collections", "tests", "runs"],
  "Request Studio": ["environments", "collections", "history"],
  "Test Library": ["environments", "tests", "suites", "plans", "runs", "integrations", "connections"],
  "Team Capacity": ["team", "sprints", "leaves", "holidays", "stories"],
  Jira: ["stories", "runs", "integrations", "connections"],
  Governance: ["rules", "governance-runs"],
  Architecture: ["diagrams"],
  "AI Copilot": ["integrations", "ai-drafts", "runs", "connections"],
  "Integration Hub": ["integrations", "events", "connections"],
  GitHub: ["integrations", "connections"],
  Admin: [],
};
export type PageProps = {
  data: Record<string, Row[]>;
  reload: (kinds?: string[]) => Promise<void>;
  run: (fn: () => Promise<unknown>, kinds?: string[]) => Promise<any>;
  edit: (kind: string, row: Row) => void;
  spec: Row | undefined;
  setPage: (s: string) => void;
  selectSpec?: (id: string) => void;
  roles?: string[];
  explorerFocus?: {operation: string};
};
export default function App() {
  const [user, setUser] = useState<Row | null>(null),
    [checking, setChecking] = useState(true),
    [page, setPage] = useState("Dashboard"),
    [data, setData] = useState<Record<string, Row[]>>({}),
    [specId, setSpecId] = useState(""),
    [theme, setTheme] = useState(localStorage.getItem("theme") || "system"),
    [explorerFocus, setExplorerFocus] = useState<{operation: string}>(),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [editor, setEditor] = useState<{ kind: string; row: Row } | null>(null),
    [palette, setPalette] = useState(false),
    [query, setQuery] = useState("");
  useEffect(() => { setNotice(""); }, [page]);
  useEffect(() => {
    request("/me")
      .then(setUser)
      .catch(() => {})
      .finally(() => setChecking(false));
  }, []);
  useEffect(() => {
    const prefersDark = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => document.documentElement.classList.toggle("dark", theme === "dark" || theme === "system" && prefersDark.matches);
    update();
    prefersDark.addEventListener("change", update);
    localStorage.setItem("theme", theme);
    return () => prefersDark.removeEventListener("change", update);
  }, [theme]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "k") {
        e.preventDefault();
        setPalette((p) => !p);
      }
      if (e.key === "Escape") setPalette(false);
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, []);
  const fetchSequence = useRef<Record<string, number>>({});
  const sessionSequence = useRef(0);
  const reload = useCallback(async (kinds = ["specs", ...(pageKinds[page] || [])]) => {
    const session = sessionSequence.current;
    const entries = await Promise.all([...new Set(kinds)].map(async kind => {
      const sequence = (fetchSequence.current[kind] || 0) + 1;
      fetchSequence.current[kind] = sequence;
      const rows = await request(
        kind === "environments" || kind === "ai-drafts" ? "/" + kind
          : kind === "connections" ? "/connections"
          : "/records/" + kind
      );
      return {kind, sequence, rows};
    }));
    if (session !== sessionSequence.current) return;
    setData(current => ({
      ...current,
      ...Object.fromEntries(entries.filter(entry => fetchSequence.current[entry.kind] === entry.sequence)
        .map(entry => [entry.kind, entry.rows])),
    }));
  }, [page]);
  useEffect(() => {
    if (user) reload().catch(e => setNotice(String(e)));
  }, [user, reload]);
  async function run(fn: () => Promise<unknown>, kinds?: string[]) {
    setBusy(true);
    setNotice("");
    try {
      const result = await fn();
      if (kinds?.length !== 0) await reload(kinds);
      setNotice("Saved successfully");
      return result;
    } catch (e) {
      setNotice(String(e));
      return undefined;
    } finally {
      setBusy(false);
    }
  }
  if (checking)
    return (
      <div className="login">
        <Activity className="spin" /> Loading workspace…
      </div>
    );
  if (!user) return <Login onLogin={setUser} />;
  const spec =
    (data.specs || []).find((s) => s.id === specId) || (data.specs || [])[0];
  const props: PageProps = {
    data,
    reload,
    run,
    edit: (kind, row) => setEditor({ kind, row }),
    spec,
    setPage,
    selectSpec: setSpecId,
    explorerFocus,
    roles: (user.roles||[]).map((r: Row|string)=>typeof r==="string"?r:r.authority),
  };
  const find = query.trim().toLowerCase();
  const commands = [
    ...nav.map(([label, Icon]) => ({key: label, label, Icon, go: () => setPage(label)})),
    ...listOperations(spec?.document).map(operation => ({
      key: operation.key, label: operation.key + " · " + (operation.operation.summary || "API operation"), Icon: Braces,
      go: () => {setExplorerFocus({operation: operation.key});setPage("API Workspace");},
    })),
    ...(data.tests || []).map(record => ({
      key: record.id, label: (record.title || record.id) + " · Test case", Icon: FlaskConical,
      go: () => {setPage("Test Library");setEditor({kind: "tests", row: record});},
    })),
  ].filter(command => command.label.toLowerCase().includes(find)).slice(0, 24);
  const openCommand = (go: () => void) => {go();setPalette(false);setQuery("");};
  return (
    <div className="app">
      <aside className="sidebar">
        <a className="brand" href="#" onClick={() => setPage("Dashboard")}>
          <span className="brand-mark">
            <Activity size={24} />
          </span>
          <span>
            API Intelligence<small>ENGINEERING WORKSPACE</small>
          </span>
        </a>
        <div className="workspace-pill">
          <span className="avatar">AI</span>
          <div>
            Platform workspace<small>Local · persistent database</small>
          </div>
          <Badge tone="green">LIVE</Badge>
        </div>
        <div className="nav-label">WORKSPACE</div>
        <nav>
          {nav.map(([label, Icon], i) => (
            <button
              key={label}
              aria-label={label}
              className={page === label ? "active" : ""}
              onClick={() => setPage(label)}
            >
              <Icon size={18} />
              <span>{label}</span>
              {i === 11 && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <ShieldCheck size={17} />
          <div>
            Credentials stay encrypted<small>AES-256-GCM vault</small>
          </div>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace <span>/</span> <b>{page}</b>
          </div>
          <div className="top-actions">
            <select
              aria-label="API version"
              value={spec?.id || ""}
              onChange={(e) => setSpecId(e.target.value)}
            >
              {(data.specs || []).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name} · {s.version}
                </option>
              ))}
            </select>
            <button className="search-button" onClick={() => setPalette(true)}>
              <Search size={16} /> Jump to… <kbd>⌘ K</kbd>
            </button>
            <select className="theme-select" aria-label="Theme" value={theme} onChange={event => setTheme(event.target.value)}>
              <option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option>
            </select>
            <span className="avatar small">
              {user.name.slice(0, 2).toUpperCase()}
            </span>
            <button
              aria-label="Log out"
              onClick={async () => {
                await post("/logout");
                resetCsrf();
                sessionSequence.current++;
                setData({});
                setUser(null);
              }}
            >
              <LogOut size={17} />
            </button>
          </div>
        </header>
        <main className={page === "Request Studio" ? "request-studio-page" : undefined}>
          <div className="page-heading">
            <div>
              <div className="eyebrow">BUILD BETTER. SHIP TOGETHER.</div>
              <h1>{page}</h1>
              <p>{descriptions[page]}</p>
            </div>
            <button onClick={() => run(() => reload(), [])}>
              <RefreshCw size={15} className={busy ? "spin" : ""} /> Refresh
            </button>
          </div>
          {notice && (
            <div
              className={
                "notice " + (notice.startsWith("Error") ? "error" : "")
              }
              role="status"
            >
              {notice}
              <button onClick={() => setNotice("")}>Dismiss</button>
            </div>
          )}
          {page === "Dashboard" ? (
            <Dashboard {...props} />
          ) : page === "API Workspace" ? (
            <Workspace {...props} />
          ) : page === "Request Studio" ? (
            <Client {...props} />
          ) : page === "Test Library" ? (
            <TestLibrary {...props} />
          ) : page === "Team Capacity" ? (
            <Capacity {...props} />
          ) : page === "Jira" ? (
            <Jira {...props} />
          ) : page === "Governance" ? (
            <Governance {...props} />
          ) : page === "Architecture" ? (
            <Architecture {...props} />
          ) : page === "AI Copilot" ? (
            <Copilot {...props} />
          ) : page === "Dev Utilities" ? (
            <Utilities />
          ) : page === "Integration Hub" ? (
            <Integrations {...props} />
          ) : page === "GitHub" ? (
            <GitHubPage {...props} />
          ) : (
            <Admin {...props} />
          )}
          <footer className="page-footer">
            <span>
              <span className="status-dot" /> Workspace connected
            </span>
            <span>API Intelligence Platform · v1.0</span>
          </footer>
        </main>
      </div>
      {editor && (
        <Editor
          title={"Edit " + editor.kind}
          value={editor.row}
          onClose={() => setEditor(null)}
          onSave={async (row) => {
            await save(editor.kind, row);
            await reload();
          }}
        />
      )}
      {palette && (
        <div className="modal-backdrop" onClick={() => setPalette(false)}>
          <div
            className="command-palette"
            role="dialog"
            aria-label="Command palette"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="command-input">
              <Command size={20} />
              <input
                autoFocus
                placeholder="Find a page, operation or test…"
                aria-label="Find a workspace item"
                onKeyDown={event => {if (event.key === "Enter" && commands[0]) openCommand(commands[0].go);}}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
              <kbd>ESC</kbd>
            </div>
            <div className="command-results">
              {commands.map(({key, label, Icon, go}) => <button key={key} onClick={() => openCommand(go)}>
                <Icon size={18} />{label}<ArrowUpRight size={16} />
              </button>)}
              {!commands.length && <p className="muted">No matching workspace items.</p>}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
function Login({ onLogin }: { onLogin: (u: Row) => void }) {
  const [name, setName] = useState("admin"),
    [password, setPassword] = useState(""),
    [error, setError] = useState("");
  return (
    <div className="login">
      <div className="login-brand">
        <Activity size={34} /> API Intelligence
      </div>
      <form
        className="login-card"
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            await request("/login", {
              method: "POST",
              headers: { "Content-Type": "application/x-www-form-urlencoded" },
              body: new URLSearchParams({
                username: name,
                password,
              }).toString(),
            });
            resetCsrf();
            onLogin(await request("/me"));
          } catch (error) {
            setError(error instanceof Error ? error.message : "Sign-in failed.");
          }
        }}
      >
        <Badge tone="green">YOUR ENGINEERING CONTROL PLANE</Badge>
        <h1>Welcome to your workspace.</h1>
        <p className="muted">
          Contracts, tests, delivery and AI agents. Connected.
        </p>
        <Field label="Username">
          <input
            autoComplete="username"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <Field label="Password">
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <button className="primary" type="submit">
          Sign in <ArrowUpRight size={18} />
        </button>
        <small>
          Use the account configured by your platform administrator.
        </small>
      </form>
    </div>
  );
}
function Dashboard({ data, setPage, selectSpec }: PageProps) {
  const specs = data.specs || [],
    tests = data.tests || [],
    runs = data.runs || [],
    stories = data.stories || [],
    connections = data.integrations || [];
  const done = stories.filter((s) => s.status === "Done").length;
  const pass = runs.filter((r) => r.status === "PASS").length;
  return (
    <>
      <div className="hero">
        <div>
          <Badge tone="purple">ENGINEERING, IN SYNC</Badge>
          <h2>
            From API idea to
            <br />
            delivery confidence.
          </h2>
          <p>
            Your contracts, quality signals and team capacity in one workspace.
          </p>
          <button className="primary" onClick={() => setPage("API Workspace")}>
            Open API workspace <ArrowUpRight size={16} />
          </button>
        </div>
        <div className="hero-orbit">
          <div className="orbit-ring">
            <Activity size={50} />
          </div>
          <span className="orbit-chip one">
            <Braces size={18} /> Contracts
          </span>
          <span className="orbit-chip two">
            <FlaskConical size={18} /> Quality
          </span>
          <span className="orbit-chip three">
            <PlugZap size={18} /> Agents
          </span>
        </div>
      </div>
      <Panel title="Needs attention">
        <div className="attention-list">
          {specs.filter(record => record.status === "IN_REVIEW").slice(0, 4).map(record => <button key={record.id}
            onClick={() => {selectSpec?.(record.id);setPage("API Workspace");}}>
            <Badge tone="purple">Review</Badge>{record.name}<ArrowUpRight size={15} />
          </button>)}
          {runs.filter(record => record.status === "FAIL").slice(0, 4).map(record => <button key={record.id}
            onClick={() => {if (record.specId) selectSpec?.(record.specId);setPage("Test Library");}}>
            <Badge tone="red">Failed run</Badge>{tests.find(test => test.id === record.caseId)?.title || "API test"}<ArrowUpRight size={15} />
          </button>)}
          {!specs.some(record => record.status === "IN_REVIEW") && !runs.some(record => record.status === "FAIL") &&
            <p className="muted">No pending contract reviews or failing runs.</p>}
        </div>
      </Panel>
      <div className="stats-grid">
        {[
          [specs.length, "API contracts", Braces, "Versioned and reviewable"],
          [
            tests.length,
            "Saved test cases",
            FlaskConical,
            `${runs.length} recorded executions`,
          ],
          [
            `${runs.length ? Math.round((pass / runs.length) * 100) : 0}%`,
            "Test pass rate",
            ShieldCheck,
            "Based on recorded runs",
          ],
          [
            connections.filter((c) => c.enabled).length,
            "Enabled connectors",
            PlugZap,
            `${connections.length} configured connections`,
          ],
        ].map(([n, label, Icon, sub]) => (
          <Panel key={String(label)}>
            <div className="stat-icon">
              {typeof Icon !== "string" && typeof Icon !== "number" && (
                <Icon size={20} />
              )}
            </div>
            <div className="stat-value">{String(n)}</div>
            <h3>{String(label)}</h3>
            <small className="muted">{String(sub)}</small>
          </Panel>
        ))}
      </div>
      <div className="two-cols">
        <Panel
          title="Delivery pulse"
          action={<Badge tone="green">CURRENT WORKSPACE</Badge>}
        >
          <div className="progress-heading">
            <span>Sprint completion</span>
            <b>
              {done}/{stories.length} stories
            </b>
          </div>
          <div className="progress">
            <i
              style={{
                width: `${stories.length ? (done / stories.length) * 100 : 0}%`,
              }}
            />
          </div>
          <div className="delivery-columns">
            {["To Do", "In Progress", "Done"].map((s) => (
              <div key={s}>
                <span className="muted">{s}</span>
                <strong>{stories.filter((r) => r.status === s).length}</strong>
              </div>
            ))}
          </div>
          <Records
            rows={stories.slice(0, 4)}
            columns={["key", "summary", "status"]}
          />
        </Panel>
        <Panel title="Next best actions">
          <div className="action-list">
            {[
              [
                "API Workspace",
                "Review your API contract",
                "Lint and approve before publishing.",
                Braces,
              ],
              [
                "Test Library",
                "Build your quality baseline",
                "Generate, edit and run test cases.",
                FlaskConical,
              ],
              [
                "Integration Hub",
                "Connect your engineering tools",
                "Add GitHub, Jira, Devin and more.",
                PlugZap,
              ],
              [
                "Team Capacity",
                "Check your delivery capacity",
                "Use leave and regional holiday data.",
                Users,
              ],
            ].map(([page, title, desc, Icon]) => (
              <button key={String(page)} onClick={() => setPage(String(page))}>
                {typeof Icon !== "string" && <Icon size={20} />}
                <span>
                  <b>{String(title)}</b>
                  <small>{String(desc)}</small>
                </span>
                <ArrowUpRight size={17} />
              </button>
            ))}
          </div>
        </Panel>
      </div>
      <Panel title="API portfolio">
        <Records
          rows={specs}
          columns={["name", "version", "status", "updatedAt"]}
        />
      </Panel>
    </>
  );
}
function Governance({ run, data }: PageProps) {
  const [rows, setRows] = useState<Row[]>([]),
    [rule, setRule] = useState<Row | null>(null);
  useEffect(() => {
    request("/workspace/governance").then(setRows);
  }, [data.rules]);
  return (
    <>
      <div className="info-banner">
        <ShieldCheck /> Governance evaluates operation IDs, version metadata,
        response declarations and security requirements.
      </div>
      <div className="toolbar">
        <button
          className="primary"
          onClick={() =>
            run(async () => {
              await post("/governance/evaluate");
              setRows(await request("/workspace/governance"));
            })
          }
        >
          Record evaluation
        </button>
        <button
          onClick={() =>
            setRule({
              name: "Security declaration",
              check: "security",
              severity: "WARNING",
              enabled: true,
            })
          }
        >
          Configure rule
        </button>
      </div>
      <div className="cards-grid">
        {rows.map((r) => (
          <Panel title={r.name} key={r.id}>
            <div className="score">
              {r.score}
              <small>/100</small>
            </div>
            <Records
              rows={r.findings}
              columns={["severity", "path", "message"]}
            />
          </Panel>
        ))}
      </div>
      <Panel title="Configured checks">
        <Records
          rows={data.rules || []}
          columns={["name", "check", "severity", "enabled"]}
          onEdit={setRule}
        />
      </Panel>
      <Panel title="Governance trend">
        <div className="trend-chart">
          {(data["governance-runs"] || [])
            .slice(0, 20)
            .reverse()
            .map((r) => (
              <div key={r.id} title={r.updatedAt}>
                <i style={{ height: Math.max(r.score, 2) + "%" }} />
                <span>{r.score}</span>
              </div>
            ))}
        </div>
        <Records
          rows={data["governance-runs"] || []}
          columns={["score", "updatedAt"]}
        />
      </Panel>
      {rule && (
        <Editor
          title="Governance rule"
          value={rule}
          onClose={() => setRule(null)}
          onSave={async (r) => {
            await post("/governance/rules", r);
            await run(async () => {});
          }}
        />
      )}
    </>
  );
}
