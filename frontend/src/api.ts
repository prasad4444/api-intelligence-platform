export type Row = Record<string, any>;
let csrf = "";
export async function request(path: string, options: RequestInit = {}) {
  if (!csrf) {
    const r = await fetch("/api/csrf");
    const c = await r.json();
    csrf = c.token;
  }
  const headers: Record<string, string> = {
    "X-XSRF-TOKEN": csrf,
    ...(options.headers as Record<string, string>),
  };
  if (
    options.body &&
    !(options.body instanceof FormData) &&
    !headers["Content-Type"]
  )
    headers["Content-Type"] = "application/json";
  const r = await fetch("/api" + path, { ...options, headers });
  if (!r.ok) {
    let message = "Request failed (" + r.status + ")";
    try {
      const e = await r.json();
      message = e.message || message;
    } catch {}
    throw new Error(message);
  }
  if (r.status === 204 || r.headers.get("content-length") === "0") return null;
  const text = await r.text();
  return text ? JSON.parse(text) : null;
}
export const post = (path: string, data: unknown = {}) =>
  request(path, { method: "POST", body: JSON.stringify(data) });
export const save = (kind: string, data: Row) => post("/records/" + kind, data);
export function resetCsrf() {
  csrf = "";
}
export function download(
  name: string,
  text: string,
  type = "application/json",
) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export const pretty = (data: unknown) => JSON.stringify(data, null, 2);
export function interpolate(value: string, env: Record<string, string>) {
  return value.replace(
    /\{\{([^}]+)\}\}/g,
    (_, key) => env[key] ?? "{{" + key + "}}",
  );
}
export function flatten(items: Row[]): Row[] {
  return items.flatMap((i) => (i.item ? flatten(i.item) : [i]));
}
