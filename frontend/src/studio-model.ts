import { type Row, interpolate } from "./api";
export type Pair = {
  key: string;
  value: string;
  enabled: boolean;
  description?: string;
  secret?: boolean;
  hasValue?: boolean;
};
export type CheckRule = {
  name: string;
  type: "status" | "contains" | "header" | "json";
  key: string;
  expected: string;
  enabled: boolean;
};
export type Draft = {
  id: string;
  name: string;
  method: string;
  url: string;
  params: Pair[];
  headers: Pair[];
  auth: "none" | "bearer" | "basic" | "apikey";
  token: string;
  username: string;
  password: string;
  apiKey: string;
  apiLocation: "header" | "query";
  bodyMode: "none" | "raw" | "urlencoded" | "graphql";
  language: "JSON" | "Text" | "XML";
  body: string;
  form: Pair[];
  graphqlVariables: string;
  checks: CheckRule[];
  description: string;
  collectionId?: string;
  itemPath?: number[];
  dirty: boolean;
};
export const pair = (key = "", value = ""): Pair => ({
  key,
  value,
  enabled: true,
});
export const newDraft = (): Draft => ({
  id: crypto.randomUUID(),
  name: "Untitled request",
  method: "GET",
  url: "",
  params: [],
  headers: [],
  auth: "none",
  token: "",
  username: "",
  password: "",
  apiKey: "X-API-Key",
  apiLocation: "header",
  bodyMode: "none",
  language: "JSON",
  body: "",
  form: [],
  graphqlVariables: "{}",
  checks: [
    {
      name: "Status is 200",
      type: "status",
      key: "",
      expected: "200",
      enabled: true,
    },
  ],
  description: "",
  dirty: false,
});
export function splitUrl(raw: string) {
  const [base, query = ""] = raw.split(/\?(.*)/s);
  return {
    url: base,
    params: [...new URLSearchParams(query)].map(([key, value]) =>
      pair(key, value),
    ),
  };
}
export function readItem(
  item: Row,
  collectionId?: string,
  itemPath?: number[],
): Draft {
  const r =
    typeof item.request === "string"
      ? { url: item.request }
      : item.request || item;
  const raw = typeof r.url === "string" ? r.url : r.url?.raw || "";
  const d = {
    ...newDraft(),
    name: item.name || r.method + " " + raw,
    method: r.method || "GET",
    ...splitUrl(raw),
    collectionId,
    itemPath,
  };
  d.headers = Array.isArray(r.header)
    ? r.header.map((h: Row) => ({
        ...pair(h.key, String(h.value ?? "")),
        enabled: !h.disabled,
        description: h.description,
      }))
    : Object.entries(r.headers || {}).map(([k, v]) => pair(k, String(v)));
  if (r.body?.mode === "urlencoded") {
    d.bodyMode = "urlencoded";
    d.form = (r.body.urlencoded || []).map((p: Row) => ({
      ...pair(p.key, p.value),
      enabled: !p.disabled,
    }));
  } else if (r.body?.mode === "graphql") {
    d.bodyMode = "graphql";
    d.body = r.body.graphql?.query || "";
    d.graphqlVariables = r.body.graphql?.variables || "{}";
  } else if (r.body?.raw || typeof r.body === "string") {
    d.bodyMode = "raw";
    d.body = typeof r.body === "string" ? r.body : r.body.raw;
    d.language =
      ({ json: "JSON", xml: "XML", text: "Text" } as Row)[
        r.body?.options?.raw?.language
      ] || "Text";
  }
  const auth = r.auth;
  if (["bearer", "basic", "apikey"].includes(auth?.type)) {
    d.auth = auth.type;
    const fields = Object.fromEntries(
      (auth[auth.type] || []).map((x: Row) => [x.key, x.value]),
    );
    d.token = String(fields.token || fields.value || "");
    d.username = String(fields.username || "");
    d.password = String(fields.password || "");
    d.apiKey = fields.key || "X-API-Key";
    d.apiLocation = fields.in === "query" ? "query" : "header";
  }
  d.checks = item["x-studio-checks"] || d.checks;
  d.description =
    typeof r.description === "string"
      ? r.description
      : r.description?.content || "";
  return d;
}
export function buildRequest(d: Draft, variables: Row, deferred: string[] = []) {
  const resolve = (value: string) => {
    let result = value;
    for(let pass=0;pass<10;pass++){const next=interpolate(result,variables);if(next===result)break;result=next;}
    if ([...result.matchAll(/\{\{([^}]+)\}\}/g)].some(match=>!deferred.includes(match[1])))
      throw new Error(
        "Unresolved variable in request. Set its value in the selected environment.",
      );
    return result;
  };
  const base = resolve(d.url.trim());
  if (!/^https?:\/\//i.test(base))
    throw new Error("Enter an HTTP or HTTPS request URL.");
  const url = new URL(base);
  if (d.url.includes("?")) url.search = "";
  d.params
    .filter((p) => p.enabled && p.key)
    .forEach((p) => url.searchParams.append(resolve(p.key), resolve(p.value)));
  const headers: Row = {};
  const setHeader = (key: string, value: string) => {
    const old = Object.keys(headers).find(
      (h) => h.toLowerCase() === key.toLowerCase(),
    );
    if (old) delete headers[old];
    headers[key] = value;
  };
  d.headers
    .filter((h) => h.enabled && h.key)
    .forEach((h) => setHeader(resolve(h.key), resolve(h.value)));
  let body = "";
  if (d.bodyMode === "raw") {
    body = resolve(d.body);
    if (d.language === "JSON" && body.trim() && !body.includes("{{")) JSON.parse(body);
    if (!Object.keys(headers).some((h) => h.toLowerCase() === "content-type"))
      setHeader(
        "Content-Type",
        {
          JSON: "application/json",
          XML: "application/xml",
          Text: "text/plain",
        }[d.language],
      );
  }
  if (d.bodyMode === "urlencoded") {
    const form = new URLSearchParams();
    d.form
      .filter((p) => p.enabled && p.key)
      .forEach((p) => form.append(resolve(p.key), resolve(p.value)));
    body = form.toString();
    setHeader("Content-Type", "application/x-www-form-urlencoded");
  }
  if (d.bodyMode === "graphql") {
    body = JSON.stringify({
      query: resolve(d.body),
      variables: JSON.parse(resolve(d.graphqlVariables)),
    });
    setHeader("Content-Type", "application/json");
  }
  if (d.auth === "bearer")
    setHeader("Authorization", "Bearer " + resolve(d.token));
  let auth: Row | undefined;
  if (d.auth === "basic" && (resolve(d.username).includes("{{") || resolve(d.password).includes("{{"))) auth={type:"basic",username:resolve(d.username),password:resolve(d.password)};
  else if (d.auth === "basic") {
    const bytes = new TextEncoder().encode(
      resolve(d.username) + ":" + resolve(d.password),
    );
    setHeader(
      "Authorization",
      "Basic " +
        btoa(Array.from(bytes, (b) => String.fromCharCode(b)).join("")),
    );
  }
  if (d.auth === "apikey") {
    if (!d.apiKey.trim()) throw new Error("Enter an API key name.");
    if (d.apiLocation === "query")
      url.searchParams.set(resolve(d.apiKey), resolve(d.token));
    else setHeader(resolve(d.apiKey), resolve(d.token));
  }
  return { method: d.method, url: url.toString().replace(/%7B%7B/gi,"{{").replace(/%7D%7D/gi,"}}"), headers, body, ...(auth?{auth}:{}) };
}
const sensitive = /authorization|cookie|token|secret|password|api[-_]?key/i;
export function serializedItem(d: Draft): Row {
  // Collection records are not the encrypted vault. Persist placeholders for credentials.
  const safe = (key: string, value: string) =>
    sensitive.test(key) && value && !/^\{\{[\w.-]+\}\}$/.test(value)
      ? "{{" +
        (key.toLowerCase() === "authorization"
          ? "authorization"
          : "credential") +
        "}}"
      : value;
  const query = new URLSearchParams();
  d.params
    .filter((p) => p.key)
    .forEach((p) => {
      if (p.enabled) query.append(p.key, safe(p.key, p.value));
    });
  const url =
    splitUrl(d.url).url +
    (query.size
      ? "?" + query.toString().replace(/%7B/g, "{").replace(/%7D/g, "}")
      : "");
  const request: Row = {
    method: d.method,
    url,
    header: d.headers
      .filter((h) => h.key)
      .map((h) => ({
        key: h.key,
        value: safe(h.key, h.value),
        disabled: !h.enabled,
        description: h.description || "",
      })),
    description: d.description,
  };
  if (d.bodyMode === "raw")
    request.body = {
      mode: "raw",
      raw: d.body,
      options: { raw: { language: d.language.toLowerCase() } },
    };
  if (d.bodyMode === "urlencoded")
    request.body = {
      mode: "urlencoded",
      urlencoded: d.form
        .filter((p) => p.key)
        .map((p) => ({
          key: p.key,
          value: safe(p.key, p.value),
          disabled: !p.enabled,
        })),
    };
  if (d.bodyMode === "graphql")
    request.body = {
      mode: "graphql",
      graphql: { query: d.body, variables: d.graphqlVariables },
    };
  const credential = (value: string, fallback: string) =>
    /^\{\{[\w.-]+\}\}$/.test(value) ? value : "{{" + fallback + "}}";
  if (d.auth === "bearer")
    request.auth = {
      type: "bearer",
      bearer: [
        {
          key: "token",
          value: credential(d.token, "bearerToken"),
          type: "string",
        },
      ],
    };
  if (d.auth === "basic")
    request.auth = {
      type: "basic",
      basic: [
        {
          key: "username",
          value: credential(d.username, "username"),
          type: "string",
        },
        {
          key: "password",
          value: credential(d.password, "password"),
          type: "string",
        },
      ],
    };
  if (d.auth === "apikey")
    request.auth = {
      type: "apikey",
      apikey: [
        { key: "key", value: d.apiKey, type: "string" },
        { key: "value", value: credential(d.token, "apiKey"), type: "string" },
        { key: "in", value: d.apiLocation, type: "string" },
      ],
    };
  return { name: d.name, request, "x-studio-checks": d.checks };
}
export function replaceItem(items: Row[], path: number[], value: Row): Row[] {
  return items.map((item, i) =>
    i !== path[0]
      ? item
      : path.length === 1
        ? value
        : { ...item, item: replaceItem(item.item || [], path.slice(1), value) },
  );
}
export function evaluateChecks(checks: CheckRule[], response: Row) {
  return checks
    .filter((c) => c.enabled)
    .map((c) => {
      let actual: unknown;
      try {
        if (c.type === "status") actual = String(response.status);
        if (c.type === "contains")
          actual = String(response.body || "").includes(c.expected)
            ? c.expected
            : "Text not found";
        if (c.type === "header")
          actual = Object.entries(response.headers || {}).find(
            ([k]) => k.toLowerCase() === c.key.toLowerCase(),
          )?.[1];
        if (c.type === "json") {
          actual = JSON.parse(response.body);
          for (const key of c.key
            .replace(/^\$\.?/, "")
            .split(".")
            .filter(Boolean))
            actual = (actual as Row)?.[key];
          if (typeof actual === "object") actual = JSON.stringify(actual);
        }
        return {
          name: c.name,
          passed: String(actual) === c.expected,
          actual: String(actual ?? "Missing"),
          expected: c.expected,
        };
      } catch {
        return {
          name: c.name,
          passed: false,
          actual: "Invalid JSON response",
          expected: c.expected,
        };
      }
    });
}
export function flattenRequests(
  items: Row[],
  prefix: number[] = [],
): { item: Row; path: number[] }[] {
  return items.flatMap((item, i) =>
    Array.isArray(item.item)
      ? flattenRequests(item.item, [...prefix, i])
      : [{ item, path: [...prefix, i] }],
  );
}
