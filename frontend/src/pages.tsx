import { exportDrawio, importDrawio } from "./drawio";
import { parseCurl, snippet as makeSnippet, safeRegex } from "./request-tools";
import { useState, useEffect, useRef } from "react";
import {
  Plus,
  Play,
  Download,
  Upload,
  GitBranch,
  Check,
  Copy,
  PlugZap,
  ShieldCheck,
  Sparkles,
  ArrowRight,
  Send,
  KeyRound,
} from "lucide-react";
import yaml from "js-yaml";
import { diffLines } from "diff";
import {
  request,
  post,
  save,
  pretty,
  download,
  interpolate,
  flatten,
  type Row,
} from "./api";
import {
  Panel,
  Badge,
  Records,
  JsonView,
  Field,
  Empty,
  Editor,
} from "./components";
import type { PageProps } from "./App";
const initialSpec = `openapi: 3.0.3
info:
  title: New API
  version: 1.0.0
security: []
paths:
  /get:
    get:
      operationId: listItems
      summary: List items
      responses:
        '200':
          description: Success
`;
export function Workspace({ data, spec, run, edit }: PageProps) {
  const [source, setSource] = useState(initialSpec),
    [result, setResult] = useState<unknown>(null),
    [compare, setCompare] = useState(""),
    [url, setUrl] = useState("https://httpbin.org/get");
  const operations: Row[] = [];
  if (spec)
    Object.entries(spec.document.paths || {}).forEach(([path, methods]) =>
      Object.entries(methods as Row).forEach(([method, op]) => {
        if (
          ["get", "post", "put", "patch", "delete", "head", "options"].includes(
            method,
          )
        )
          operations.push({ path, method: method.toUpperCase(), ...op });
      }),
    );
  return (
    <>
      <div className="two-cols">
        <Panel
          title="Contract workspace"
          action={<Badge>{spec?.status || "DRAFT"}</Badge>}
        >
          <Field label="OpenAPI JSON or YAML">
            <textarea
              className="code tall"
              value={source}
              onChange={(e) => setSource(e.target.value)}
            />
          </Field>
          <div className="toolbar">
            <label className="button">
              <Upload size={15} /> Open file
              <input
                type="file"
                accept=".json,.yaml,.yml"
                hidden
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  if (file) setSource(await file.text());
                }}
              />
            </label>
            <button
              onClick={() =>
                run(async () =>
                  setResult(await post("/workspace/lint", { source })),
                )
              }
            >
              Lint
            </button>
            <button
              className="primary"
              onClick={() => run(() => post("/workspace/import", { source }))}
            >
              Import contract
            </button>
            <button onClick={() => spec && setSource(pretty(spec.document))}>
              Load selected
            </button>
          </div>
        </Panel>
        <Panel title="Selected API">
          <h2>{spec?.name || "Import an API"}</h2>
          <p className="muted">
            Version {spec?.version} · {operations.length} operations
          </p>
          <div className="toolbar">
            <button
              disabled={!spec}
              onClick={() =>
                spec && edit("specs", { ...spec, status: "DRAFT" })
              }
            >
              Edit as draft
            </button>
            <button
              disabled={!spec}
              onClick={() =>
                run(() =>
                  post("/workspace/" + spec!.id + "/approval", {
                    status: "IN_REVIEW",
                  }),
                )
              }
            >
              Request review
            </button>
            <button
              disabled={!spec}
              onClick={() =>
                run(() =>
                  post("/workspace/" + spec!.id + "/approval", {
                    status: "APPROVED",
                  }),
                )
              }
            >
              <Check size={15} /> Approve
            </button>
            <button
              disabled={!spec}
              onClick={() =>
                run(() => post("/workspace/" + spec!.id + "/collection"))
              }
            >
              Create collection
            </button>
          </div>
          <Records
            rows={operations}
            columns={["method", "path", "summary"]}
            actions={(o) => (
              <button
                onClick={() =>
                  setUrl(
                    (spec?.document.servers?.[0]?.url ||
                      "https://httpbin.org") + o.path,
                  )
                }
              >
                Try
              </button>
            )}
          />
          <Field label="Try-it URL">
            <input value={url} onChange={(e) => setUrl(e.target.value)} />
          </Field>
          <button
            onClick={() =>
              run(async () =>
                setResult(
                  await post("/client/send", {
                    method: "GET",
                    url,
                    headers: {},
                  }),
                ),
              )
            }
          >
            <Play size={15} /> Send GET
          </button>
        </Panel>
      </div>
      <Panel title="Version comparison">
        <Field label="Compare selected contract with">
          <select value={compare} onChange={(e) => setCompare(e.target.value)}>
            <option value="">Choose a version</option>
            {(data.specs || [])
              .filter((s) => s.id !== spec?.id)
              .map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name} {s.version}
                </option>
              ))}
          </select>
        </Field>
        <button
          disabled={!compare}
          onClick={() =>
            run(async () =>
              setResult(
                await post("/workspace/diff", {
                  before: spec?.document,
                  after: data.specs.find((s) => s.id === compare)?.document,
                }),
              ),
            )
          }
        >
          Compare versions
        </button>
      </Panel>
      {result !== null && (
        <Panel title="Results">
          <JsonView value={result} />
        </Panel>
      )}
    </>
  );
}
export function Client({ data, run, edit }: PageProps) {
  const [method, setMethod] = useState("GET"),
    [url, setUrl] = useState("{{baseUrl}}/get"),
    [body, setBody] = useState(""),
    [bodyType, setBodyType] = useState("JSON"),
    [headers, setHeaders] = useState("{}"),
    [env, setEnv] = useState('{"baseUrl":"https://httpbin.org"}'),
    [response, setResponse] = useState<Row | null>(null),
    [auth, setAuth] = useState("None"),
    [token, setToken] = useState(""),
    [collectionId, setCollectionId] = useState(""),
    [snippet, setSnippet] = useState("");
  const collection =
    (data.collections || []).find((c) => c.id === collectionId) ||
    (data.collections || [])[0];
  function makeRequest(r?: Row) {
    const vars = JSON.parse(env);
    let h: Row = JSON.parse(headers);
    if (Array.isArray(r?.header))
      for (const header of r.header)
        if (!header.disabled)
          h[header.key] = interpolate(String(header.value), vars);
    if (bodyType === "JSON") h["Content-Type"] = "application/json";
    if (bodyType === "Form")
      h["Content-Type"] = "application/x-www-form-urlencoded";
    if (bodyType === "XML") h["Content-Type"] = "application/xml";
    if (auth === "Bearer") h.Authorization = "Bearer " + token;
    if (auth === "Basic") h.Authorization = "Basic " + btoa(token);
    if (auth === "API Key") h["X-API-Key"] = token;
    return {
      method: r?.method || method,
      url: interpolate(
        typeof r?.url === "object" ? r.url.raw : r?.url || url,
        vars,
      ),
      body: interpolate(r?.body?.raw ?? body, vars),
      headers: h,
    };
  }
  return (
    <div className="studio-layout">
      <Panel
        title="Collections"
        action={
          <button
            onClick={() =>
              edit("collections", {
                name: "New collection",
                info: {
                  name: "New collection",
                  schema:
                    "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
                },
                item: [],
              })
            }
          >
            <Plus size={15} />
          </button>
        }
      >
        <select
          aria-label="Collection"
          value={collection?.id || ""}
          onChange={(e) => setCollectionId(e.target.value)}
        >
          {(data.collections || []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
        {flatten(collection?.item || []).map((i, n) => (
          <button
            className="request-item"
            key={n}
            onClick={() => {
              setMethod(i.request.method);
              setUrl(
                typeof i.request.url === "string"
                  ? i.request.url
                  : i.request.url.raw,
              );
              setBody(i.request.body?.raw || "");
            }}
          >
            <Badge tone="green">{i.request.method}</Badge>
            {i.name}
          </button>
        ))}
        <div className="toolbar">
          <button
            disabled={!collection}
            onClick={() => collection && edit("collections", collection)}
          >
            Edit
          </button>
          <button
            disabled={!collection}
            onClick={() => download("collection.json", pretty(collection))}
          >
            <Download size={14} /> v2.1
          </button>
          <label className="button">
            <Upload size={14} /> Import
            <input
              hidden
              type="file"
              accept=".json"
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f)
                  run(async () => {
                    const c = JSON.parse(await f.text());
                    if (
                      !c.info?.schema?.includes("v2.1.0") ||
                      !Array.isArray(c.item)
                    )
                      throw new Error("Postman v2.1 collection required");
                    delete c.id;
                    delete c.revision;
                    return save("collections", { ...c, name: c.info.name });
                  });
              }}
            />
          </label>
        </div>
        <button
          onClick={() =>
            run(async () => {
              const results = [];
              for (const i of flatten(collection?.item || [])) {
                const res = await post("/client/send", makeRequest(i.request));
                results.push({ name: i.name, ...res });
              }
              setResponse({ runner: results });
            })
          }
        >
          <Play size={14} /> Run collection
        </button>
        <h4 className="section-label">REQUEST HISTORY</h4>
        {(data.history || []).slice(0, 8).map((h) => (
          <button
            key={h.id}
            className="history-item"
            onClick={() => {
              setMethod(h.method);
              setUrl(h.url);
            }}
          >
            <Badge>{h.status}</Badge>
            <span>
              {h.method} {h.url}
            </span>
          </button>
        ))}
      </Panel>
      <div>
        <Panel>
          <div className="request-bar">
            <select
              aria-label="HTTP method"
              value={method}
              onChange={(e) => setMethod(e.target.value)}
            >
              {["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"].map(
                (m) => (
                  <option key={m}>{m}</option>
                ),
              )}
            </select>
            <input
              aria-label="Request URL"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
            <button
              className="primary"
              onClick={() =>
                run(async () =>
                  setResponse(await post("/client/send", makeRequest())),
                )
              }
            >
              <Send size={16} /> Send
            </button>
          </div>
          <div className="two-cols compact">
            <Field label="Authentication">
              <select value={auth} onChange={(e) => setAuth(e.target.value)}>
                {["None", "Bearer", "Basic", "API Key"].map((x) => (
                  <option key={x}>{x}</option>
                ))}
              </select>
            </Field>
            <Field label="Ephemeral credential (not saved)">
              <input
                type="password"
                value={token}
                placeholder="For saved credentials use Integration Hub"
                onChange={(e) => setToken(e.target.value)}
              />
            </Field>
          </div>
          <div className="two-cols compact">
            <Field label="Headers (JSON)">
              <textarea
                className="code"
                value={headers}
                onChange={(e) => setHeaders(e.target.value)}
              />
            </Field>
            <Field label="Environment variables (JSON)">
              <textarea
                className="code"
                value={env}
                onChange={(e) => setEnv(e.target.value)}
              />
            </Field>
          </div>
          <Field label="Body format">
            <select
              value={bodyType}
              onChange={(e) => setBodyType(e.target.value)}
            >
              {["JSON", "Text", "XML", "Form"].map((x) => (
                <option key={x}>{x}</option>
              ))}
            </select>
          </Field>
          <textarea
            aria-label="Request body"
            className="code"
            value={body}
            onChange={(e) => setBody(e.target.value)}
          />
          <div className="toolbar">
            <button
              onClick={() => {
                try {
                  const r = makeRequest();
                  setSnippet(makeSnippet(r, "curl"));
                } catch (e) {
                  setSnippet(String(e));
                }
              }}
            >
              Generate curl
            </button>
            <button
              onClick={() => {
                try {
                  setSnippet(makeSnippet(makeRequest(), "fetch"));
                } catch (e) {
                  setSnippet(String(e));
                }
              }}
            >
              fetch snippet
            </button>
            <button
              onClick={() => {
                try {
                  setSnippet(makeSnippet(makeRequest(), "java"));
                } catch (e) {
                  setSnippet(String(e));
                }
              }}
            >
              Java snippet
            </button>
            <button
              onClick={() => {
                try {
                  const text = prompt("Paste curl command");
                  if (text) {
                    const parsed = parseCurl(text);
                    setMethod(parsed.method);
                    setUrl(parsed.url);
                    setHeaders(pretty(parsed.headers));
                    setBody(parsed.body);
                  }
                } catch (e) {
                  setSnippet(String(e));
                }
              }}
            >
              Import curl
            </button>
            <button
              onClick={() =>
                collection &&
                run(() =>
                  save("collections", {
                    ...collection,
                    item: [
                      ...collection.item,
                      {
                        name: method + " " + url,
                        request: {
                          method,
                          url,
                          header: [],
                          body: { mode: "raw", raw: body },
                        },
                      },
                    ],
                  }),
                )
              }
            >
              Save to collection
            </button>
          </div>
          {snippet && <JsonView value={snippet} />}
        </Panel>
        <Panel
          title="Response"
          action={
            response && (
              <Badge
                tone={
                  response.status >= 200 && response.status < 300
                    ? "green"
                    : "neutral"
                }
              >
                {response.status || "Runner"} · {response.durationMs || 0} ms
              </Badge>
            )
          }
        >
          {response ? (
            <JsonView value={response} />
          ) : (
            <Empty text="Send a request to inspect the response, headers and timing." />
          )}
        </Panel>
      </div>
    </div>
  );
}
export function TestLibrary({ data, spec, run, edit }: PageProps) {
  const [search, setSearch] = useState(""),
    [type, setType] = useState(""),
    [result, setResult] = useState<unknown>(null),
    [selected, setSelected] = useState<string[]>([]),
    [base, setBase] = useState("https://httpbin.org"),
    [tab, setTab] = useState("Cases");
  const rows = (data.tests || []).filter(
    (t) =>
      (!type || t.type === type) &&
      pretty(t).toLowerCase().includes(search.toLowerCase()),
  );
  return (
    <>
      <div className="toolbar spread">
        <div className="tabs">
          {["Cases", "Suites", "Plans", "Runs"].map((t) => (
            <button
              className={tab === t ? "selected" : ""}
              key={t}
              onClick={() => setTab(t)}
            >
              {t}
            </button>
          ))}
        </div>
        <div className="toolbar">
          <button
            onClick={() =>
              edit("tests", {
                title: "Manual test",
                type: "positive",
                priority: "Medium",
                status: "Draft",
                operation: "GET /get",
                tags: [],
                steps: [],
                request: {
                  method: "GET",
                  url: "{{baseUrl}}/get",
                  headers: {},
                  body: "",
                },
                expectedStatus: 200,
                expectedBodyContains: "",
              })
            }
          >
            <Plus size={15} /> Manual case
          </button>
          <button
            className="primary"
            disabled={!spec}
            onClick={() => run(() => post("/tests/generate/" + spec!.id))}
          >
            <Sparkles size={16} /> Generate from API
          </button>
        </div>
      </div>
      {tab === "Cases" ? (
        <Panel>
          <div className="toolbar">
            <input
              aria-label="Search test cases"
              placeholder="Search API, operation, tag, Jira key…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <select value={type} onChange={(e) => setType(e.target.value)}>
              <option value="">All types</option>
              {["positive", "negative", "boundary", "security"].map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
            <input
              aria-label="Test base URL"
              value={base}
              onChange={(e) => setBase(e.target.value)}
            />
            <button
              onClick={() =>
                run(async () => {
                  const results = [];
                  for (const id of selected)
                    results.push(
                      await post("/tests/" + id + "/run", { baseUrl: base }),
                    );
                  setResult(results);
                })
              }
            >
              Run selected ({selected.length})
            </button>
          </div>
          <p className="muted">
            Generated cases are editable drafts. Review payloads, boundary
            values and expected statuses before execution. Regeneration
            preserves existing edits.
          </p>
          <Records
            rows={rows}
            columns={["title", "type", "priority", "status", "linkedJiraKey"]}
            onEdit={(r) => edit("tests", r)}
            actions={(r) => (
              <>
                <input
                  aria-label={"Select " + r.title}
                  type="checkbox"
                  checked={selected.includes(r.id)}
                  onChange={(e) =>
                    setSelected((s) =>
                      e.target.checked
                        ? [...s, r.id]
                        : s.filter((id) => id !== r.id),
                    )
                  }
                />
                <button
                  onClick={() =>
                    run(async () =>
                      setResult(
                        await post("/tests/" + r.id + "/run", {
                          baseUrl: base,
                        }),
                      ),
                    )
                  }
                >
                  <Play size={13} />
                </button>
                <button
                  title="Clone"
                  onClick={() => {
                    const c: Row = { ...r, title: r.title + " (copy)" };
                    delete c.id;
                    delete c.revision;
                    delete c.fingerprint;
                    edit("tests", c);
                  }}
                >
                  <Copy size={13} />
                </button>
                <button
                  onClick={() =>
                    run(async () =>
                      setResult(
                        await request("/records/tests/" + r.id + "/versions"),
                      ),
                    )
                  }
                >
                  History
                </button>
                <button
                  onClick={() =>
                    run(() =>
                      post(
                        "/records/tests/" +
                          r.id +
                          "/restore/" +
                          prompt("Revision to restore", "1"),
                      ),
                    )
                  }
                >
                  Restore
                </button>
              </>
            )}
          />
          <div className="toolbar">
            <a className="button" href="/api/tests/workbook">
              Excel export
            </a>
            <label className="button">
              Excel import
              <input
                type="file"
                accept=".xlsx"
                hidden
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f)
                    run(async () => {
                      const form = new FormData();
                      form.append("file", f);
                      setResult(
                        await request("/tests/workbook", {
                          method: "POST",
                          body: form,
                        }),
                      );
                    });
                }}
              />
            </label>
            {["json", "csv", "postman", "junit", "cucumber"].map((f) => (
              <a className="button" key={f} href={"/api/tests/export/" + f}>
                <Download size={14} />
                {f}
              </a>
            ))}
            <label className="button">
              Import JSON
              <input
                type="file"
                accept=".json"
                hidden
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f)
                    run(async () => {
                      const cases = JSON.parse(await f.text());
                      if (!Array.isArray(cases))
                        throw new Error("Expected an array of test cases");
                      for (const c of cases) {
                        delete c.id;
                        delete c.revision;
                        await save("tests", c);
                      }
                    });
                }}
              />
            </label>
          </div>
        </Panel>
      ) : tab === "Runs" ? (
        <Panel title="Execution history">
          <Records
            rows={data.runs || []}
            columns={["caseId", "caseRevision", "status", "updatedAt"]}
            actions={(r) => (
              <button onClick={() => setResult(r)}>Details</button>
            )}
          />
        </Panel>
      ) : (
        <Panel title={tab}>
          <Records
            rows={data[tab.toLowerCase()] || []}
            columns={["name", "sprint", "caseIds"]}
            actions={(r) => (
              <button
                onClick={() =>
                  run(async () =>
                    setResult(
                      await post(
                        "/tests/suite/" +
                          tab.toLowerCase() +
                          "/" +
                          r.id +
                          "/run",
                        { baseUrl: base },
                      ),
                    ),
                  )
                }
              >
                Run {tab.slice(0, -1)}
              </button>
            )}
            onEdit={(r) => edit(tab.toLowerCase(), r)}
            onAdd={() =>
              edit(tab.toLowerCase(), {
                name: "New " + tab.slice(0, -1),
                sprint: "",
                caseIds: selected,
              })
            }
          />
        </Panel>
      )}
      {result !== null && (
        <Panel title="Case evidence and versions">
          <JsonView value={result} />
        </Panel>
      )}
    </>
  );
}
export function Capacity({ data, run, edit }: PageProps) {
  const [sprintId, setSprint] = useState(""),
    [capacity, setCapacity] = useState<Row[]>([]),
    [tab, setTab] = useState("Matrix"),
    [report, setReport] = useState<unknown>(null);
  const sprint =
    data.sprints?.find((s) => s.id === sprintId) || data.sprints?.[0];
  useEffect(() => {
    if (sprint)
      request("/capacity/" + sprint.id)
        .then(setCapacity)
        .catch((e) => setReport(String(e)));
  }, [sprint, data.team, data.leaves, data.holidays, data.stories]);
  const defaults: Record<string, Row> = {
    team: {
      name: "New member",
      role: "Dev",
      region: "USA",
      hoursPerDay: 8,
      allocation: 100,
    },
    sprints: {
      name: "New sprint",
      start: "2026-11-02",
      end: "2026-11-13",
      hoursPerPoint: 8,
    },
    leaves: {
      name: data.team?.[0]?.name || "",
      from: "2026-11-02",
      to: "2026-11-02",
      type: "Leave",
    },
    holidays: { date: "2026-11-26", region: "USA", name: "Regional holiday" },
    stories: {
      key: "NEW-1",
      summary: "New story",
      priority: "Medium",
      status: "To Do",
      sprint: sprint?.name || "",
      devPoints: 0,
      testPoints: 0,
      developer: "",
      tester: "",
    },
  };
  return (
    <>
      <div className="toolbar spread">
        <select
          aria-label="Sprint"
          value={sprint?.id || ""}
          onChange={(e) => setSprint(e.target.value)}
        >
          {data.sprints?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} · {s.start} — {s.end}
            </option>
          ))}
        </select>
        <div className="toolbar">
          <a className="button" href="/api/capacity/workbook/template">
            <Download size={15} /> Download template
          </a>
          <label className="button">
            <Upload size={15} /> Upload Excel
            <input
              type="file"
              accept=".xlsx"
              hidden
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f)
                  run(async () => {
                    const form = new FormData();
                    form.append("file", f);
                    setReport(
                      await request("/capacity/workbook", {
                        method: "POST",
                        body: form,
                      }),
                    );
                  });
              }}
            />
          </label>
          <a className="button" href="/api/capacity/workbook/export">
            Export Excel
          </a>
          <button
            onClick={() =>
              download(
                "capacity.csv",
                "Name,Role,Region,Hours,Capacity,Committed\n" +
                  capacity
                    .map((r) =>
                      [
                        r.name,
                        r.role,
                        r.region,
                        r.hours,
                        r.capacity,
                        r.committed,
                      ]
                        .map((v) => '"' + String(v).replace(/"/g, '""') + '"')
                        .join(","),
                    )
                    .join("\n"),
                "text/csv",
              )
            }
          >
            CSV
          </button>
        </div>
      </div>
      <div className="stats-grid">
        {["Dev", "Test"]
          .flatMap((role) => {
            const team = capacity.filter((r) => r.role === role);
            return [
              [role + " capacity", team.reduce((s, r) => s + r.capacity, 0)],
              [role + " committed", team.reduce((s, r) => s + r.committed, 0)],
            ];
          })
          .map(([l, v]) => (
            <Panel key={l}>
              <small className="muted">{l} · points</small>
              <div className="stat-value">{Number(v).toFixed(1)}</div>
            </Panel>
          ))}
      </div>
      <div className="tabs">
        {["Matrix", "Stories", "Team", "Sprints", "Leaves", "Holidays"].map(
          (t) => (
            <button
              className={tab === t ? "selected" : ""}
              key={t}
              onClick={() => setTab(t)}
            >
              {t}
            </button>
          ),
        )}
      </div>
      {tab === "Matrix" ? (
        <>
          <Panel title="Capacity vs. commitment">
            <button
              disabled={!sprint}
              onClick={() =>
                run(async () =>
                  setReport(await post("/capacity/" + sprint!.id + "/notify")),
                )
              }
            >
              Propose Friday follow-up if over capacity
            </button>
            <p className="muted">
              Points = available hours ÷ {sprint?.hoursPerPoint || 8} hours per
              point. Weekends, leave and regional holidays reduce availability.
            </p>
            <Records
              rows={capacity}
              columns={[
                "name",
                "role",
                "region",
                "hours",
                "capacity",
                "committed",
                "utilization",
              ]}
            />
            {capacity.map((r) => (
              <div className="capacity-bar" key={r.name}>
                <span>{r.name}</span>
                <div className="progress">
                  <i
                    className={r.utilization > 100 ? "over" : ""}
                    style={{ width: Math.min(r.utilization, 100) + "%" }}
                  />
                </div>
                <Badge tone={r.utilization > 100 ? "red" : "green"}>
                  {r.utilization}%
                </Badge>
              </div>
            ))}
          </Panel>
          <Panel title="Availability heatmap">
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Team member</th>
                    {capacity[0]?.days.map((d: Row) => (
                      <th key={d.date}>{d.date.slice(5)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {capacity.map((r) => (
                    <tr key={r.name}>
                      <td>{r.name}</td>
                      {r.days.map((d: Row) => (
                        <td key={d.date}>
                          <div
                            title={d.date + " · " + d.reason}
                            className={
                              "heat-cell " +
                              (d.hours ? "available" : "unavailable")
                            }
                          >
                            {d.hours || "—"}
                          </div>
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </>
      ) : (
        <Panel title={tab}>
          <Records
            rows={data[tab.toLowerCase()] || []}
            columns={Object.keys(defaults[tab.toLowerCase()])}
            onEdit={(r) => edit(tab.toLowerCase(), r)}
            onAdd={() => edit(tab.toLowerCase(), defaults[tab.toLowerCase()])}
          />
        </Panel>
      )}
      {report !== null && (
        <Panel title="Import report">
          <JsonView value={report} />
        </Panel>
      )}
    </>
  );
}
export function Jira({ data, run, edit }: PageProps) {
  const [jql, setJql] = useState("project = DEMO ORDER BY updated DESC"),
    [result, setResult] = useState<unknown>(null),
    [selected, setSelected] = useState<string[]>([]),
    [status, setStatus] = useState("To Do");
  const connection = data.integrations?.find((c) => c.provider === "jira");
  return (
    <>
      <Panel title="Jira search">
        <label className="button">
          Import CSV
          <input
            type="file"
            accept=".csv"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f)
                run(async () =>
                  setResult(await post("/jira/csv", { csv: await f.text() })),
                );
            }}
          />
        </label>
        <div className="toolbar">
          <input
            aria-label="JQL"
            value={jql}
            onChange={(e) => setJql(e.target.value)}
          />
          <button
            disabled={!connection?.enabled}
            onClick={() =>
              run(async () =>
                setResult(
                  await post("/integrations/" + connection!.id + "/read", {
                    path:
                      "/rest/api/3/search/jql?jql=" + encodeURIComponent(jql),
                  }),
                ),
              )
            }
          >
            Run JQL
          </button>
          <button
            onClick={() =>
              edit("stories", {
                key: "NEW-1",
                summary: "New issue",
                priority: "Medium",
                status: "To Do",
                sprint: "",
                devPoints: 0,
                testPoints: 0,
              })
            }
          >
            New local story
          </button>
          <button
            onClick={() =>
              download(
                "stories.csv",
                "Key,Summary,Status\n" +
                  (data.stories || [])
                    .map((s) =>
                      [s.key, s.summary, s.status]
                        .map((v) => '"' + String(v).replace(/"/g, '""') + '"')
                        .join(","),
                    )
                    .join("\n"),
                "text/csv",
              )
            }
          >
            Export CSV
          </button>
        </div>
        <p className="muted">
          Local sprint stories are stored independently. Configure Jira in
          Integration Hub for live search and approved issue creation.
        </p>
      </Panel>
      <div className="kanban">
        {["To Do", "In Progress", "Done"].map((s) => (
          <div className="kanban-column" key={s}>
            <h3>
              {s}{" "}
              <Badge>
                {data.stories?.filter((r) => r.status === s).length || 0}
              </Badge>
            </h3>
            {data.stories
              ?.filter((r) => r.status === s)
              .map((r) => (
                <article className="story-card" key={r.id}>
                  <div className="toolbar spread">
                    <small>{r.key}</small>
                    <input
                      type="checkbox"
                      aria-label={"Select " + r.key}
                      checked={selected.includes(r.id)}
                      onChange={(e) =>
                        setSelected((old) =>
                          e.target.checked
                            ? [...old, r.id]
                            : old.filter((x) => x !== r.id),
                        )
                      }
                    />
                  </div>
                  <button
                    className="story-title"
                    onClick={() => edit("stories", r)}
                  >
                    {r.summary}
                  </button>
                  <div className="toolbar spread">
                    <Badge tone={r.priority === "High" ? "red" : "neutral"}>
                      {r.priority}
                    </Badge>
                    <small>
                      {r.devPoints || 0} Dev · {r.testPoints || 0} Test
                    </small>
                  </div>
                </article>
              ))}
          </div>
        ))}
      </div>
      <Panel title="Bulk update local stories">
        <div className="toolbar">
          <span>{selected.length} selected</span>
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            {["To Do", "In Progress", "Done"].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
          <button
            onClick={() =>
              run(async () => {
                for (const id of selected)
                  await save("stories", {
                    ...data.stories.find((s) => s.id === id),
                    status,
                  });
                setSelected([]);
              })
            }
          >
            Apply status
          </button>
          <button
            onClick={() =>
              run(async () => {
                for (const test of data.runs?.filter(
                  (r) => r.status === "FAIL",
                ) || [])
                  await save("stories", {
                    key: "FAIL-" + test.id.slice(0, 6),
                    summary: "Investigate failed test " + test.caseId,
                    status: "To Do",
                    priority: "High",
                    runId: test.id,
                  });
              })
            }
          >
            Stories from failed runs
          </button>
        </div>
      </Panel>
      {result !== null && (
        <Panel title="Jira response">
          <JsonView value={result} />
        </Panel>
      )}
    </>
  );
}
export function Architecture({ data, spec, run, edit }: PageProps) {
  const [source, setSource] = useState(
      "flowchart TD\n Client --> Gateway\n Gateway --> Orders\n Gateway --> Payments\n Orders --> Database\n Payments --> Database",
    ),
    [error, setError] = useState("");
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let active = true;
    const timer = setTimeout(async () => {
      try {
        const { default: mermaid } = await import("mermaid");
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: "strict",
          theme: "neutral",
        });
        const { svg } = await mermaid.render("diagram-" + Date.now(), source);
        if (active && container.current) {
          container.current.innerHTML = svg;
          setError("");
        }
      } catch {
        if (active)
          setError("Diagram syntax is invalid. Check the Mermaid source.");
      }
    }, 350);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [source]);
  return (
    <>
      <div className="two-cols">
        <Panel title="Diagram source">
          <textarea
            aria-label="Mermaid source"
            className="code tall"
            value={source}
            onChange={(e) => setSource(e.target.value)}
          />
          <div className="toolbar">
            <button
              onClick={() =>
                run(() =>
                  save("diagrams", { name: "Architecture diagram", source }),
                )
              }
            >
              Save diagram
            </button>
            <button
              onClick={() => {
                const paths = Object.keys(spec?.document.paths || {});
                setSource(
                  "flowchart TD\n Client --> API" +
                    paths
                      .map(
                        (p, i) =>
                          `\n API --> Op${i}["${p.replace(/["<>]/g, "")}"]`,
                      )
                      .join(""),
                );
              }}
            >
              From API
            </button>
            <button
              onClick={() => download("architecture.mmd", source, "text/plain")}
            >
              Mermaid
            </button>
            <button
              onClick={() => {
                download(
                  "architecture.drawio",
                  exportDrawio(source),
                  "application/xml",
                );
              }}
            >
              draw.io export
            </button>
            <label className="button">
              Import
              <input
                hidden
                type="file"
                accept=".mmd,.drawio,.xml"
                onChange={async (e) => {
                  const f = e.target.files?.[0];
                  if (f) {
                    const text = await f.text();
                    if (text.trim().startsWith("<")) {
                      try {
                        setSource(await importDrawio(text));
                      } catch (error) {
                        setError(String(error));
                      }
                    } else setSource(text);
                  }
                }}
              />
            </label>
          </div>
          <small className="muted">
            draw.io export preserves Mermaid source and basic flowchart shapes.
            Import converts native nodes and edges; vendor-specific styling is
            not preserved.
          </small>
        </Panel>
        <Panel title="Live preview">
          {error && <p className="error">{error}</p>}
          <div className="diagram" ref={container} />
        </Panel>
      </div>
      <Panel title="Saved diagrams">
        <Records
          rows={data.diagrams || []}
          columns={["name", "updatedAt"]}
          onEdit={(r) => edit("diagrams", r)}
          actions={(r) => (
            <button onClick={() => setSource(r.source)}>Open</button>
          )}
        />
      </Panel>
    </>
  );
}
const providerDescriptions: Record<string, string> = {
  github: "Repositories, branches and pull requests",
  jira: "Issues, JQL and delivery workflows",
  devin: "Delegate engineering tasks to an AI agent",
  openai: "OpenAI-compatible chat completions",
  anthropic: "Claude via configurable API actions",
  azure: "Azure-hosted AI deployments",
  ollama: "Local models using your server allowlist",
  friday: "Local spaces, chat and workflow signals",
  custom: "Extend the workspace with any HTTP API",
};
export function Integrations({ data, run, edit }: PageProps) {
  const [connectionId, setConnectionId] = useState("");
  const [selected, setSelected] = useState<Row | null>(null),
    [secret, setSecret] = useState(""),
    [secretName, setSecretName] = useState(""),
    [result, setResult] = useState<unknown>(null),
    [action, setAction] = useState(
      '{"method":"POST","path":"/v3/organizations/ORG_ID/sessions","body":{"prompt":"Review this API contract","max_acu_limit":2}}',
    ),
    [readPath, setReadPath] = useState("/");
  const [tab, setTab] = useState("Connections");
  return (
    <>
      <div className="integration-hero">
        <div className="integration-symbol">
          <PlugZap size={38} />
        </div>
        <div>
          <Badge tone="purple">EXTENSIBLE BY DESIGN</Badge>
          <h2>Your tools. One control plane.</h2>
          <p>
            Manage authentication, scope connector access and review outbound
            actions.
          </p>
        </div>
        <button
          className="primary"
          onClick={() =>
            setSelected({
              name: "New connector",
              provider: "custom",
              baseUrl: "https://example.com",
              enabled: false,
              authType: "BEARER",
              secretRef: "custom-token",
              allowedRoles: ["ADMIN"],
            })
          }
        >
          <Plus size={16} /> Add connector
        </button>
      </div>
      <div className="tabs">
        {["Connections", "Credential vault", "Approval queue"].map((t) => (
          <button
            className={tab === t ? "selected" : ""}
            key={t}
            onClick={() => setTab(t)}
          >
            {t}
            {t === "Approval queue" && (
              <Badge>
                {data.events?.filter((e) => e.status === "PENDING").length || 0}
              </Badge>
            )}
          </button>
        ))}
      </div>
      {tab === "Connections" ? (
        <div className="connector-grid">
          {data.integrations?.map((c) => (
            <Panel key={c.id}>
              <div className="connector-top">
                <span className={"connector-icon " + c.provider}>
                  {c.provider === "devin" ? "D" : c.name.slice(0, 2)}
                </span>
                <Badge tone={c.enabled ? "green" : "neutral"}>
                  {c.enabled ? "Enabled" : "Not connected"}
                </Badge>
              </div>
              <h2>{c.name}</h2>
              <p className="muted">
                {providerDescriptions[c.provider] ||
                  providerDescriptions.custom}
              </p>
              <div className="connector-meta">
                <span>
                  <KeyRound size={13} /> {c.authType}
                </span>
                <span>{(c.allowedRoles || ["ADMIN"]).join(", ")}</span>
              </div>
              <button
                className="wide"
                onClick={() => {
                  setSelected(c);
                  setSecretName(c.secretRef || "");
                  setReadPath(
                    c.provider === "github"
                      ? "/repos/owner/repository"
                      : c.provider === "friday"
                        ? "/api/workspaces"
                        : c.provider === "devin"
                          ? "/v3/organizations/" +
                            (c.organizationId || "ORG_ID") +
                            "/sessions"
                          : "/",
                  );
                }}
              >
                Manage connection <ArrowRight size={15} />
              </button>
            </Panel>
          ))}
        </div>
      ) : tab === "Credential vault" ? (
        <Panel title="Encrypted credentials">
          <div className="info-banner">
            <ShieldCheck /> Values are encrypted with AES-256-GCM. Only secret
            names are retrievable. Save a replacement value to rotate a
            credential.
          </div>
          <div className="two-cols compact">
            <Field label="Secret name">
              <input
                value={secretName}
                onChange={(e) => setSecretName(e.target.value)}
                placeholder="devin-token"
              />
            </Field>
            <Field label="Secret value">
              <input
                type="password"
                autoComplete="off"
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
              />
            </Field>
          </div>
          <button
            className="primary"
            onClick={() =>
              run(async () => {
                await request(
                  "/integrations/secrets/" + encodeURIComponent(secretName),
                  { method: "PUT", body: pretty({ value: secret }) },
                );
                setSecret("");
              })
            }
          >
            Save / rotate secret
          </button>
          <button
            onClick={() =>
              run(async () => setResult(await request("/integrations/secrets")))
            }
          >
            List saved names
          </button>
          <button
            onClick={() =>
              run(() =>
                request(
                  "/integrations/secrets/" + encodeURIComponent(secretName),
                  { method: "DELETE" },
                ),
              )
            }
          >
            Delete secret
          </button>
          <p className="muted">
            Bearer tokens, PATs, API keys and OAuth client secrets use this
            vault. For Basic auth, store username:password as the secret value.
          </p>
        </Panel>
      ) : (
        <Panel title="Review outbound changes">
          <p className="muted">
            Each approved action is consumed once. Changes to the connection
            invalidate previous approvals. Failed actions require a new
            proposal.
          </p>
          <Records
            rows={data.events || []}
            columns={["requester", "status", "integrationId", "updatedAt"]}
            actions={(e) => (
              <>
                <button onClick={() => setResult(e)}>Inspect</button>
                <button
                  disabled={e.status !== "PENDING"}
                  onClick={() =>
                    run(() =>
                      post("/integrations/approvals/" + e.id + "/approve"),
                    )
                  }
                >
                  Approve
                </button>
                <button
                  disabled={e.status !== "APPROVED"}
                  onClick={() =>
                    run(async () =>
                      setResult(
                        await post(
                          "/integrations/approvals/" + e.id + "/execute",
                        ),
                      ),
                    )
                  }
                >
                  Execute
                </button>
                {data.integrations?.find((c) => c.id === e.integrationId)
                  ?.provider === "friday" && (
                  <button
                    disabled={e.status !== "APPROVED"}
                    onClick={() =>
                      run(async () => {
                        const csrf = await request("/csrf");
                        const response = await fetch(
                          "/api/integrations/approvals/" + e.id + "/stream",
                          {
                            method: "POST",
                            headers: { "X-XSRF-TOKEN": csrf.token },
                          },
                        );
                        if (!response.ok)
                          throw new Error("Workflow stream failed");
                        const reader = response.body!.getReader(),
                          decoder = new TextDecoder();
                        let text = "";
                        while (true) {
                          const chunk = await reader.read();
                          if (chunk.done) break;
                          text += decoder.decode(chunk.value, { stream: true });
                          setResult(text);
                        }
                      })
                    }
                  >
                    Stream workflow
                  </button>
                )}
              </>
            )}
          />
        </Panel>
      )}
      {selected && (
        <ConnectorEditor
          value={selected}
          onClose={() => setSelected(null)}
          onSave={async (c) => {
            await post("/integrations", c);
            await run(async () => {});
          }}
        />
      )}
      <Panel title="Connector action console">
        <p className="muted">
          Use a saved connector for read requests or propose an exact write
          payload. Configure the target host in INTEGRATION_HOSTS before
          enabling it.
        </p>
        <Field label="Connection">
          <select
            value={connectionId}
            onChange={(e) => setConnectionId(e.target.value)}
          >
            <option value="">Choose a connector</option>
            {data.integrations?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Connection ID">
          <input
            value={connectionId}
            onChange={(e) => setConnectionId(e.target.value)}
            list="connections"
          />
          <datalist id="connections">
            {data.integrations?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </datalist>
        </Field>
        <div className="request-bar">
          <input
            aria-label="Connector read path"
            value={readPath}
            onChange={(e) => setReadPath(e.target.value)}
          />
          <button
            onClick={() =>
              run(async () =>
                setResult(
                  await post("/integrations/" + connectionId + "/read", {
                    path: readPath,
                  }),
                ),
              )
            }
          >
            Read / test connection
          </button>
        </div>
        <textarea
          aria-label="Connector action JSON"
          className="code"
          value={action}
          onChange={(e) => setAction(e.target.value)}
        />
        <button
          onClick={() =>
            run(async () =>
              setResult(
                await post(
                  "/integrations/" + connectionId + "/proposals",
                  JSON.parse(action),
                ),
              ),
            )
          }
        >
          Submit for approval
        </button>
        <p className="muted">
          Devin preset: POST /v3/organizations/ORG_ID/sessions. Use your
          organization ID, a task prompt and a cost limit. Future tools use the
          same connector interface.
        </p>
      </Panel>
      {result !== null && (
        <Panel title="Connection results">
          <JsonView value={result} />
        </Panel>
      )}
    </>
  );
}
export function GitHubPage({ data, run }: PageProps) {
  const c = data.integrations?.find((i) => i.provider === "github"),
    [repo, setRepo] = useState("owner/repository"),
    [path, setPath] = useState(""),
    [result, setResult] = useState<unknown>(null),
    [branch, setBranch] = useState("feature/api-update"),
    [sha, setSha] = useState(""),
    [content, setContent] = useState(""),
    [title, setTitle] = useState("Update API contract"),
    [jira, setJira] = useState("");
  function propose(action: Row) {
    return run(async () =>
      setResult(await post("/integrations/" + c?.id + "/proposals", action)),
    );
  }
  return (
    <>
      <Panel title="Repository browser">
        <div className="toolbar">
          <input
            aria-label="GitHub repository"
            value={repo}
            onChange={(e) => setRepo(e.target.value)}
          />
          <input
            aria-label="Repository path"
            placeholder="File or folder path"
            value={path}
            onChange={(e) => setPath(e.target.value)}
          />
          <button
            disabled={!c?.enabled}
            onClick={() =>
              run(async () =>
                setResult(
                  await post("/integrations/" + c!.id + "/read", {
                    path: "/repos/" + repo + "/contents/" + path,
                  }),
                ),
              )
            }
          >
            Browse
          </button>
          <button
            disabled={!c?.enabled}
            onClick={() =>
              run(async () =>
                setResult(
                  await post("/integrations/" + c!.id + "/read", {
                    path: "/repos/" + repo + "/pulls?state=open",
                  }),
                ),
              )
            }
          >
            Pull requests
          </button>
          <button
            disabled={!c?.enabled}
            onClick={() =>
              run(async () =>
                setResult(
                  await post("/integrations/" + c!.id + "/read", {
                    path: "/repos/" + repo + "/git/ref/heads/main",
                  }),
                ),
              )
            }
          >
            Read main SHA
          </button>
        </div>
        <p className="muted">
          Configure a GitHub PAT in Integration Hub. Each write goes to the
          approval queue before execution.
        </p>
      </Panel>
      <Panel title="Prepare a branch, commit and pull request">
        <div className="two-cols compact">
          <Field label="Branch name">
            <input value={branch} onChange={(e) => setBranch(e.target.value)} />
          </Field>
          <Field label="Base commit SHA (or current file blob SHA for updates)">
            <input value={sha} onChange={(e) => setSha(e.target.value)} />
          </Field>
          <Field label="PR title / commit message">
            <input value={title} onChange={(e) => setTitle(e.target.value)} />
          </Field>
          <Field label="Linked Jira key">
            <input value={jira} onChange={(e) => setJira(e.target.value)} />
          </Field>
        </div>
        <Field label="File contents">
          <textarea
            className="code"
            value={content}
            onChange={(e) => setContent(e.target.value)}
          />
        </Field>
        <div className="toolbar">
          <button
            disabled={!c?.enabled}
            onClick={() =>
              propose({
                method: "POST",
                path: "/repos/" + repo + "/git/refs",
                body: { ref: "refs/heads/" + branch, sha },
              })
            }
          >
            <GitBranch size={14} /> 1. Propose branch
          </button>
          <button
            disabled={!c?.enabled || !path}
            onClick={() =>
              propose({
                method: "PUT",
                path: "/repos/" + repo + "/contents/" + path,
                body: {
                  message: title + (jira ? " [" + jira + "]" : ""),
                  branch,
                  content: btoa(
                    String.fromCharCode(...new TextEncoder().encode(content)),
                  ),
                  ...(sha ? { sha } : {}),
                },
              })
            }
          >
            2. Propose commit
          </button>
          <button
            disabled={!c?.enabled}
            onClick={() =>
              propose({
                method: "POST",
                path: "/repos/" + repo + "/pulls",
                body: {
                  title,
                  head: branch,
                  base: "main",
                  body: jira
                    ? "Related issue: " + jira
                    : "API workspace update",
                },
              })
            }
          >
            3. Propose PR
          </button>
        </div>
        <small className="muted">
          Execute these in order through Integration Hub → Approval queue. For a
          new file, clear the blob SHA before proposing its commit.
        </small>
      </Panel>
      {result !== null && (
        <Panel title="GitHub response">
          <JsonView value={result} />
        </Panel>
      )}
    </>
  );
}
export function Copilot({ data, run }: PageProps) {
  const [provider, setProvider] = useState("fallback"),
    [assistant, setAssistant] = useState("API reviewer"),
    [promptText, setPrompt] = useState(
      "Review this API for missing error responses and authentication requirements.",
    ),
    [messages, setMessages] = useState<Row[]>([]),
    [workspace, setWorkspace] = useState(""),
    [spaces, setSpaces] = useState<unknown>(null);
  async function ask() {
    if (provider === "fallback") {
      setMessages((old) => [
        ...old,
        { role: "You", text: promptText },
        {
          role: assistant,
          text: "Rule-based guidance: declare authentication per operation; document success and 4xx/5xx responses; validate request schemas and boundary values; use unique operation IDs; review breaking changes before approval. This fallback does not call an AI model.",
        },
      ]);
      return;
    }
    const response = await post("/integrations/" + provider + "/ai", {
      prompt: assistant + ": " + promptText,
    });
    setMessages((old) => [
      ...old,
      { role: "You", text: promptText },
      {
        role: assistant,
        text:
          response.choices?.[0]?.message?.content ||
          response.response ||
          pretty(response),
      },
    ]);
  }
  const friday = data.integrations?.find((c) => c.provider === "friday");
  return (
    <div className="two-cols">
      <Panel title="AI workbench">
        <div className="two-cols compact">
          <Field label="Provider">
            <select
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
            >
              <option value="fallback">
                Rule-based fallback · no credentials
              </option>
              {data.integrations
                ?.filter((c) =>
                  ["openai", "azure", "ollama", "anthropic"].includes(
                    c.provider,
                  ),
                )
                .map((c) => (
                  <option key={c.id} value={c.id} disabled={!c.enabled}>
                    {c.name} {c.enabled ? "" : "· disabled"}
                  </option>
                ))}
            </select>
          </Field>
          <Field label="Assistant">
            <select
              value={assistant}
              onChange={(e) => setAssistant(e.target.value)}
            >
              {[
                "API reviewer",
                "Test designer",
                "Delivery planner",
                "Architecture reviewer",
              ].map((a) => (
                <option key={a}>{a}</option>
              ))}
            </select>
          </Field>
        </div>
        <div className="chat-history">
          {messages.length ? (
            messages.map((m, i) => (
              <div
                className={
                  "chat-message " + (m.role === "You" ? "user-message" : "")
                }
                key={i}
              >
                <Badge>{m.role}</Badge>
                <p>{m.text}</p>
              </div>
            ))
          ) : (
            <Empty text="Choose an assistant and start a focused conversation." />
          )}
        </div>
        <textarea
          aria-label="Copilot prompt"
          value={promptText}
          onChange={(e) => setPrompt(e.target.value)}
        />
        <button className="primary" onClick={() => run(ask)}>
          <Sparkles size={16} /> Ask assistant
        </button>
      </Panel>
      <Panel title="Friday spaces">
        <p className="muted">
          Connect your Friday daemon in Integration Hub. Space chat streams
          here; workflow signals use the approved action console.
        </p>
        <button
          disabled={!friday?.enabled}
          onClick={() =>
            run(async () =>
              setSpaces(
                await post("/integrations/" + friday!.id + "/read", {
                  path: "/api/workspaces",
                }),
              ),
            )
          }
        >
          List spaces
        </button>
        {spaces !== null && <JsonView value={spaces} />}
        <Field label="Workspace ID">
          <input
            value={workspace}
            onChange={(e) => setWorkspace(e.target.value)}
          />
        </Field>
        <button
          disabled={!friday?.enabled || !workspace}
          onClick={() =>
            run(async () => {
              const csrf = await request("/csrf");
              const response = await fetch(
                "/api/integrations/" + friday!.id + "/friday/chat",
                {
                  method: "POST",
                  headers: {
                    "Content-Type": "application/json",
                    "X-XSRF-TOKEN": csrf.token,
                  },
                  body: pretty({
                    workspaceId: workspace,
                    body: { message: promptText },
                  }),
                },
              );
              if (!response.ok) throw new Error("Friday chat failed");
              const reader = response.body!.getReader();
              const decoder = new TextDecoder();
              let text = "";
              const index = messages.length;
              setMessages((old) => [...old, { role: "Friday", text: "" }]);
              while (true) {
                const item = await reader.read();
                if (item.done) break;
                text += decoder.decode(item.value, { stream: true });
                setMessages((old) =>
                  old.map((m, i) => (i === index ? { ...m, text } : m)),
                );
              }
            })
          }
        >
          Stream Friday chat
        </button>
      </Panel>
    </div>
  );
}
export function Admin({ run }: PageProps) {
  const [audit, setAudit] = useState<unknown>(null);
  return (
    <>
      <div className="two-cols">
        <Panel title="Authentication and authorization">
          <h2>One entry point. Explicit permissions.</h2>
          <Records
            rows={[
              {
                role: "Admin",
                access: "All modules, connection settings and vault rotation",
              },
              {
                role: "Reviewer",
                access:
                  "Read workspace, approve contracts and external actions",
              },
              {
                role: "Developer",
                access:
                  "Design contracts, requests, tests and proposed actions",
              },
              {
                role: "Tester",
                access: "Manage tests, capacity data and proposed actions",
              },
            ]}
            columns={["role", "access"]}
          />
          <p className="muted">
            Account passwords are supplied by the server administrator. Session
            cookies are HttpOnly and SameSite=Strict. State changes require CSRF
            tokens. Connector role lists add a second authorization boundary.
          </p>
        </Panel>
        <Panel title="Operational settings">
          <Records
            rows={[
              {
                setting: "Database",
                value: "File-based H2 with Flyway migrations",
              },
              {
                setting: "Credential encryption",
                value: "AES-256-GCM using server VAULT_KEY",
              },
              { setting: "H2 console", value: "Disabled" },
              {
                setting: "External destinations",
                value: "Server-controlled INTEGRATION_HOSTS allowlist",
              },
              {
                setting: "Friday daemon",
                value: "Server-controlled FRIDAY_URL",
              },
            ]}
            columns={["setting", "value"]}
          />
          <p className="muted">
            Back up the database and vault key together. Use HTTPS and a reverse
            proxy when exposing the application beyond localhost.
          </p>
        </Panel>
      </div>
      <Panel title="Audit trail">
        <button
          onClick={() => run(async () => setAudit(await request("/audit")))}
        >
          Load audit trail
        </button>
        {audit !== null && <JsonView value={audit} />}
      </Panel>
    </>
  );
}
export function Utilities() {
  const [tool, setTool] = useState("JSON format"),
    [left, setLeft] = useState('{"hello":"world"}'),
    [right, setRight] = useState(""),
    [output, setOutput] = useState(""),
    [password, setPassword] = useState("");
  async function execute() {
    try {
      let result = "";
      switch (tool) {
        case "JSON format":
          result = pretty(JSON.parse(left));
          break;
        case "YAML format":
          result = yaml.dump(yaml.load(left));
          break;
        case "XML format": {
          const doc = new DOMParser().parseFromString(left, "application/xml");
          if (doc.querySelector("parsererror")) throw new Error("Invalid XML");
          result = new XMLSerializer()
            .serializeToString(doc)
            .replace(/></g, ">\n<");
          break;
        }
        case "Text compare":
        case "XML compare":
        case "YAML compare":
        case "JSON compare": {
          const normalize = (text: string) =>
            tool === "JSON compare"
              ? pretty(JSON.parse(text))
              : tool === "YAML compare"
                ? yaml.dump(yaml.load(text))
                : tool === "XML compare"
                  ? new XMLSerializer()
                      .serializeToString(
                        new DOMParser().parseFromString(
                          text,
                          "application/xml",
                        ),
                      )
                      .replace(/></g, ">\n<")
                  : text;
          const a = normalize(left),
            b = normalize(right);
          result = diffLines(a, b)
            .map((p) => (p.added ? "+ " : p.removed ? "- " : "  ") + p.value)
            .join("");
          break;
        }
        case "Base64 encode":
          result = btoa(String.fromCharCode(...new TextEncoder().encode(left)));
          break;
        case "Base64 decode":
          result = new TextDecoder().decode(
            Uint8Array.from(atob(left), (c) => c.charCodeAt(0)),
          );
          break;
        case "JWT decode": {
          const parts = left.split(".");
          const decode = (s: string) =>
            JSON.parse(
              new TextDecoder().decode(
                Uint8Array.from(
                  atob(
                    s
                      .replace(/-/g, "+")
                      .replace(/_/g, "/")
                      .padEnd(Math.ceil(s.length / 4) * 4, "="),
                  ),
                  (c) => c.charCodeAt(0),
                ),
              ),
            );
          result = pretty({
            header: decode(parts[0]),
            payload: decode(parts[1]),
            notice: "Decoded only. Signature has NOT been verified.",
          });
          break;
        }
        case "SHA-256":
          result = Array.from(
            new Uint8Array(
              await crypto.subtle.digest(
                "SHA-256",
                new TextEncoder().encode(left),
              ),
            ),
          )
            .map((b) => b.toString(16).padStart(2, "0"))
            .join("");
          break;
        case "Regex": {
          result = pretty(await safeRegex(left, right));
          break;
        }
        case "AES encrypt":
        case "AES decrypt": {
          if (password.length < 12)
            throw new Error("Use a passphrase of at least 12 characters");
          const encode = (x: Uint8Array) => btoa(String.fromCharCode(...x));
          const decode = (s: string) =>
            Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
          const encrypt = tool === "AES encrypt";
          const parts = encrypt ? [] : left.split(".");
          const salt = encrypt
            ? crypto.getRandomValues(new Uint8Array(16))
            : decode(parts[0]);
          const iv = encrypt
            ? crypto.getRandomValues(new Uint8Array(12))
            : decode(parts[1]);
          const material = await crypto.subtle.importKey(
            "raw",
            new TextEncoder().encode(password),
            "PBKDF2",
            false,
            ["deriveKey"],
          );
          const key = await crypto.subtle.deriveKey(
            { name: "PBKDF2", salt, iterations: 210000, hash: "SHA-256" },
            material,
            { name: "AES-GCM", length: 256 },
            false,
            ["encrypt", "decrypt"],
          );
          result = encrypt
            ? [
                encode(salt),
                encode(iv),
                encode(
                  new Uint8Array(
                    await crypto.subtle.encrypt(
                      { name: "AES-GCM", iv },
                      key,
                      new TextEncoder().encode(left),
                    ),
                  ),
                ),
              ].join(".")
            : new TextDecoder().decode(
                await crypto.subtle.decrypt(
                  { name: "AES-GCM", iv },
                  key,
                  decode(parts[2]),
                ),
              );
          break;
        }
        default:
          result = left;
      }
      setOutput(result);
    } catch (e) {
      setOutput(String(e));
    }
  }
  return (
    <>
      <div className="utility-tabs">
        {[
          "JSON format",
          "YAML format",
          "XML format",
          "Text compare",
          "JSON compare",
          "YAML compare",
          "XML compare",
          "Base64 encode",
          "Base64 decode",
          "JWT decode",
          "SHA-256",
          "Regex",
          "AES encrypt",
          "AES decrypt",
        ].map((t) => (
          <button
            className={tool === t ? "selected" : ""}
            key={t}
            onClick={() => setTool(t)}
          >
            {t}
          </button>
        ))}
      </div>
      <div className="two-cols">
        <Panel title="Input">
          <textarea
            aria-label="Utility input"
            className="code tall"
            value={left}
            onChange={(e) => setLeft(e.target.value)}
          />
          {(tool.includes("compare") || tool === "Regex") && (
            <Field
              label={tool === "Regex" ? "Regex pattern" : "Comparison input"}
            >
              <textarea
                className="code"
                value={right}
                onChange={(e) => setRight(e.target.value)}
              />
            </Field>
          )}
          {tool.startsWith("AES") && (
            <Field label="Passphrase">
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </Field>
          )}
          <button className="primary" onClick={execute}>
            Run {tool}
          </button>
        </Panel>
        <Panel title="Output">
          <JsonView value={output} />
          <button onClick={() => navigator.clipboard.writeText(output)}>
            <Copy size={14} /> Copy
          </button>
        </Panel>
      </div>
    </>
  );
}

function ConnectorEditor({
  value,
  onClose,
  onSave,
}: {
  value: Row;
  onClose: () => void;
  onSave: (c: Row) => Promise<void>;
}) {
  const [config, setConfig] = useState<Row>({ ...value }),
    [error, setError] = useState(""),
    [credential, setCredential] = useState(""),
    [busy, setBusy] = useState(false);
  const set = (key: string, v: unknown) =>
    setConfig((c) => ({ ...c, [key]: v }));
  return (
    <div className="modal-backdrop">
      <section className="modal" role="dialog" aria-label="Configure connector">
        <header>
          <div>
            <Badge tone="purple">INTEGRATION HUB</Badge>
            <h2 style={{ marginTop: 10 }}>Configure {value.name}</h2>
          </div>
          <button onClick={onClose}>Close</button>
        </header>
        <div className="two-cols compact">
          <Field label="Display name">
            <input
              value={config.name}
              onChange={(e) => set("name", e.target.value)}
            />
          </Field>
          <Field label="Provider">
            <select
              value={config.provider}
              onChange={(e) => set("provider", e.target.value)}
            >
              {Object.keys(providerDescriptions).map((p) => (
                <option key={p}>{p}</option>
              ))}
            </select>
          </Field>
          <Field label="Base URL">
            <input
              value={config.baseUrl}
              onChange={(e) => set("baseUrl", e.target.value)}
            />
          </Field>
          <Field label="Authentication">
            <select
              value={config.authType}
              onChange={(e) => set("authType", e.target.value)}
            >
              {["NONE", "BEARER", "BASIC", "API_KEY", "OAUTH2_CLIENT"].map(
                (a) => (
                  <option key={a}>{a}</option>
                ),
              )}
            </select>
          </Field>
          <Field label="Secret reference">
            <input
              value={config.secretRef || ""}
              placeholder="devin-token"
              onChange={(e) => set("secretRef", e.target.value)}
            />
          </Field>
          <Field label="New credential (leave blank to keep existing)">
            <input
              type="password"
              autoComplete="new-password"
              value={credential}
              onChange={(e) => setCredential(e.target.value)}
            />
          </Field>
          {config.authType === "API_KEY" && (
            <Field label="API key header">
              <input
                value={config.headerName || "X-API-Key"}
                onChange={(e) => set("headerName", e.target.value)}
              />
            </Field>
          )}
          {config.authType === "OAUTH2_CLIENT" && (
            <>
              <Field label="Client ID">
                <input
                  value={config.clientId || ""}
                  onChange={(e) => set("clientId", e.target.value)}
                />
              </Field>
              <Field label="Token path (same host)">
                <input
                  value={config.tokenPath || "/oauth/token"}
                  onChange={(e) => set("tokenPath", e.target.value)}
                />
              </Field>
              <Field label="Scopes">
                <input
                  value={config.scope || ""}
                  onChange={(e) => set("scope", e.target.value)}
                />
              </Field>
            </>
          )}
          {config.provider === "friday" && (
            <>
              <Field label="Friday workspace ID">
                <input
                  value={config.workspaceId || ""}
                  onChange={(e) => set("workspaceId", e.target.value)}
                />
              </Field>
              {[
                "specApproved",
                "prCreated",
                "testsFailed",
                "sprintOverCapacity",
              ].map((event) => (
                <Field label={event + " signal ID"} key={event}>
                  <input
                    value={config.eventSignals?.[event] || ""}
                    onChange={(e) =>
                      set("eventSignals", {
                        ...config.eventSignals,
                        [event]: e.target.value,
                      })
                    }
                  />
                </Field>
              ))}
            </>
          )}
          {config.provider === "devin" && (
            <Field label="Devin organization ID">
              <input
                value={config.organizationId || ""}
                onChange={(e) => set("organizationId", e.target.value)}
              />
            </Field>
          )}
          {["openai", "ollama", "azure", "anthropic"].includes(
            config.provider,
          ) && (
            <>
              <Field label="Model / deployment">
                <input
                  value={config.model || ""}
                  onChange={(e) => set("model", e.target.value)}
                />
              </Field>
              <Field label="Chat endpoint path">
                <input
                  value={config.chatPath || "/v1/chat/completions"}
                  onChange={(e) => set("chatPath", e.target.value)}
                />
              </Field>
            </>
          )}
        </div>
        <Field label="Allowed workspace roles">
          <div className="toolbar">
            {["ADMIN", "REVIEWER", "DEVELOPER", "TESTER"].map((role) => (
              <label key={role}>
                <input
                  type="checkbox"
                  checked={(config.allowedRoles || []).includes(role)}
                  onChange={(e) =>
                    set(
                      "allowedRoles",
                      e.target.checked
                        ? [...(config.allowedRoles || []), role]
                        : (config.allowedRoles || []).filter(
                            (r: string) => r !== role,
                          ),
                    )
                  }
                />{" "}
                {role}
              </label>
            ))}
          </div>
        </Field>
        <label>
          <input
            type="checkbox"
            checked={!!config.enabled}
            onChange={(e) => set("enabled", e.target.checked)}
          />{" "}
          Enable this connection
        </label>
        <p className="muted">
          Credentials are sent only to the configured destination. Add its host
          to the server allowlist. OAuth2 client credentials are obtained
          server-side; interactive OAuth consent is not part of this connector
          type.
        </p>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <footer>
          <button onClick={onClose}>Cancel</button>
          <button
            className="primary"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              try {
                if (credential) {
                  if (!config.secretRef)
                    throw new Error("Choose a secret reference");
                  await request(
                    "/integrations/secrets/" +
                      encodeURIComponent(config.secretRef),
                    { method: "PUT", body: pretty({ value: credential }) },
                  );
                  setCredential("");
                }
                await onSave(config);
                onClose();
              } catch (e) {
                setError(String(e));
              } finally {
                setBusy(false);
              }
            }}
          >
            Save connection
          </button>
        </footer>
      </section>
    </div>
  );
}
