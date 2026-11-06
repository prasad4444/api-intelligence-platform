import { parseCurl, snippet } from "./request-tools";

const reference = /^\{\{vault:([\w-]{1,60})}}$/;
const blockedHeaders = new Set(["cookie", "host", "connection", "proxy-authorization", "content-length", "transfer-encoding", "forwarded", "upgrade", "expect", "te", "trailer"]);
function checkBody(value: unknown) {
  if (!value || typeof value !== "object") return;
  for (const [key, child] of Object.entries(value)) {
    if (/^(authorization|cookie|password|token|access[-_]?token|refresh[-_]?token|api[-_]?key|client[-_]?secret)$/i.test(key)) {
      throw new Error("Remove credentials from the request JSON before importing");
    }
    checkBody(child);
  }
}
const publicHeaders = new Set(["accept", "content-type", "x-api-version", "anthropic-version", "x-client-version"]);

export function importAiCurl(command: string) {
  if (command.length > 40000 || /\$\(|`/.test(command)) throw new Error("Use a single redacted cURL command without shell expressions");
  let parsed;
  try { parsed = parseCurl(command.replace(/^\s*curl\.exe\b/i, "curl")); }
  catch { throw new Error("Use a redacted cURL command with an HTTP(S) URL, headers and optional inline JSON"); }
  const url = new URL(parsed.url);
  if (url.username || url.password || url.hash || [...url.searchParams.keys()].some(key => /token|secret|password|api[_-]?key/i.test(key))) {
    throw new Error("Keep credentials in vault references, separate from the URL");
  }
  const method = String(parsed.method || "GET").toUpperCase();
  if (method !== "GET" && method !== "POST") throw new Error("Use GET or POST in AI Tools");
  const headers: Record<string, string> = Object.create(null);
  const seen = new Set<string>();
  let authType = "NONE";
  let secretRef = "";
  for (const [name, raw] of Object.entries(parsed.headers)) {
    const key = name.toLowerCase();
    const value = String(raw);
    if (seen.has(key)) throw new Error("Remove duplicate header names");
    seen.add(key);
    if (key === "authorization") {
      const bearer = /^Bearer\s+/i.test(value);
      const match = reference.exec(bearer ? value.replace(/^Bearer\s+/i, "") : value);
      if (!match) throw new Error("Replace the Authorization value with a vault reference before importing");
      authType = bearer ? "BEARER" : "AUTHORIZATION";
      secretRef = match[1];
    } else if (blockedHeaders.has(key) || key.startsWith("x-forwarded-")) {
      throw new Error("Remove cookies and transport headers from the template");
    } else if (publicHeaders.has(key) || reference.test(value)) {
      headers[name] = value;
    } else {
      throw new Error("Use a vault reference for every custom header value");
    }
  }
  let body = "";
  if (method === "POST") {
    let decoded;
    try { decoded = JSON.parse(parsed.body); }
    catch { throw new Error("Use an inline JSON body template containing {{prompt}}"); }
    if (!decoded || typeof decoded !== "object" || Array.isArray(decoded) || !JSON.stringify(decoded).includes("{{prompt}}")) {
      throw new Error("Replace the message text in the JSON object with {{prompt}}");
    }
    checkBody(decoded);
    body = JSON.stringify(decoded, null, 2);
  }
  return { url: url.href, method, headers, body, authType, secretRef };
}

export function exportAiCurl(url: string, method: string, headers: Record<string, string>, body: string, authType: string, secretRef: string) {
  const safe = {...headers};
  if (secretRef && authType === "BEARER") safe.Authorization = "Bearer {{vault:" + secretRef + "}}";
  if (secretRef && authType === "AUTHORIZATION") safe.Authorization = "{{vault:" + secretRef + "}}";
  return snippet({ url, method, headers: safe, body: method === "POST" ? body : "" }, "curl");
}
