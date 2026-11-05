import EndpointBrowser from "./EndpointBrowser";
import SwaggerPreview from "./SwaggerPreview";
import ContractCompare from "./ContractCompare";
import { listOperations } from "./endpoint-model";
import TestGeneration from "./TestGeneration";
import { exportDrawio, importDrawio } from "./drawio";
import { parseCurl, snippet as makeSnippet, safeRegex } from "./request-tools";
import { useState, useEffect, useMemo, useRef } from "react";
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
export function Workspace({ data, spec, run, selectSpec, setPage, roles }: PageProps) {
  const [source, setSource] = useState(initialSpec),
    [result, setResult] = useState<unknown>(null),
    [actionBusy,setActionBusy] = useState(false),
    [actionMessage,setActionMessage] = useState(""),
    [collectionReady,setCollectionReady] = useState(false),
    [showEndpointDetails, setShowEndpointDetails] = useState(false),
    [url, setUrl] = useState("https://httpbin.org/get");
  useEffect(() => {
    setSource(spec?.source || (spec?.document ? yaml.dump(spec.document) : initialSpec));
  }, [spec?.id, spec?.revision]);
  const { preview, sourceError } = useMemo(() => {
    try {
      const parsed = yaml.load(source) as Row;
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed) ||
          !String(parsed.openapi || "").startsWith("3.") ||
          !parsed.paths || typeof parsed.paths !== "object" || Array.isArray(parsed.paths))
        throw new Error("An OpenAPI 3 document with a paths object is required.");
      return { preview: parsed, sourceError: "" };
    } catch (error) {
      return { preview: undefined, sourceError: String(error) };
    }
  }, [source]);
  const operations=listOperations(preview);
  const dirty=!!spec&&(!preview||source!==(spec.source||(spec.document?yaml.dump(spec.document):initialSpec)));
  const canEdit=roles?roles.some(r=>["ROLE_ADMIN","ROLE_DEVELOPER"].includes(r)):true;
  const canApprove=roles?.some(r=>["ROLE_ADMIN","ROLE_REVIEWER"].includes(r))??false;
  const canCollect=roles?roles.some(r=>["ROLE_ADMIN","ROLE_DEVELOPER","ROLE_TESTER"].includes(r)):true;
  async function action(kind:"draft"|"IN_REVIEW"|"APPROVED"|"collection") {
    if(!spec)return;setActionBusy(true);setActionMessage("");setCollectionReady(false);
    try{const saved=await run(()=>post("/workspace/"+spec.id+"/"+(kind==="draft"?"draft":kind==="collection"?"collection":"approval"),{expectedRevision:spec.revision,...(["IN_REVIEW","APPROVED"].includes(kind)?{status:kind}:{})}));if(saved){setActionMessage(kind==="collection"?`Collection ready: ${saved.name}. ${saved.item?.length||0} requests available in Request Studio.`:kind==="draft"?"Contract is now an editable draft.":kind==="IN_REVIEW"?"Review requested for the saved contract.":"Contract approved.");setCollectionReady(kind==="collection");}}
    finally{setActionBusy(false);}
  }
  return (
    <>
      <div className="two-cols contract-grid">
        <Panel
          title="Contract workspace"
          action={<Badge>{spec?.status || "DRAFT"}</Badge>}
        >
          <Field label="OpenAPI JSON or YAML">
            <textarea
              aria-label="OpenAPI source"
              className="code tall"
              value={source}
              onChange={(e) => setSource(e.target.value)}
            />
          </Field>
          {sourceError && <p role="alert" className="error">{sourceError}</p>}
          <p className="workspace-dirty-message">A file upload prepares all three modules automatically. Pasted edits preview immediately; import or save them to update collections and tests.</p>
          <div className="toolbar">
            <label className="button">
              <Upload size={15} /> Upload & prepare
              <input
                type="file"
                accept=".json,.yaml,.yml"
                hidden
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  if(file){const uploaded=await file.text();setSource(uploaded);setActionMessage("");const saved=await run(()=>post("/workspace/import",{source:uploaded}));if(saved){selectSpec?.(saved.id);setActionMessage("Upload prepared: endpoint details, Request Studio collection, test cases, suites and plan are ready.");}}
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
              disabled={!preview}
              onClick={() => run(async () => {
                const saved = await post("/workspace/import", { source });
                selectSpec?.(saved.id);
                setActionMessage("Contract imported. Collection, cases, suites and plan are ready.");
                return saved;
              })}
            >
              Import contract
            </button>
            <button disabled={!preview || !spec}
              onClick={() => run(async () => {
                const saved = await save("specs", {
                  ...spec, source, document: preview, name: preview?.info?.title || "API",
                  version: preview?.info?.version || "1.0", status: "DRAFT",
                });
                selectSpec?.(saved.id);
                setActionMessage("Contract saved. Collection and tests have been synchronized without overwriting earlier edits.");
                return saved;
              })}>Save selected contract</button>
            <button onClick={() => spec && setSource(spec.source||yaml.dump(spec.document || {}))}>
              Load selected
            </button>
          </div>
        </Panel>
        <Panel title="Contract review">
          <h2>{preview?.info?.title || "Enter a valid API contract"}</h2>
          <p className="muted">
            Version {preview?.info?.version || "—"} · {operations.length} operations
          </p>
          <div className="toolbar">
            <button disabled={!spec||dirty||actionBusy||!canEdit} title={dirty?"Save the current YAML first":"Return the selected saved contract to DRAFT"} onClick={()=>void action("draft")}>Edit as draft</button>
            <button disabled={!spec||dirty||actionBusy||!canEdit||!["DRAFT","REJECTED"].includes(spec?.status||"DRAFT")} onClick={()=>void action("IN_REVIEW")}>Request review</button>
            <button disabled={!spec||dirty||actionBusy||!canApprove||spec.status!=="IN_REVIEW"} title={!canApprove?"Reviewer or administrator role required":"Approve the contract currently in review"} onClick={()=>void action("APPROVED")}><Check size={15}/> Approve</button>
            <button disabled={!spec||dirty||actionBusy||!canCollect} onClick={()=>void action("collection")}>Create collection</button>
          </div>
          {dirty&&<p className="workspace-dirty-message">You are previewing unsaved YAML. Import as a new API or save the selected contract before using review and collection actions.</p>}
          {actionMessage&&<div className="workspace-action-message" aria-live="polite">{actionMessage}{collectionReady&&<button onClick={()=>setPage("Request Studio")}>Open Request Studio</button>}</div>}
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
      <Panel
        title="Endpoint preview"
        action={<button aria-pressed={showEndpointDetails} onClick={() => setShowEndpointDetails((show) => !show)}>
          {showEndpointDetails ? "Show Swagger UI" : "Show endpoint details"}
        </button>}
      >
        {showEndpointDetails
          ? <EndpointBrowser document={preview} onUseUrl={setUrl} />
          : preview
            ? <SwaggerPreview document={preview} run={run} />
            : <p className="muted">Enter a valid OpenAPI contract to open Swagger UI.</p>}
      </Panel>
      <Panel title="Compare complete contract files"><ContractCompare source={source} document={preview} contracts={data.specs||[]} currentId={spec?.id}/></Panel>
      {result !== null && (
        <Panel title="Results">
          <JsonView value={result} />
        </Panel>
      )}
    </>
  );
}
export { default as Client } from "./RequestStudio";
export function TestLibrary({ data, spec, run, edit, reload }: PageProps) {
  const [search, setSearch] = useState(""),
    [type, setType] = useState(""),
    [result, setResult] = useState<unknown>(null),
    [selected, setSelected] = useState<string[]>([]),
    [base, setBase] = useState("https://httpbin.org"),
    [executionVariables, setExecutionVariables] = useState("{}"),
    [testEnvironmentId, setTestEnvironmentId] = useState(""),
    [tab, setTab] = useState("Cases");
  useEffect(() => { setSelected([]); setResult(null); }, [spec?.id]);
  const scoped = (kind: string) => (data[kind] || []).filter(row => (!row.contractHash || !spec?.contractHash || row.contractHash===spec.contractHash) && (row.specId === spec?.id || (!row.specId && (row.caseIds || []).some((id: string) => data.tests?.some(t => t.id === id && t.specId === spec?.id)))));
  useEffect(()=>{const environment=data.environments?.find(e=>e.id===testEnvironmentId);const value=environment?.values?.find((v:Row)=>v.key==="baseUrl"&&v.enabled!==false&&!v.secret)?.value;if(value)setBase(value);},[testEnvironmentId,data.environments]);
  function executionEnv() {
    const extra = JSON.parse(executionVariables);
    if (!extra || typeof extra !== "object" || Array.isArray(extra) || Object.values(extra).some(v=>typeof v!=="string")) throw new Error("Execution variables must be a JSON object of string values");
    const global=data.environments?.find(e=>e.scope==="global");
    return {...extra,baseUrl:base,...(testEnvironmentId?{environmentId:testEnvironmentId}:{}),...(global?{globalProfileId:global.id}:{})};
  }
  const visibleIds = new Set(scoped("tests").map(t => t.id));
  const selectedIds = selected.filter(id => visibleIds.has(id));
  const rows = scoped("tests").filter(
    (t) =>
      (!type || t.type === type) &&
      pretty(t).toLowerCase().includes(search.toLowerCase()),
  );
  return (
    <>
      <TestGeneration data={data} spec={spec} reload={reload}/>
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
                specId: spec?.id,
                specRevision: spec?.revision,
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
          <Field label="Saved execution environment"><select aria-label="Test execution environment" value={testEnvironmentId} onChange={e=>setTestEnvironmentId(e.target.value)}><option value="">Session variables only</option>{(data.environments||[]).filter(e=>e.scope==="environment").map(e=><option key={e.id} value={e.id}>{e.name}</option>)}</select></Field>
          <Field label="Execution variables (session only, JSON)"><input aria-label="Execution variables" type="password" value={executionVariables} onChange={e=>setExecutionVariables(e.target.value)} placeholder='{"bearerToken":"..."}' autoComplete="off"/></Field>
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
                  for (const id of selectedIds)
                    results.push(
                      await post("/tests/" + id + "/run", executionEnv()),
                    );
                  setResult(results);
                })
              }
            >
              Run selected ({selectedIds.length})
            </button>
          </div>
          <p className="muted">
            Generated cases are editable drafts. Review payloads, boundary
            values and expected statuses before execution. Regeneration
            preserves existing edits.
          </p>
          <Records
            rows={rows}
            columns={["title", "type", "priority", "status", "specRevision", "linkedJiraKey"]}
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
                        await post("/tests/" + r.id + "/run", executionEnv()),
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
                        await save("tests", { ...c, specId: spec?.id, specRevision: spec?.revision });
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
            rows={(data.runs || []).filter(r => r.specId === spec?.id || visibleIds.has(r.caseId))}
            columns={["caseId", "caseRevision", "status", "updatedAt"]}
            actions={(r) => (
              <button onClick={() => setResult(r)}>Details</button>
            )}
          />
        </Panel>
      ) : (
        <Panel title={tab}>
          <Records
            rows={scoped(tab.toLowerCase())}
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
                        executionEnv(),
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
                caseIds: selectedIds,
                specId: spec?.id,
                specRevision: spec?.revision,
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
    [jiraId, setJiraId] = useState(""),
    [status, setStatus] = useState("To Do");
  const connections = data.integrations?.filter((c) => c.provider === "jira") || [];
  const connection = connections.find(c => c.id === jiraId) || connections.find(c => c.enabled) || connections[0];
  return (
    <>
      <Panel title="Jira search">
        {connections.length > 0 && <Field label="Jira connection">
          <select value={connection?.id || ""} onChange={event => setJiraId(event.target.value)}>
            {connections.map(c => <option key={c.id} value={c.id}>{c.name}{c.enabled ? "" : " · disabled"}</option>)}
          </select>
        </Field>}
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
                  await post("/integrations/" + connection!.id + "/jira/search", { jql }),
                ),
              )
            }
          >
            Run JQL
          </button>
          <button disabled={!connection?.enabled} onClick={() => run(async () => {
            const account = await post("/integrations/" + connection!.id + "/jira/check");
            setResult({ connectedAs: account.displayName });
          })}>Test Jira connection</button>
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
                const paths = Object.keys(spec?.document?.paths || {});
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
  stylus: "Stylus via a configured AI request",
  copilot: "Microsoft Copilot via a configured AI request",
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
          (typeof response.response === "string" ? response.response : response.response !== undefined ? pretty(response.response) : "") ||
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
                  c.aiChatEnabled || ["openai", "azure", "ollama", "anthropic"].includes(
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
    [busy, setBusy] = useState(false),
    [diagnostic, setDiagnostic] = useState("");
  const [aiUrl, setAiUrl] = useState(() => value.aiChatEnabled
    ? (value.baseUrl || "").replace(/\/$/, "") + (value.chatPath || "") : "");
  const [aiTemplate, setAiTemplate] = useState(() => pretty(value.aiRequestTemplate || {
    messages: [{ role: "user", content: "{{prompt}}" }], stream: false,
  }));
  const [connectionTestPrompt, setConnectionTestPrompt] = useState("Reply with OK.");
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
              onChange={(event) => {
                const provider = event.target.value;
                setConfig(current => ({
                  ...current, provider,
                  aiChatEnabled: provider === "jira" || provider === "github" ? false : current.aiChatEnabled,
                  authType: provider === "jira" ? "JIRA_TOKEN" : current.authType === "JIRA_TOKEN" ? "BEARER" : current.authType,
                }));
                setCredential("");
              }}
            >
              {Object.keys(providerDescriptions).map((p) => (
                <option key={p}>{p}</option>
              ))}
            </select>
          </Field>
          {(!config.aiChatEnabled || config.provider === "jira") && <Field label={config.provider === "jira" ? "Jira site or API gateway URL" : "Base URL"}>
            <input
              value={config.baseUrl}
              onChange={(e) => set("baseUrl", e.target.value)}
            />
          </Field>}
          <Field label="Authentication">
            <select
              aria-label="Authentication"
              value={config.authType}
              onChange={(e) => {set("authType", e.target.value);setCredential("");setDiagnostic("");}}
            >
              {["NONE", "BEARER", "BASIC", "AUTHORIZATION", ...(config.provider === "jira" ? ["JIRA_TOKEN"] : []), "API_KEY", "OAUTH2_CLIENT", "WINDOWS"].map(
                (a) => (
                  <option key={a}>{a}</option>
                ),
              )}
            </select>
          </Field>
          {config.provider === "jira" && <>
            <Field label="Jira deployment">
              <select value={config.jiraDeployment || "cloud"} onChange={event => {
                const deployment = event.target.value;
                setConfig(current => ({
                  ...current, jiraDeployment: deployment, authType: deployment === "cloud" ? "JIRA_TOKEN" : "BEARER",
                }));
                setCredential("");
              }}><option value="cloud">Jira Cloud</option><option value="data-center">Jira Data Center</option></select>
            </Field>
            {config.authType === "JIRA_TOKEN" && <Field label="Atlassian account email">
              <input type="email" value={config.jiraEmail || ""} onChange={event => set("jiraEmail", event.target.value)} />
            </Field>}
            <p className="muted">Cloud profile API tokens use JIRA_TOKEN with your Atlassian email. For scoped Cloud tokens, use https://api.atlassian.com/ex/jira/CLOUD_ID. Data Center personal access tokens use BEARER.</p>
          </>}
          <Field label="Secret reference">
            <input
              value={config.secretRef || ""}
              placeholder="devin-token"
              onChange={(e) => set("secretRef", e.target.value)}
            />
          </Field>
          {config.authType === "WINDOWS" && <>
            <Field label="Windows protocol"><select aria-label="Windows protocol" value={config.windowsProtocol || "NEGOTIATE"} onChange={e=>set("windowsProtocol",e.target.value)}><option value="NEGOTIATE">Windows Negotiate (Kerberos / SSPI)</option><option value="NTLM">Windows NTLM</option></select></Field>
            <Field label="Windows identity"><select aria-label="Windows identity" value={config.windowsUseCurrentUser === false ? "explicit" : "current"} onChange={e=>{set("windowsUseCurrentUser",e.target.value==="current");setCredential("");setDiagnostic("");}}><option value="current">Use the application process Windows account</option><option value="explicit">Use a configured Windows username/password</option></select></Field>
            {config.windowsUseCurrentUser === false && <Field label="Windows username"><input aria-label="Windows username" value={config.windowsUsername || ""} placeholder="DOMAIN\\username or user@domain" onChange={e=>set("windowsUsername",e.target.value)}/></Field>}
            <p className="muted">Optional per connection. Requires Windows curl.exe with SSPI, HTTPS and a server-configured WINDOWS_AUTH_HOSTS allowlist. The current account is the account running Spring Boot/IntelliJ, not a remote browser user's Windows account. Browser SSO and streaming are separate capabilities.</p>
            {config.id && <button type="button" disabled={busy} onClick={async()=>{setBusy(true);setError("");try{await post("/integrations/"+config.id+"/windows/check");setDiagnostic("Windows runtime ready. Destination login has not been tested. Use the connector read console to verify access.");}catch(e){setError(String(e));}finally{setBusy(false);}}}>Check saved Windows configuration</button>}
          </>}
          {(config.authType !== "WINDOWS" || config.windowsUseCurrentUser === false) && <Field label={
            config.authType === "WINDOWS" ? "Windows password (encrypted in vault)" :
            config.authType === "AUTHORIZATION" ? "Authorization header value (leave blank to keep existing)" :
            config.authType === "JIRA_TOKEN" ? "Jira API token (leave blank to keep existing)" :
            "New credential (leave blank to keep existing)"}>
            <input
              type="password"
              autoComplete="new-password"
              value={credential}
              onChange={(e) => setCredential(e.target.value)}
            />
          </Field>}
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
        {config.provider !== "jira" && config.provider !== "github" && <Panel title="AI request from Network tab">
          <label><input type="checkbox" checked={!!config.aiChatEnabled} onChange={event => {
            const enabled = event.target.checked;
            setConfig(current => ({
              ...current, aiChatEnabled: enabled,
              authType: enabled && current.authType === "NONE" ? "AUTHORIZATION" : current.authType,
            }));
          }} /> Use a configurable AI request</label>
          {config.aiChatEnabled && <>
            <Field label="Full AI request URL"><input type="url" value={aiUrl} onChange={event => setAiUrl(event.target.value)} /></Field>
            <Field label="AI request JSON template"><textarea className="code" value={aiTemplate} onChange={event => setAiTemplate(event.target.value)} /></Field>
            <p className="muted">Use the request URL and JSON payload. Replace the message text with {"{{prompt}}"} and put the Authorization value in the encrypted credential field. If the response uses SSE, choose the JSON pointer for each text delta.</p>
            <Field label="AI response format">
              <select value={config.aiResponseFormat || "json"} onChange={event => set("aiResponseFormat", event.target.value)}>
                <option value="json">JSON / text</option><option value="sse">Server-sent events (text deltas)</option>
              </select>
            </Field>
            <Field label="AI response JSON pointer">
              <input value={config.aiResponsePointer || ""} placeholder={config.aiResponseFormat === "sse" ? "/choices/0/delta/content" : "/choices/0/message/content"} onChange={event => set("aiResponsePointer", event.target.value)} />
            </Field>
            {value.id && value.enabled && value.aiChatEnabled && <div className="toolbar">
              <input aria-label="AI test prompt" value={connectionTestPrompt} onChange={event => setConnectionTestPrompt(event.target.value)} />
              <button type="button" disabled={busy} onClick={async () => {
                setBusy(true); setError(""); setDiagnostic("");
                try {
                  const result = await post("/integrations/" + value.id + "/ai", { prompt: connectionTestPrompt });
                  setDiagnostic("Provider responded. " + String(typeof result.response === "string" ? result.response : pretty(result.response)).slice(0, 240));
                } catch (error) { setError(String(error)); }
                finally { setBusy(false); }
              }}>Test saved AI connection</button>
            </div>}
          </>}
        </Panel>}
        <Panel title="AI test generation">
          <label><input type="checkbox" checked={!!config.testGenerationEnabled} onChange={e => set("testGenerationEnabled",e.target.checked)}/> Allow this connection to generate test drafts</label>
          {config.testGenerationEnabled && config.aiChatEnabled && <p className="muted">Test generation uses the AI request template and response mapping above.</p>}
          {config.testGenerationEnabled && !config.aiChatEnabled && <>
            <Field label="Test generation protocol"><select value={config.testGenerationProtocol || (["openai","azure","anthropic","ollama"].includes(config.provider) ? config.provider : "custom-json")} onChange={e=>set("testGenerationProtocol",e.target.value)}>
              <option value="openai">OpenAI-compatible JSON API</option><option value="azure">Azure OpenAI-compatible API</option><option value="anthropic">Anthropic Messages</option><option value="ollama">Ollama JSON API</option><option value="custom-json">Approved enterprise JSON gateway</option>
            </select></Field>
            <Field label="Generation endpoint path"><input value={config.chatPath || ""} placeholder="/approved/chat/endpoint" onChange={e=>set("chatPath",e.target.value)}/></Field>
            <Field label="Generation model / deployment"><input value={config.model || ""} onChange={e=>set("model",e.target.value)}/></Field>
            <Field label="Prompt JSON field (enterprise gateway)"><input value={config.testPromptField || "prompt"} onChange={e=>set("testPromptField",e.target.value)}/></Field>
            <Field label="Response JSON pointer (optional)"><input value={config.testResponsePointer || ""} placeholder="/result/text" onChange={e=>set("testResponsePointer",e.target.value)}/></Field>
            <p className="muted">Friday, Stylus and Microsoft services require an approved API or gateway with matching request/response schemas. This does not automate their chat website. Private network restrictions still apply.</p>
          </>}
        </Panel>
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
        {diagnostic && <p role="status" className="muted">{diagnostic}</p>}
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
                const next = { ...config };
                if (next.aiChatEnabled) {
                  const url = new URL(aiUrl.trim());
                  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.hash) {
                    throw new Error("Enter an HTTP(S) AI endpoint without embedded credentials or a fragment");
                  }
                  next.baseUrl = url.origin;
                  next.chatPath = url.pathname + url.search;
                  next.aiRequestTemplate = JSON.parse(aiTemplate);
                  if (!next.aiRequestTemplate || typeof next.aiRequestTemplate !== "object" ||
                      Array.isArray(next.aiRequestTemplate) || !aiTemplate.includes("{{prompt}}")) {
                    throw new Error("Enter a JSON object template containing {{prompt}}");
                  }
                }
                if (credential && !(config.authType === "WINDOWS" && config.windowsUseCurrentUser !== false)) {
                  next.secretRef ||= "connector-" + crypto.randomUUID();
                  await request(
                    "/integrations/secrets/" +
                      encodeURIComponent(next.secretRef),
                    { method: "PUT", body: pretty({ value: credential }) },
                  );
                  setCredential("");
                }
                await onSave(next);
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
