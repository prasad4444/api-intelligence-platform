import {assignItemIds,itemAt,itemLocations,changeItems} from "./collection-tree";
import { useEffect, useRef, useState } from "react";
import {
  ArrowDownToLine,
  ChevronDown,
  ChevronRight,
  Code2,
  Copy,
  FileJson,
  Folder,
  FolderPlus,
  Globe,
  History,
  Layers,
  Loader2,
  MoreHorizontal,
  Play,
  Plus,
  Save,
  Search,
  Send,
  Settings2,
  Terminal,
  Trash2,
  X,
} from "lucide-react";
import type { PageProps } from "./App";
import { download, post, pretty, request, save, type Row } from "./api";
import { parseCurl, snippet } from "./request-tools";
import {
  buildRequest,
  evaluateChecks,
  flattenRequests,
  newDraft,
  pair,
  readItem,
  replaceItem,
  serializedItem,
  splitUrl,
  type Draft,
  type Pair,
  type CheckRule,
} from "./studio-model";
import "./request-studio.css";
const methods = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];
const sanitizeItems=(items:Row[]):Row[]=>items.map(i=>Array.isArray(i.item)?{name:i.name,item:sanitizeItems(i.item)}:{...serializedItem(readItem(i)),...(Array.isArray(i.response)?{response:i.response.map((r:Row)=>({...r,...(r.originalRequest?{originalRequest:serializedItem(readItem({request:r.originalRequest})).request}:{})}))}:{})});
const sizes = (n: number) =>
  n < 1024 ? n + " B" : (n / 1024).toFixed(1) + " KB";
function KeyValues({
  rows,
  onChange,
  label,
  description = false,
  secrets = false,
}: {
  rows: Pair[];
  onChange: (p: Pair[]) => void;
  label: string;
  description?: boolean;
  secrets?: boolean;
}) {
  const display = [...rows, pair()];
  function update(index: number, change: Partial<Pair>) {
    const next = display.map((p, i) => (i === index ? { ...p, ...change } : p));
    onChange(next.filter((p, i) => i !== next.length - 1 || p.key || p.value));
  }
  return (
    <div className="rs-kv-wrap">
      <table className={"rs-kv"+(secrets?" rs-has-secrets":"")}>
        <thead>
          <tr>
            <th aria-label="Enabled" />
            <th>KEY</th>
            <th>VALUE</th>
            {description && <th>DESCRIPTION</th>}{secrets && <th>TYPE</th>}
            <th />
          </tr>
        </thead>
        <tbody>
          {display.map((p, i) => (
            <tr key={i}>
              <td>
                <input
                  aria-label={`${label} ${i + 1} enabled`}
                  type="checkbox"
                  checked={p.enabled}
                  onChange={(e) => update(i, { enabled: e.target.checked })}
                />
              </td>
              <td>
                <input
                  aria-label={`${label} ${i + 1} key`}
                  value={p.key}
                  placeholder="Key"
                  onChange={(e) => update(i, { key: e.target.value })}
                />
              </td>
              <td>
                <input
                  aria-label={`${label} ${i + 1} value`}
                  type={p.secret?"password":"text"}
                  value={p.value}
                  placeholder={p.secret&&p.hasValue?"Saved secret · blank keeps current value":"Value"}
                  onChange={(e) => update(i, { value: e.target.value })}
                />
              </td>
              {secrets && <td><select aria-label={`${label} ${i+1} type`} value={p.secret?"secret":"text"} onChange={e=>update(i,{secret:e.target.value==="secret"})}><option value="text">Default</option><option value="secret">Secret</option></select></td>}
              {description && (
                <td>
                  <input
                    aria-label={`${label} ${i + 1} description`}
                    value={p.description || ""}
                    placeholder="Description"
                    onChange={(e) => update(i, { description: e.target.value })}
                  />
                </td>
              )}
              <td>
                {i < rows.length && (
                  <button
                    aria-label={`Remove ${label} ${i + 1}`}
                    onClick={() => onChange(rows.filter((_, n) => n !== i))}
                  >
                    <X size={13} />
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function JsonTree({
  value,
  name = "root",
  depth = 0,
}: {
  value: unknown;
  name?: string;
  depth?: number;
}) {
  const [open, setOpen] = useState(depth < 2);
  if (value && typeof value === "object")
    return (
      <div className="rs-tree-node">
        <button onClick={() => setOpen(!open)}>
          {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
          <b>{name}</b>
          <span>
            {Array.isArray(value)
              ? `[${value.length}]`
              : `{${Object.keys(value).length}}`}
          </span>
        </button>
        {open && (
          <div className="rs-tree-children">
            {Object.entries(value).map(([key, v]) => (
              <JsonTree key={key} name={key} value={v} depth={depth + 1} />
            ))}
          </div>
        )}
      </div>
    );
  return (
    <div className="rs-tree-leaf">
      <b>{name}:</b>{" "}
      <span className={typeof value === "string" ? "rs-string" : "rs-number"}>
        {JSON.stringify(value)}
      </span>
    </div>
  );
}
function ItemMenu({name,onAction}:{name:string;onAction:(action:string)=>void}) {return <details className="rs-menu"><summary aria-label={`Actions for ${name}`}><MoreHorizontal size={14}/></summary><div>{["Rename","Duplicate","Move","Delete"].map(action=><button key={action} className={action==="Delete"?"rs-danger":""} onClick={e=>{e.currentTarget.closest("details")?.removeAttribute("open");onAction(action);}}>{action}</button>)}</div></details>;}
function CollectionTree({items,collectionId,path=[],search,onOpen,onFolder,onManage}:{items:Row[];collectionId:string;path?:number[];search:string;onOpen:(item:Row,c:string,p:number[])=>void;onFolder:(c:string,p:number[])=>void;onManage:(action:string,c:string,path:number[],name:string)=>void}) {
 const [closed,setClosed]=useState<string[]>([]);
 return <>{items.map((item,i)=>{const itemPath=[...path,i],key=item["x-studio-id"]||itemPath.join(".");if(search&&!JSON.stringify(item).toLowerCase().includes(search.toLowerCase()))return null;
  if(Array.isArray(item.item))return <div key={key}><div className="rs-folder"><button onClick={()=>setClosed(old=>old.includes(key)?old.filter(x=>x!==key):[...old,key])}>{closed.includes(key)?<ChevronRight size={13}/>:<ChevronDown size={13}/>}<Folder size={14}/><span>{item.name}</span></button><button aria-label={`New request in ${item.name}`} onClick={()=>onFolder(collectionId,itemPath)}><Plus size={13}/></button><ItemMenu name={item.name} onAction={action=>onManage(action,collectionId,itemPath,item.name)}/></div>{!closed.includes(key)&&<div className="rs-nested"><CollectionTree items={item.item} collectionId={collectionId} path={itemPath} search={search} onOpen={onOpen} onFolder={onFolder} onManage={onManage}/></div>}</div>;
  return <div className="rs-item-row" key={key}><button className="rs-saved-request" onClick={()=>onOpen(item,collectionId,itemPath)}><span className={`rs-method ${item.request?.method?.toLowerCase()}`}>{item.request?.method||"GET"}</span><span>{item.name||"Untitled request"}</span></button><ItemMenu name={item.name||"Untitled request"} onAction={action=>onManage(action,collectionId,itemPath,item.name)}/></div>;
 })}</>;
}
export default function RequestStudio({ data, reload, roles }: PageProps) {
  const [tabs, setTabs] = useState<Draft[]>(() => [
    { ...newDraft(), name: "New request", url: "{{baseUrl}}/get" },
  ]);
  const [active, setActive] = useState(tabs[0].id);
  const draft = tabs.find((t) => t.id === active) || tabs[0];
  const [tab, setTab] = useState("Params"),
    [side, setSide] = useState("Collections"),
    [search, setSearch] = useState("");
  const [collapsed, setCollapsed] = useState<string[]>([]);
  const [responseTab, setResponseTab] = useState("Body"),
    [view, setView] = useState("Pretty"),
    [find, setFind] = useState("");
  const [responses, setResponses] = useState<Record<string, Row>>({});
  const [sending, setSending] = useState<string[]>([]),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState(""),
    [importText, setImportText] = useState(""),
    [importError, setImportError] = useState("");
  const [managedPath,setManagedPath]=useState<number[]|null>(null),[moveTo,setMoveTo]=useState("[]"),[managedRevision,setManagedRevision]=useState<number|undefined>();
  const [target, setTarget] = useState(""),
    [folderPath, setFolderPath] = useState<number[]>([]),
    [newName, setNewName] = useState(""),
    [saving, setSaving] = useState(false);
  type Profile={id:string;name:string;values:Pair[];scope:string;collectionId?:string;allowedHosts:string[];revision?:number};
  const [envs,setEnvs]=useState<Profile[]>([{id:"local",name:"Local development",scope:"environment",allowedHosts:["httpbin.org"],values:[pair("baseUrl","https://httpbin.org")]}]);
  const [envId,setEnvId]=useState(localStorage.getItem("studio-environment-id")||"local"),[editEnvId,setEditEnvId]=useState("local");
  useEffect(()=>{if(data.environments)setEnvs(old=>[...old.filter(e=>!e.revision),...data.environments as Profile[]]);},[data.environments]);
  useEffect(()=>{localStorage.setItem("studio-environment-id",envId);},[envId]);
  const environment=envs.find(e=>e.id===envId);
  const editingEnvironment=envs.find(e=>e.id===editEnvId);
  const globalProfile=envs.find(e=>e.scope==="global");
  const collectionProfile=envs.find(e=>e.scope==="collection"&&e.collectionId===draft.collectionId);
  function variableContext(collectionId?:string){
    const profiles=[globalProfile,envs.find(e=>e.scope==="collection"&&e.collectionId===collectionId),environment].filter(Boolean) as Profile[];
    const collection=data.collections?.find(c=>c.id===collectionId);
    const vars:Row={};
    const defaults=Object.fromEntries((collection?.variable||[]).filter((v:Row)=>!v.disabled).map((v:Row)=>[v.key,String(v.value||"")]));
    const deferred:string[]=[];
    if(!globalProfile)Object.assign(vars,defaults);
    for(const profile of profiles){for(const value of profile.values)if(value.enabled&&value.key){if(profile.revision&&value.secret&&value.hasValue&&!value.value){vars[value.key]="{{"+value.key+"}}";deferred.push(value.key);}else vars[value.key]=value.value;}if(profile.scope==="global")Object.assign(vars,defaults);}
    return {variables:vars,deferred,profileIds:{...(globalProfile?.revision?{globalProfileId:globalProfile.id}:{}),...(profiles.find(p=>p.scope==="collection"&&p.revision)?{collectionProfileId:profiles.find(p=>p.scope==="collection")!.id}:{}),...(environment?.revision?{environmentId:environment.id}:{}),collectionId}};
  }
  const {variables}=variableContext(draft.collectionId);
  function requestPayload(d:Draft){const context=variableContext(d.collectionId);return {...buildRequest(d,context.variables,context.deferred),...context.profileIds};}
  const updateProfile=(change:Partial<Profile>)=>setEnvs(old=>old.map(e=>e.id===editEnvId?{...e,...change}:e));
  async function persistEnvironment(){if(!editingEnvironment)return;setSaving(true);setImportError("");try{const saved=await post("/environments",{...editingEnvironment,id:editingEnvironment.id==="local"?undefined:editingEnvironment.id});setEnvs(old=>[...old.filter(e=>e.id!==editingEnvironment.id),saved]);setEditEnvId(saved.id);if(saved.scope==="environment")setEnvId(saved.id);await reload();setNotice("Variable profile saved. Secret values are encrypted and resolved by the backend.");}catch(e){setImportError(String(e));}finally{setSaving(false);}}
  async function removeEnvironment(){if(!editingEnvironment)return;if(!window.confirm("Delete this variable profile?"))return;setSaving(true);try{if(editingEnvironment.revision)await request("/environments/"+editingEnvironment.id,{method:"DELETE"});setEnvs(old=>old.filter(e=>e.id!==editingEnvironment.id));setEditEnvId("");if(envId===editingEnvironment.id)setEnvId("");await reload();}catch(e){setImportError(String(e));}finally{setSaving(false);}}
  const [codeLanguage, setCodeLanguage] = useState("curl"),
    [consoleOpen, setConsoleOpen] = useState(false);
  const [logs, setLogs] = useState<{ time: string; message: string }[]>([]);
  const [runner, setRunner] = useState<Row[]>([]),
    [running, setRunning] = useState(false),
    [iterations, setIterations] = useState(1),
    [stopOnFailure, setStopOnFailure] = useState(false);
  const controllers = useRef(new Map<string, AbortController>()),
    runnerAbort = useRef(false);
  const runnerController = useRef<AbortController | null>(null);
  const current = responses[draft.id];
  useEffect(
    () => () => {
      controllers.current.forEach((c) => c.abort());
      runnerAbort.current = true;
      runnerController.current?.abort();
    },
    [],
  );
  const patch = (changes: Partial<Draft>) =>
    setTabs((old) =>
      old.map((d) =>
        d.id === draft.id ? { ...d, ...changes, dirty: true } : d,
      ),
    );
  const addTab = (d = newDraft()) => {
    setTabs((t) => [...t, d]);
    setActive(d.id);
    setError("");
  };
  const openItem = (item: Row, c: string, p: number[]) => {
    const collection=data.collections?.find(row=>row.id===c);
    if(collection?.sourceSpecId&&envId==="local"&&environment?.values.length===1&&environment.values[0].value==="https://httpbin.org"){setEnvId("");setNotice("Using this API collection's base URL. Select an environment to override it.");}

    const existing = tabs.find(
      (d) => d.collectionId === c && (item["x-studio-id"] ? d.itemKey === item["x-studio-id"] : pretty(d.itemPath) === pretty(p)),
    );
    if (existing) setActive(existing.id);
    else addTab(readItem(item, c, p));
  };
  const closeTab = (id: string) => {
    const closing = tabs.find((t) => t.id === id);
    if (
      closing?.dirty &&
      !window.confirm("Discard unsaved changes to this request?")
    )
      return;
    controllers.current.get(id)?.abort();
    const rest = tabs.filter((t) => t.id !== id);
    if (!rest.length) {
      const d = newDraft();
      setTabs([d]);
      setActive(d.id);
    } else {
      setTabs(rest);
      if (active === id) setActive(rest[rest.length - 1].id);
    }
    setResponses((old) => {
      const next = { ...old };
      delete next[id];
      return next;
    });
  };
  async function send() {
    const snapshot = draft;
    if (controllers.current.has(snapshot.id)) return;
    setError("");
    setNotice("");
    const controller = new AbortController();
    try {
      const payload = requestPayload(snapshot);
      controllers.current.set(snapshot.id, controller);
      setSending((ids) => [...ids, snapshot.id]);
      // Log method only; credentials and URLs may contain secrets.
      setLogs((l) => [
        ...l.slice(-99),
        {
          time: new Date().toLocaleTimeString(),
          message: `${snapshot.method} request started`,
        },
      ]);
      const result = await request("/client/send", {
        method: "POST",
        body: JSON.stringify(payload),
        signal: controller.signal,
      });
      const completed = {
        ...result,
        checks: evaluateChecks(snapshot.checks, result),
      };
      if (!controller.signal.aborted)
        setResponses((old) => ({ ...old, [snapshot.id]: completed }));
      setLogs((l) => [
        ...l.slice(-99),
        {
          time: new Date().toLocaleTimeString(),
          message: `${snapshot.method} completed · ${result.status} · ${result.durationMs} ms`,
        },
      ]);
      await reload();
    } catch (e) {
      if (!controller.signal.aborted) setError(String(e));
      else
        setNotice(
          "Request cancelled. The destination may already have processed it.",
        );
    } finally {
      controllers.current.delete(snapshot.id);
      setSending((ids) => ids.filter((id) => id !== snapshot.id));
    }
  }
  const sendRef = useRef(send);
  sendRef.current = send;
  const saveRef = useRef(() => {});
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      if (e.key === "Enter") {
        e.preventDefault();
        void sendRef.current();
      }
      if (e.key.toLowerCase() === "s") {
        e.preventDefault();
        saveRef.current();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);
  function openSave() {
    setTarget(draft.collectionId || data.collections?.[0]?.id || "");
    setFolderPath([]);
    setNewName(draft.name);
    setDialog("Save request");
    setImportError("");
  }
  saveRef.current = openSave;
  function manage(action:string,collectionId:string,path:number[]|null,name:string){document.querySelectorAll(".rs-menu[open]").forEach(el=>el.removeAttribute("open"));setTarget(collectionId);setManagedRevision(data.collections?.find(c=>c.id===collectionId)?.revision);setManagedPath(path);setNewName(name);setMoveTo("[]");setImportError("");setDialog(action+(path===null?" collection":" item"));}
  async function applyManagement(){const collection=data.collections?.find(c=>c.id===target);if(!collection)return;setSaving(true);setImportError("");try{
    if(collection.revision!==managedRevision)throw new Error("The collection changed. Close this dialog and reopen the action before continuing.");
    const action=dialog.split(" ")[0].toLowerCase();
    if(managedPath===null){
      if(action==="delete"){await request("/records/collections/"+collection.id,{method:"DELETE"});setTabs(old=>old.map(d=>d.collectionId===collection.id?{...d,collectionId:undefined,itemPath:undefined,itemKey:undefined,dirty:true}:d));}
      if(action==="rename"){if(!newName.trim())throw new Error("Enter a collection name.");await save("collections",{...collection,name:newName.trim(),info:{...collection.info,name:newName.trim()}});}
      if(action==="duplicate"){const copy={...collection,name:collection.name+" copy",info:{...collection.info,name:collection.name+" copy"},item:assignItemIds(collection.item||[],true)};for(const key of ["id","revision","updatedAt","sourceSpecId","sourceSpecRevision","sourceContractHash"])delete (copy as Row)[key];await save("collections",copy);}
    }else{
      const normalized=assignItemIds(collection.item||[]);const next=changeItems(normalized,action as "rename"|"duplicate"|"delete"|"move",managedPath,newName,JSON.parse(moveTo));
      await save("collections",{...collection,item:next});const locations=itemLocations(next);
      setTabs(old=>old.map(d=>{if(d.collectionId!==collection.id||!d.itemPath)return d;const key=d.itemKey||itemAt(normalized,d.itemPath)?.["x-studio-id"];const location=locations.find(x=>x.item["x-studio-id"]===key);return location?{...d,itemPath:location.path,itemKey:key,...(!d.dirty?{name:location.item.name}:{})}:{...d,collectionId:undefined,itemPath:undefined,itemKey:undefined,dirty:true};}));
    }
    await reload();setDialog("");setNotice(action==="delete"?"Deleted. Open requests are retained as unsaved tabs.":"Collection updated.");
  }catch(e){setImportError(String(e));}finally{setSaving(false);}}
  async function persistRequest() {
    setSaving(true);
    setImportError("");
    const snapshot = { ...draft, name: newName.trim() || "Untitled request" };
    try {
      let collection = data.collections?.find((c) => c.id === target);
      if (!collection)
        collection = await save("collections", {
          name: "My collection",
          info: {
            name: "My collection",
            schema:
              "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
          },
          item: [],
        });
      const sameCollection=snapshot.collectionId===collection!.id;
      const originalCollection=data.collections?.find(c=>c.id===snapshot.collectionId);
      const originalItem=snapshot.itemKey?itemLocations(originalCollection?.item||[]).find(x=>x.item["x-studio-id"]===snapshot.itemKey)?.item:snapshot.itemPath?itemAt(originalCollection?.item||[],snapshot.itemPath):undefined;
      let item={...originalItem,...serializedItem(sameCollection?snapshot:{...snapshot,itemKey:undefined,id:crypto.randomUUID()})};
      if(sameCollection&&snapshot.itemKey){const current=itemLocations(collection!.item||[]).find(x=>x.item["x-studio-id"]===snapshot.itemKey);if(!current)throw new Error("This saved request was moved or deleted. Reopen it from the collection before saving.");snapshot.itemPath=current.path;}
      let itemPath: number[], items: Row[];
      if (snapshot.collectionId === collection!.id && snapshot.itemPath) {
        itemPath = snapshot.itemPath;
        items = replaceItem(collection!.item || [], itemPath, item);
      } else {
        const children = folderPath.reduce(
          (node: Row[], i: number) => node[i]?.item || [],
          collection!.item || [],
        );
        itemPath = [...folderPath, children.length];
        const append = (nodes: Row[], path: number[]): Row[] =>
          path.length
            ? nodes.map((n, i) =>
                i === path[0]
                  ? { ...n, item: append(n.item || [], path.slice(1)) }
                  : n,
              )
            : [...nodes, item];
        items = append(collection!.item || [], folderPath);
      }
      await save("collections", { ...collection, item: items });
      setTabs((old) =>
        old.map((d) =>
          d.id === snapshot.id
            ? {
                ...d,
                name: snapshot.name,
                collectionId: collection!.id,
                itemPath,
                itemKey:item["x-studio-id"],
                dirty: false,
              }
            : d,
        ),
      );
      await reload();
      setDialog("");
      setNotice(
        "Request saved. Auth fields and recognized credential headers/query values are saved as variable references.",
      );
    } catch (e) {
      setImportError(String(e));
    } finally {
      setSaving(false);
    }
  }
  async function importContent(text = importText) {
    setImportError("");
    setSaving(true);
    try {
      if (text.trim().startsWith("curl")) {
        const parsed = parseCurl(text),
          d = readItem({ name: "Imported curl", request: parsed });
        const authKey = Object.keys(parsed.headers).find(
          (k) => k.toLowerCase() === "authorization",
        );
        if (authKey && /^Bearer /i.test(parsed.headers[authKey])) {
          d.auth = "bearer";
          d.token = parsed.headers[authKey].slice(7);
          d.headers = d.headers.filter((h) => h.key !== authKey);
        }
        addTab({ ...d, dirty: true });
        setImportText("");
        setDialog("");
        setNotice("curl imported into a new request tab.");
      } else {
        const collection = JSON.parse(text);
        if (
          !collection.info?.schema?.includes("v2.1.0") ||
          !Array.isArray(collection.item)
        )
          throw new Error(
            "Paste a curl command or a Postman Collection v2.1 JSON document.",
          );
        const clean=sanitizeItems;
        const saved = await save("collections", {
          name: collection.info.name,
          info: collection.info,
          item: clean(collection.item),
        });
        await reload();
        setTarget(saved.id);
        setImportText("");
        setDialog("");
        setNotice(
          "Collection imported. Credential fields use variable references.",
        );
      }
    } catch (e) {
      setImportError(String(e));
    } finally {
      setSaving(false);
    }
  }
  async function addCollection() {
    if (!newName.trim()) return;
    setSaving(true);
    setImportError("");
    try {
      await save("collections", {
        name: newName.trim(),
        info: {
          name: newName.trim(),
          schema:
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        item: [],
      });
      await reload();
      setDialog("");
    } catch (e) {
      setImportError(String(e));
    } finally {
      setSaving(false);
    }
  }
  async function addFolder() {
    const c = data.collections?.find((c) => c.id === target);
    if (!c || !newName.trim()) return;
    setSaving(true);
    setImportError("");
    try {
      await save("collections", {
        ...c,
        item: [...(c.item || []), { name: newName.trim(), item: [] }],
      });
      await reload();
      setDialog("");
    } catch (e) {
      setImportError(String(e));
    } finally {
      setSaving(false);
    }
  }
  async function runCollection() {
    const c = data.collections?.find((c) => c.id === target);
    if (!c) return;
    setRunning(true);
    setRunner([]);
    setImportError("");
    runnerAbort.current = false;
    try {
      let stop = false;
      for (let iteration = 1; iteration <= iterations && !stop; iteration++)
        for (const { item, path } of flattenRequests(c.item || [])) {
          if (runnerAbort.current) {
            stop = true;
            break;
          }
          try {
            const d = readItem(item, c.id, path),
              payload = requestPayload(d);
            const control = new AbortController();
            runnerController.current = control;
            const r = await request("/client/send", {
              method: "POST",
              body: JSON.stringify(payload),
              signal: control.signal,
            });
            const checks = evaluateChecks(d.checks, r),
              passed =
                r.status >= 200 &&
                r.status < 400 &&
                checks.every((t) => t.passed);
            setRunner((rows) => [
              ...rows,
              {
                name: item.name,
                iteration,
                status: r.status,
                duration: r.durationMs,
                passed,
                checks,
              },
            ]);
            if (stopOnFailure && !passed) {
              stop = true;
              break;
            }
          } catch (e) {
            if (runnerAbort.current) {
              stop = true;
              break;
            }
            setRunner((rows) => [
              ...rows,
              {
                name: item.name,
                iteration,
                status: "Error",
                passed: false,
                error: String(e),
              },
            ]);
            if (stopOnFailure) {
              stop = true;
              break;
            }
          }
        }
      await reload();
    } catch (e) {
      setImportError(String(e));
    } finally {
      setRunning(false);
      runnerController.current = null;
    }
  }
  const textBody = String(current?.body ?? "");
  let jsonBody: unknown,
    prettyBody = textBody;
  try {
    jsonBody = JSON.parse(textBody);
    prettyBody = pretty(jsonBody);
  } catch {}
  const displayBody = view === "Raw" ? textBody : prettyBody;
  const found = find ? displayBody.split(find).length - 1 : 0;
  let code = "";
  // Generate code from serialized placeholders so no recognized header/query credentials are exposed.
  if (dialog === "Code snippet")
    try {
      const safeDraft = readItem(serializedItem(draft));
      const safeVariables = Object.fromEntries(
        Object.keys(variables).map((k) => [k, "REDACTED"]),
      );
      const names = JSON.stringify(serializedItem(draft)).matchAll(
        /\{\{([\w.-]+)\}\}/g,
      );
      for (const m of names) safeVariables[m[1]] = "REDACTED";
      code = snippet(
        buildRequest(safeDraft, {
          ...safeVariables,
          baseUrl: variables.baseUrl || "https://example.com",
        }),
        codeLanguage,
      );
    } catch (e) {
      code = String(e);
    }
  const collectionName =
    data.collections?.find((c) => c.id === draft.collectionId)?.name ||
    "Unsaved requests";
  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setNotice("Copied to clipboard.");
    } catch {
      setError("Clipboard unavailable. Select and copy the text manually.");
    }
  }
  return (
    <section className="rs-shell" aria-label="Request Studio workspace">
      <header className="rs-workspace-bar">
        <div>
          <span className="rs-workspace-icon">
            <Layers size={15} />
          </span>
          <strong>My workspace</strong>
          <span className="rs-local-tag">LOCAL</span>
        </div>
        <div>
          <button
            onClick={() => {
              setDialog("Import");
              setImportError("");
            }}
          >
            Import
          </button>
          <button onClick={() => addTab()}>
            <Plus size={14} /> New
          </button>
          <span className="rs-divider" />
          <Globe size={14} />
          <select
            aria-label="Active environment"
            value={envId}
            onChange={(e) => setEnvId(e.target.value)}
          >
            <option value="">No environment</option>
            {envs.filter(e=>e.scope==="environment").map((e) => (
              <option key={e.id} value={e.id}>
                {e.name}
              </option>
            ))}
          </select>
          <button
            aria-label="Manage environments"
            title="Manage environments"
            onClick={() => {setEditEnvId(envId||envs[0]?.id||"");setDialog("Environments");}}
          >
            <Settings2 size={15} />
          </button>
        </div>
      </header>
      <div className="rs-layout">
        <aside className="rs-sidebar">
          <div className="rs-side-tabs">
            {["Collections", "History"].map((t) => (
              <button
                className={side === t ? "active" : ""}
                key={t}
                onClick={() => setSide(t)}
              >
                {t === "Collections" ? (
                  <Layers size={14} />
                ) : (
                  <History size={14} />
                )}{" "}
                {t}
              </button>
            ))}
          </div>
          <div className="rs-filter">
            <Search size={14} />
            <input
              aria-label="Filter collections and history"
              placeholder="Filter requests"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <div className="rs-side-actions">
            <button
              onClick={() => {
                setNewName("");
                setDialog("New collection");
                setImportError("");
              }}
            >
              <Plus size={13} /> Collection
            </button>
            <button
              title="Collection runner"
              onClick={() => {
                setTarget(data.collections?.[0]?.id || "");
                setDialog("Collection runner");
                setImportError("");
              }}
            >
              <Play size={13} /> Run
            </button>
          </div>
          <div className="rs-side-list">
            {side === "Collections"
              ? (data.collections || []).map((c) => (
                  <div className="rs-collection" key={c.id}>
                    <div className="rs-collection-heading">
                      <button
                        onClick={() =>
                          setCollapsed((old) =>
                            old.includes(c.id)
                              ? old.filter((id) => id !== c.id)
                              : [...old, c.id],
                          )
                        }
                      >
                        {collapsed.includes(c.id) ? (
                          <ChevronRight size={14} />
                        ) : (
                          <ChevronDown size={14} />
                        )}
                        <Folder size={15} />
                        <strong>{c.name}</strong>
                      </button>
                      <details className="rs-menu">
                        <summary aria-label={`Actions for ${c.name}`}>
                          <MoreHorizontal size={16} />
                        </summary>
                        <div>
                          <button
                            onClick={() => {
                              const d = newDraft();
                              d.collectionId = c.id;
                              addTab(d);
                            }}
                          >
                            Add request
                          </button>
                          <button
                            onClick={() => {
                              setTarget(c.id);
                              setNewName("");
                              setDialog("New folder");
                              setImportError("");
                            }}
                          >
                            Add folder
                          </button>
                          <button
                            onClick={() =>
                              download(
                                (c.name || "collection") +
                                  ".postman_collection.json",
                                pretty({
                                  info: c.info,
                                  item: sanitizeItems(c.item || []),
                                }),
                              )
                            }
                          >
                            Export v2.1
                          </button>
                          <button onClick={()=>manage("Rename",c.id,null,c.name)}>Rename collection</button><button onClick={()=>manage("Duplicate",c.id,null,c.name)}>Duplicate collection</button><button className="rs-danger" disabled={roles? !roles.includes("ROLE_ADMIN"):false} title="Collection deletion requires an administrator" onClick={()=>manage("Delete",c.id,null,c.name)}>Delete collection</button>
                        </div>
                      </details>
                    </div>
                    {!collapsed.includes(c.id) && (
                      <CollectionTree
                        items={c.item || []}
                        collectionId={c.id}
                        search={search}
                        onOpen={openItem}
                        onManage={manage}
                        onFolder={(id, path) => {
                          const d = newDraft();
                          d.collectionId = id;
                          addTab(d);
                          setTarget(id);
                          setFolderPath(path);
                          setNewName(d.name);
                          setDialog("Save request");
                        }}
                      />
                    )}
                  </div>
                ))
              : (data.history || [])
                  .filter((h) =>
                    `${h.method} ${h.url}`
                      .toLowerCase()
                      .includes(search.toLowerCase()),
                  )
                  .map((h) => (
                    <button
                      className="rs-history-entry"
                      key={h.id}
                      onClick={() =>
                        addTab({
                          ...newDraft(),
                          name: "History request",
                          method: h.method,
                          ...splitUrl(h.url),
                        })
                      }
                    >
                      <span className={`rs-method ${h.method?.toLowerCase()}`}>
                        {h.method}
                      </span>
                      <span>
                        {h.url}
                        <small>
                          {h.status} · {h.durationMs} ms · URL replay only
                        </small>
                      </span>
                    </button>
                  ))}
            {side === "Collections" && !data.collections?.length && (
              <div className="rs-side-empty">
                <FolderPlus size={26} />
                <b>Organize your API work</b>
                <p>Create a collection or import your existing requests.</p>
                <button onClick={() => setDialog("Import")}>
                  Import collection
                </button>
              </div>
            )}
          </div>
          <div className="rs-sidebar-footer">
            <span className="rs-connected" /> Local workspace · H2 storage
          </div>
        </aside>
        <div className="rs-main">
          <div
            className="rs-request-tabs"
            role="tablist"
            aria-label="Open requests"
          >
            {tabs.map((d) => (
              <div
                className={
                  d.id === active ? "rs-request-tab active" : "rs-request-tab"
                }
                key={d.id}
              >
                <button
                  role="tab"
                  aria-selected={d.id === active}
                  onClick={() => {
                    setActive(d.id);
                    setError("");
                  }}
                >
                  <span className={`rs-method ${d.method.toLowerCase()}`}>
                    {d.method}
                  </span>
                  <span>{d.name}</span>
                  {d.dirty && <i title="Unsaved changes" />}
                </button>
                <button
                  aria-label={`Close ${d.name}`}
                  onClick={() => closeTab(d.id)}
                >
                  <X size={12} />
                </button>
              </div>
            ))}
            <button
              className="rs-new-tab"
              aria-label="New request tab"
              onClick={() => addTab()}
            >
              <Plus size={16} />
            </button>
          </div>
          <div className="rs-request-heading">
            <div>
              <span>{collectionName}</span>
              <ChevronRight size={13} />
              <input
                aria-label="Request name"
                value={draft.name}
                onChange={(e) => patch({ name: e.target.value })}
              />
            </div>
            <div>
              <button
                onClick={() =>
                  addTab({
                    ...draft,
                    id: crypto.randomUUID(),
                    name: draft.name + " copy",
                    itemPath: undefined,
                    dirty: true,
                  })
                }
                title="Duplicate request"
              >
                <Copy size={14} />
              </button>
              <button onClick={openSave}>
                <Save size={14} /> Save <kbd>⌘/Ctrl S</kbd>
              </button>
            </div>
          </div>
          <div className="rs-url-row">
            <div className="rs-url-input">
              <select
                className={`rs-method ${draft.method.toLowerCase()}`}
                aria-label="HTTP method"
                value={draft.method}
                onChange={(e) => patch({ method: e.target.value })}
              >
                {methods.map((m) => (
                  <option key={m}>{m}</option>
                ))}
              </select>
              <input
                aria-label="Request URL"
                placeholder="Enter URL or paste a URL with parameters"
                value={draft.url}
                onChange={(e) =>
                  patch({
                    url: e.target.value,
                    params: e.target.value.includes("?")
                      ? splitUrl(e.target.value).params
                      : draft.params,
                  })
                }
              />
            </div>
            {sending.includes(draft.id) ? (
              <button
                className="rs-send"
                onClick={() => controllers.current.get(draft.id)?.abort()}
              >
                <Loader2 size={15} className="spin" /> Cancel
              </button>
            ) : (
              <button className="rs-send" onClick={() => void send()}>
                <Send size={15} /> Send
              </button>
            )}
          </div>
          {(error || notice) && (
            <div
              className={error ? "rs-message error" : "rs-message"}
              role="status"
            >
              {error || notice}
              <button
                aria-label="Dismiss message"
                onClick={() => {
                  setError("");
                  setNotice("");
                }}
              >
                <X size={13} />
              </button>
            </div>
          )}
          <div
            className="rs-editor-tabs"
            role="tablist"
            aria-label="Request configuration"
          >
            {[
              "Params",
              "Authorization",
              "Headers",
              "Body",
              "Tests",
              "Description",
            ].map((t) => (
              <button
                role="tab"
                aria-selected={tab === t}
                className={tab === t ? "active" : ""}
                key={t}
                onClick={() => setTab(t)}
              >
                {t}
                {t === "Params" &&
                  draft.params.filter((p) => p.enabled && p.key).length > 0 && (
                    <span>
                      {draft.params.filter((p) => p.enabled && p.key).length}
                    </span>
                  )}
                {t === "Headers" &&
                  draft.headers.filter((p) => p.enabled && p.key).length >
                    0 && (
                    <span>
                      {draft.headers.filter((p) => p.enabled && p.key).length}
                    </span>
                  )}
                {t === "Body" && draft.bodyMode !== "none" && <i />}
              </button>
            ))}
            <button
              className="rs-code-button"
              onClick={() => setDialog("Code snippet")}
            >
              <Code2 size={15} /> Code
            </button>
          </div>
          <div className="rs-request-editor">
            {tab === "Params" && (
              <>
                <div className="rs-section-label">
                  Query parameters{" "}
                  <span>
                    Enabled parameters are added to the URL when sending.
                  </span>
                </div>
                <KeyValues
                  rows={draft.params}
                  onChange={(params) => patch({ params })}
                  label="Parameter"
                  description
                />
              </>
            )}
            {tab === "Headers" && (
              <>
                <div className="rs-section-label">
                  Request headers{" "}
                  <span>
                    Content-Type and authorization are generated when needed.
                  </span>
                </div>
                <KeyValues
                  rows={draft.headers}
                  onChange={(headers) => patch({ headers })}
                  label="Header"
                  description
                />
              </>
            )}
            {tab === "Authorization" && (
              <div className="rs-auth-layout">
                <div>
                  <label>
                    Auth type
                    <select
                      aria-label="Auth type"
                      value={draft.auth}
                      onChange={(e) =>
                        patch({ auth: e.target.value as Draft["auth"] })
                      }
                    >
                      <option value="none">No Auth</option>
                      <option value="bearer">Bearer Token</option>
                      <option value="basic">Basic Auth</option>
                      <option value="apikey">API Key</option>
                    </select>
                  </label>
                  <p>
                    Credentials are applied when you send the request. Use{" "}
                    <code>{"{{variables}}"}</code> to switch between
                    environments.
                  </p>
                </div>
                <div>
                  {draft.auth === "none" ? (
                    <div className="rs-auth-empty">
                      <Globe size={24} />
                      <b>This request does not use authorization</b>
                      <p>Choose an auth type to configure credentials.</p>
                    </div>
                  ) : (
                    <>
                      {draft.auth === "basic" ? (
                        <>
                          <label>
                            Username
                            <input
                              aria-label="Basic username"
                              value={draft.username}
                              onChange={(e) =>
                                patch({ username: e.target.value })
                              }
                            />
                          </label>
                          <label>
                            Password
                            <input
                              aria-label="Basic password"
                              type="password"
                              value={draft.password}
                              onChange={(e) =>
                                patch({ password: e.target.value })
                              }
                            />
                          </label>
                        </>
                      ) : (
                        <>
                          {draft.auth === "apikey" && (
                            <label>
                              Key
                              <input
                                aria-label="API key name"
                                value={draft.apiKey}
                                onChange={(e) =>
                                  patch({ apiKey: e.target.value })
                                }
                              />
                            </label>
                          )}
                          <label>
                            {draft.auth === "bearer" ? "Token" : "Value"}
                            <input
                              aria-label="Auth credential"
                              type="password"
                              value={draft.token}
                              onChange={(e) => patch({ token: e.target.value })}
                              placeholder="{{bearerToken}}"
                              autoComplete="off"
                            />
                          </label>
                          {draft.auth === "apikey" && (
                            <label>
                              Add to
                              <select
                                aria-label="API key location"
                                value={draft.apiLocation}
                                onChange={(e) =>
                                  patch({
                                    apiLocation: e.target.value as
                                      "header" | "query",
                                  })
                                }
                              >
                                <option value="header">Header</option>
                                <option value="query">Query params</option>
                              </select>
                            </label>
                          )}
                        </>
                      )}
                      <small>
                        Auth credentials stay in this session. Saved requests
                        use variable references.
                      </small>
                    </>
                  )}
                </div>
              </div>
            )}
            {tab === "Body" && (
              <>
                <div className="rs-body-options">
                  {[
                    ["none", "none"],
                    ["raw", "raw"],
                    ["urlencoded", "x-www-form-urlencoded"],
                    ["graphql", "GraphQL"],
                  ].map(([value, label]) => (
                    <label key={value}>
                      <input
                        type="radio"
                        name="body-mode"
                        value={value}
                        checked={draft.bodyMode === value}
                        onChange={() =>
                          patch({ bodyMode: value as Draft["bodyMode"] })
                        }
                      />
                      {label}
                    </label>
                  ))}
                  {draft.bodyMode === "raw" && (
                    <select
                      aria-label="Raw body language"
                      value={draft.language}
                      onChange={(e) =>
                        patch({ language: e.target.value as Draft["language"] })
                      }
                    >
                      {["JSON", "Text", "XML"].map((l) => (
                        <option key={l}>{l}</option>
                      ))}
                    </select>
                  )}
                </div>
                {draft.bodyMode === "none" ? (
                  <div className="rs-body-empty">
                    This request does not have a body.
                  </div>
                ) : draft.bodyMode === "urlencoded" ? (
                  <KeyValues
                    rows={draft.form}
                    onChange={(form) => patch({ form })}
                    label="Form"
                  />
                ) : (
                  <>
                    <div className="rs-code-toolbar">
                      <span>
                        {draft.bodyMode === "graphql"
                          ? "QUERY"
                          : draft.language}
                      </span>
                      {draft.language === "JSON" &&
                        draft.bodyMode === "raw" && (
                          <button
                            onClick={() => {
                              try {
                                patch({ body: pretty(JSON.parse(draft.body)) });
                              } catch (e) {
                                setError(String(e));
                              }
                            }}
                          >
                            Beautify
                          </button>
                        )}
                    </div>
                    <div className="rs-code-editor">
                      <div className="rs-line-numbers" aria-hidden="true">
                        {draft.body.split("\n").map((_, i) => (
                          <div key={i}>{i + 1}</div>
                        ))}
                      </div>
                      <textarea
                        aria-label="Request body"
                        spellCheck={false}
                        value={draft.body}
                        onChange={(e) => patch({ body: e.target.value })}
                        placeholder={
                          draft.bodyMode === "graphql"
                            ? "query { ... }"
                            : '{\n  "key": "value"\n}'
                        }
                      />
                    </div>
                    {draft.bodyMode === "graphql" && (
                      <label className="rs-graphql-vars">
                        Variables (JSON)
                        <textarea
                          aria-label="GraphQL variables"
                          value={draft.graphqlVariables}
                          onChange={(e) =>
                            patch({ graphqlVariables: e.target.value })
                          }
                        />
                      </label>
                    )}
                  </>
                )}
              </>
            )}
            {tab === "Tests" && (
              <>
                <div className="rs-section-label">
                  Response assertions{" "}
                  <span>
                    Run automatically after Send and in the collection runner.
                  </span>
                  <button
                    onClick={() =>
                      patch({
                        checks: [
                          ...draft.checks,
                          {
                            name: "New assertion",
                            type: "status",
                            key: "",
                            expected: "200",
                            enabled: true,
                          },
                        ],
                      })
                    }
                  >
                    <Plus size={13} /> Add assertion
                  </button>
                </div>
                <div className="rs-tests">
                  {draft.checks.map((c, i) => (
                    <div className="rs-check-row" key={i}>
                      <input
                        aria-label={`Assertion ${i + 1} enabled`}
                        type="checkbox"
                        checked={c.enabled}
                        onChange={(e) =>
                          patch({
                            checks: draft.checks.map((x, n) =>
                              n === i ? { ...x, enabled: e.target.checked } : x,
                            ),
                          })
                        }
                      />
                      <input
                        aria-label={`Assertion ${i + 1} name`}
                        value={c.name}
                        onChange={(e) =>
                          patch({
                            checks: draft.checks.map((x, n) =>
                              n === i ? { ...x, name: e.target.value } : x,
                            ),
                          })
                        }
                      />
                      <select
                        aria-label={`Assertion ${i + 1} type`}
                        value={c.type}
                        onChange={(e) =>
                          patch({
                            checks: draft.checks.map((x, n) =>
                              n === i
                                ? {
                                    ...x,
                                    type: e.target.value as CheckRule["type"],
                                  }
                                : x,
                            ),
                          })
                        }
                      >
                        <option value="status">Status equals</option>
                        <option value="contains">Body contains</option>
                        <option value="header">Header equals</option>
                        <option value="json">JSON path equals</option>
                      </select>
                      {["header", "json"].includes(c.type) && (
                        <input
                          aria-label={`Assertion ${i + 1} key`}
                          placeholder={
                            c.type === "json" ? "data.id" : "content-type"
                          }
                          value={c.key}
                          onChange={(e) =>
                            patch({
                              checks: draft.checks.map((x, n) =>
                                n === i ? { ...x, key: e.target.value } : x,
                              ),
                            })
                          }
                        />
                      )}
                      <input
                        aria-label={`Assertion ${i + 1} expected`}
                        value={c.expected}
                        onChange={(e) =>
                          patch({
                            checks: draft.checks.map((x, n) =>
                              n === i ? { ...x, expected: e.target.value } : x,
                            ),
                          })
                        }
                      />
                      <button
                        aria-label={`Remove assertion ${i + 1}`}
                        onClick={() =>
                          patch({
                            checks: draft.checks.filter((_, n) => n !== i),
                          })
                        }
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  ))}
                </div>
                <p className="rs-helper">
                  Declarative assertions. Postman JavaScript scripts are not
                  executed.
                </p>
              </>
            )}
            {tab === "Description" && (
              <textarea
                className="rs-description"
                aria-label="Request description"
                placeholder="Describe what this request does, its parameters, and expected behavior…"
                value={draft.description}
                onChange={(e) => patch({ description: e.target.value })}
              />
            )}
          </div>
          <div className="rs-splitter">
            <span />
            <span />
          </div>
          <section className="rs-response" aria-label="Response viewer">
            <div className="rs-response-heading">
              <strong>Response</strong>
              {current && (
                <div>
                  <span
                    className={
                      current.status >= 200 && current.status < 300
                        ? "rs-success"
                        : "rs-failure"
                    }
                  >
                    Status: <b>{current.status || "Network error"}</b>
                  </span>
                  <span>
                    Time: <b>{current.durationMs} ms</b>
                  </span>
                  <span>
                    Size:{" "}
                    <b>{sizes(new TextEncoder().encode(textBody).length)}</b>
                  </span>
                </div>
              )}
            </div>
            <div className="rs-response-tabs">
              {["Body", "Headers", "Test Results"].map((t) => (
                <button
                  className={responseTab === t ? "active" : ""}
                  key={t}
                  onClick={() => setResponseTab(t)}
                >
                  {t}
                  {t === "Headers" && current && (
                    <span>{Object.keys(current.headers || {}).length}</span>
                  )}
                  {t === "Test Results" && current && (
                    <span>
                      {
                        (current.checks || []).filter((c: Row) => c.passed)
                          .length
                      }
                      /{current.checks?.length || 0}
                    </span>
                  )}
                </button>
              ))}
              {current && (
                <div className="rs-response-actions">
                  <button
                    aria-label="Copy response"
                    onClick={() => copy(textBody)}
                  >
                    <Copy size={14} />
                  </button>
                  <button
                    aria-label="Download response"
                    onClick={() =>
                      download(
                        "response." + (jsonBody !== undefined ? "json" : "txt"),
                        textBody,
                      )
                    }
                  >
                    <ArrowDownToLine size={14} />
                  </button>
                </div>
              )}
            </div>
            {!current ? (
              <div className="rs-response-empty">
                {sending.includes(draft.id) ? (
                  <Loader2 size={34} className="spin" />
                ) : (
                  <div className="rs-response-illustration">
                    <Send size={28} />
                  </div>
                )}
                <h3>
                  {sending.includes(draft.id)
                    ? "Sending your request…"
                    : "Make a request. See what happens."}
                </h3>
                <p>Enter a URL and click Send to get a response.</p>
                <kbd>Ctrl / ⌘ + Enter</kbd>
              </div>
            ) : responseTab === "Headers" ? (
              <div className="rs-response-header-table">
                <table>
                  <thead>
                    <tr>
                      <th>KEY</th>
                      <th>VALUE</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(current.headers || {}).map(([k, v]) => (
                      <tr key={k}>
                        <td>{k}</td>
                        <td>{String(v)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : responseTab === "Test Results" ? (
              <div className="rs-test-results">
                {current.checks?.map((c: Row, i: number) => (
                  <div key={i}>
                    <span className={c.passed ? "rs-pass" : "rs-fail"}>
                      {c.passed ? "PASS" : "FAIL"}
                    </span>
                    <strong>{c.name}</strong>
                    {!c.passed && (
                      <small>
                        Expected {c.expected}; received {c.actual}
                      </small>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <>
                <div className="rs-response-tools">
                  <div>
                    {["Pretty", "Raw", "Tree"].map((v) => (
                      <button
                        disabled={v === "Tree" && jsonBody === undefined}
                        className={view === v ? "selected" : ""}
                        key={v}
                        onClick={() => setView(v)}
                      >
                        {v}
                      </button>
                    ))}
                  </div>
                  <span>{jsonBody !== undefined ? "JSON" : "Text"}</span>
                  <label>
                    <Search size={13} />
                    <input
                      aria-label="Find in response"
                      placeholder="Find in response"
                      value={find}
                      onChange={(e) => setFind(e.target.value)}
                    />
                    {find && <small>{found} matches</small>}
                  </label>
                </div>
                {current.error && (
                  <p className="rs-message error">{current.error}</p>
                )}
                {view === "Tree" && jsonBody !== undefined ? (
                  <div className="rs-json-tree">
                    <JsonTree value={jsonBody} />
                  </div>
                ) : (
                  <pre
                    className="rs-response-body output"
                    aria-label="Response body"
                  >
                    {find
                      ? displayBody.split(find).map((part, i) => (
                          <span key={i}>
                            {i > 0 && <mark>{find}</mark>}
                            {part}
                          </span>
                        ))
                      : displayBody}
                  </pre>
                )}
              </>
            )}
          </section>
          <footer className="rs-statusbar">
            <button onClick={() => setConsoleOpen(!consoleOpen)}>
              <Terminal size={13} /> Console{" "}
              {logs.length > 0 && <span>{logs.length}</span>}
            </button>
            <span>HTTP · Backend proxy</span>
            <span>
              <i className="rs-connected" /> Connected to local workspace
            </span>
          </footer>
          {consoleOpen && (
            <div className="rs-console">
              <header>
                <strong>Console</strong>
                <button onClick={() => setLogs([])}>Clear</button>
                <button
                  aria-label="Close console"
                  onClick={() => setConsoleOpen(false)}
                >
                  <X size={13} />
                </button>
              </header>
              {logs.length ? (
                logs.map((l, i) => (
                  <div key={i}>
                    <time>{l.time}</time>
                    {l.message}
                  </div>
                ))
              ) : (
                <p>
                  Request events appear here. Credentials and response bodies
                  are excluded.
                </p>
              )}
            </div>
          )}
        </div>
      </div>
      {dialog && (
        <div className="modal-backdrop">
          <section
            className={`modal rs-dialog ${dialog === "Environments" || dialog === "Collection runner" ? "rs-dialog-wide" : ""}`}
            role="dialog"
            aria-modal="true"
            aria-label={dialog}
          >
            <header>
              <h2>{dialog}</h2>
              <button
                aria-label="Close dialog"
                disabled={saving || running}
                onClick={() => {
                  setDialog("");
                  setImportError("");
                  setImportText("");
                }}
              >
                <X size={18} />
              </button>
            </header>
            {/^(Rename|Duplicate|Delete|Move) (collection|item)$/.test(dialog)&&<>
              {dialog.startsWith("Delete")?<p>Delete <strong>{newName}</strong>{managedPath===null?" and all its folders and requests":" and any nested requests"}? Open requests will remain as unsaved tabs. This action cannot be undone.</p>:dialog.startsWith("Rename")?<label>Name<input aria-label="Managed item name" value={newName} onChange={e=>setNewName(e.target.value)}/></label>:dialog.startsWith("Move")?<label>Destination folder<select aria-label="Move destination" value={moveTo} onChange={e=>setMoveTo(e.target.value)}><option value="[]">Collection root</option>{itemLocations(data.collections?.find(c=>c.id===target)?.item||[]).filter(x=>Array.isArray(x.item.item)&&!(managedPath&&x.path.length>=managedPath.length&&managedPath.every((v,i)=>x.path[i]===v))).map(x=><option key={JSON.stringify(x.path)} value={JSON.stringify(x.path)}>{x.path.map((_,i)=>itemAt(data.collections?.find(c=>c.id===target)?.item||[],x.path.slice(0,i+1))?.name).join(" / ")}</option>)}</select></label>:<p>Create a separate copy of <strong>{newName}</strong>?</p>}
              <footer><button disabled={saving} onClick={()=>setDialog("")}>Cancel</button><button className={dialog.startsWith("Delete")?"rs-danger":"rs-orange"} disabled={saving} onClick={()=>void applyManagement()}>{saving?"Working…":dialog.split(" ")[0]}</button></footer>
            </>}
            {dialog === "Import" && (
              <>
                <p>
                  Paste a curl command or Postman Collection v2.1 JSON. Import
                  never executes a shell command.
                </p>
                <textarea
                  aria-label="Import content"
                  spellCheck={false}
                  value={importText}
                  onChange={(e) => setImportText(e.target.value)}
                  placeholder="curl 'https://api.example.com/items' …"
                />
                <label className="button">
                  <FileJson size={14} /> Choose collection JSON
                  <input
                    hidden
                    type="file"
                    accept=".json"
                    onChange={async (e) => {
                      const file = e.target.files?.[0];
                      if (file) setImportText(await file.text());
                    }}
                  />
                </label>
                <footer>
                  <button
                    className="rs-orange"
                    disabled={saving || !importText.trim()}
                    onClick={() => void importContent()}
                  >
                    {saving ? "Importing…" : "Import"}
                  </button>
                </footer>
              </>
            )}
            {dialog === "Save request" && (
              <>
                <label>
                  Request name
                  <input
                    aria-label="Save request name"
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                  />
                </label>
                <label>
                  Collection
                  <select
                    aria-label="Save collection"
                    value={target}
                    onChange={(e) => {
                      setTarget(e.target.value);
                      setFolderPath([]);
                    }}
                  >
                    <option value="">Create My collection</option>
                    {(data.collections || []).map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </select>
                </label>
                <p>
                  Saved credentials become variable references. Put runtime
                  values in Environments. Request body text is saved as entered;
                  use variable references for sensitive body fields.
                </p>
                <footer>
                  <button onClick={() => setDialog("")}>Cancel</button>
                  <button
                    className="rs-orange"
                    disabled={saving}
                    onClick={() => void persistRequest()}
                  >
                    {saving ? "Saving…" : "Save request"}
                  </button>
                </footer>
              </>
            )}
            {["New collection", "New folder"].includes(dialog) && (
              <>
                <label>
                  Name
                  <input
                    aria-label={dialog + " name"}
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                  />
                </label>
                <footer>
                  <button
                    className="rs-orange"
                    disabled={saving || !newName.trim()}
                    onClick={() =>
                      void (dialog === "New collection"
                        ? addCollection()
                        : addFolder())
                    }
                  >
                    Create
                  </button>
                </footer>
              </>
            )}
            {dialog === "Environments" && <>
              <div className="rs-env-toolbar"><select aria-label="Edit environment" value={editEnvId} onChange={e=>setEditEnvId(e.target.value)}><option value="">Select variable profile</option>{envs.map(e=><option key={e.id} value={e.id}>{e.name} · {e.scope}</option>)}</select><button onClick={()=>{const id=crypto.randomUUID();setEnvs(old=>[...old,{id,name:"New environment",scope:"environment",allowedHosts:[],values:[]}]);setEditEnvId(id);}}><Plus size={14}/> New profile</button></div>
              {editingEnvironment && <><div className="rs-env-toolbar"><label>Profile name<input aria-label="Environment name" value={editingEnvironment.name} onChange={e=>updateProfile({name:e.target.value})}/></label><label>Variable scope<select aria-label="Variable scope" value={editingEnvironment.scope} onChange={e=>updateProfile({scope:e.target.value})}><option value="environment">Environment</option><option value="global">Global</option><option value="collection">Collection</option></select></label></div>
              {editingEnvironment.scope==="collection"&&<label>Collection<select aria-label="Variable collection" value={editingEnvironment.collectionId||""} onChange={e=>updateProfile({collectionId:e.target.value})}><option value="">Select collection</option>{(data.collections||[]).map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label>}
              <label>Allowed destination hosts (comma separated)<input aria-label="Environment allowed hosts" placeholder="api.example.com, staging.example.com" value={editingEnvironment.allowedHosts.join(", ")} onChange={e=>updateProfile({allowedHosts:e.target.value.split(",").map(h=>h.trim()).filter(Boolean)})}/></label>
              <KeyValues rows={editingEnvironment.values} onChange={values=>updateProfile({values})} label="Variable" secrets/>
              <p>Precedence: global profile → collection defaults → collection profile → selected environment. Keep one global profile and one profile per collection. Unsaved changes stay in memory. Admins can save profiles; all saved variable values are encrypted. Saved secrets remain masked and are resolved server-side. Every applied profile must allow the destination hostname.</p>
              <footer><button disabled={saving} onClick={()=>void removeEnvironment()}><Trash2 size={13}/> Delete profile</button><button disabled={saving} onClick={()=>download("environment.json",pretty({name:editingEnvironment.name,values:editingEnvironment.values.map(v=>({...v,value:v.secret?"":v.value})),_postman_variable_scope:"environment"}))}>Export without secrets</button><button className="rs-orange" disabled={saving} onClick={()=>void persistEnvironment()}>{saving?"Saving…":"Save profile"}</button></footer></>}
              <label className="button">Import environment JSON<input type="file" hidden accept=".json" onChange={async e=>{try{const f=e.target.files?.[0];if(!f)return;const doc=JSON.parse(await f.text());if(!Array.isArray(doc.values))throw new Error("Environment JSON must contain values");const id=crypto.randomUUID();setEnvs(old=>[...old,{id,name:doc.name||"Imported environment",scope:"environment",allowedHosts:[],values:doc.values.map((v:Row)=>({...pair(v.key,String(v.value||"")),enabled:v.enabled!==false,secret:v.type==="secret"||v.secret===true}))}]);setEditEnvId(id);}catch(e){setImportError(String(e));}}}/></label>
            </>}
            {dialog === "Code snippet" && (
              <>
                <p>
                  Recognized credentials and variable values are redacted in
                  generated examples.
                </p>
                <select
                  aria-label="Snippet language"
                  value={codeLanguage}
                  onChange={(e) => setCodeLanguage(e.target.value)}
                >
                  <option value="curl">cURL · Bash</option>
                  <option value="fetch">JavaScript · Fetch</option>
                  <option value="java">Java · HttpClient</option>
                </select>
                <pre className="rs-snippet">{code}</pre>
                <footer>
                  <button onClick={() => copy(code)}>
                    <Copy size={14} /> Copy code
                  </button>
                </footer>
              </>
            )}
            {dialog === "Collection runner" && (
              <>
                <div className="rs-runner-settings">
                  <label>
                    Collection
                    <select
                      aria-label="Runner collection"
                      disabled={running}
                      value={target}
                      onChange={(e) => setTarget(e.target.value)}
                    >
                      <option value="">Select collection</option>
                      {(data.collections || []).map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Iterations
                    <input
                      aria-label="Runner iterations"
                      type="number"
                      min="1"
                      max="100"
                      value={iterations}
                      disabled={running}
                      onChange={(e) =>
                        setIterations(
                          Math.max(
                            1,
                            Math.min(100, Number(e.target.value) || 1),
                          ),
                        )
                      }
                    />
                  </label>
                  <label className="rs-checkbox-label">
                    <input
                      type="checkbox"
                      checked={stopOnFailure}
                      onChange={(e) => setStopOnFailure(e.target.checked)}
                      disabled={running}
                    />{" "}
                    Stop on failure
                  </label>
                </div>
                <p>
                  Uses the selected environment. Requests run sequentially;
                  writes can change the destination service.
                </p>
                <div className="rs-runner-summary">
                  <span>{runner.length} completed</span>
                  <b className="rs-success">
                    {runner.filter((r) => r.passed).length} passed
                  </b>
                  <b className="rs-failure">
                    {runner.filter((r) => !r.passed).length} failed
                  </b>
                </div>
                <div className="rs-run-results">
                  {runner.map((r, i) => (
                    <div key={i}>
                      <span className={r.passed ? "rs-pass" : "rs-fail"}>
                        {r.passed ? "PASS" : "FAIL"}
                      </span>
                      <strong>{r.name}</strong>
                      <span>#{r.iteration}</span>
                      <span>{r.status}</span>
                      <span>{r.duration || 0} ms</span>
                      {r.error && <small>{r.error}</small>}
                    </div>
                  ))}
                </div>
                <footer>
                  <button
                    disabled={!runner.length || running}
                    onClick={() =>
                      download("runner-results.json", pretty(runner))
                    }
                  >
                    Export results
                  </button>
                  {running ? (
                    <button
                      onClick={() => {
                        runnerAbort.current = true;
                        runnerController.current?.abort();
                      }}
                    >
                      Stop run
                    </button>
                  ) : (
                    <button
                      className="rs-orange"
                      disabled={!target}
                      onClick={() => void runCollection()}
                    >
                      <Play size={14} /> Run collection
                    </button>
                  )}
                </footer>
              </>
            )}
            {importError && (
              <p role="alert" className="error">
                {importError}
              </p>
            )}
          </section>
        </div>
      )}
    </section>
  );
}
