import type { Row } from "./api";
export function parseCurl(text: string): Row {
  const args: string[] = [];
  let value = "",
    quote = "",
    escape = false;
  for (const char of text.trim()) {
    if (escape) {
      value += char;
      escape = false;
      continue;
    }
    if (char === "\\" && quote !== "'") {
      escape = true;
      continue;
    }
    if (quote) {
      if (char === quote) quote = "";
      else value += char;
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }
    if (/\s/.test(char)) {
      if (value) {
        args.push(value);
        value = "";
      }
    } else value += char;
  }
  if (quote || escape) throw new Error("Unclosed curl quote or escape");
  if (value) args.push(value);
  if (args.shift() !== "curl") throw new Error("Start with curl");
  const result: Row = { method: "GET", url: "", body: "", headers: {} };
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === "-X" || a === "--request") result.method = args[++i];
    else if (a === "-H" || a === "--header") {
      const h = args[++i] || "",
        split = h.indexOf(":");
      if (split < 1) throw new Error("Header must contain a colon");
      result.headers[h.slice(0, split).trim()] = h.slice(split + 1).trim();
    } else if (["-d", "--data", "--data-raw", "--data-binary"].includes(a)) {
      result.body = args[++i] || "";
      if (result.body.startsWith("@"))
        throw new Error("File reads are not supported");
      if (result.method === "GET") result.method = "POST";
    } else if (a === "--url") result.url = args[++i];
    else if (/^https?:\/\//.test(a)) result.url = a;
    else if (
      !["-s", "--silent", "--compressed", "-L", "--location"].includes(a)
    )
      throw new Error("Unsupported curl option: " + a);
  }
  if (!result.url) throw new Error("An HTTP(S) URL is required");
  return result;
}
export function snippet(r: Row, language: string) {
  const quote = (s: string) => "'" + s.replace(/'/g, "'\\''") + "'";
  if (language === "fetch")
    return `const response = await fetch(${JSON.stringify(r.url)}, ${JSON.stringify({ method: r.method, headers: r.headers, ...(r.body ? { body: r.body } : {}) }, null, 2)});\nconsole.log(await response.text());`;
  if (language === "java")
    return `var client = java.net.http.HttpClient.newHttpClient();\nvar request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(${JSON.stringify(r.url)}))${Object.entries(
      r.headers || {},
    )
      .map(([k, v]) => `.header(${JSON.stringify(k)}, ${JSON.stringify(v)})`)
      .join(
        "",
      )}\n.method(${JSON.stringify(r.method)}, java.net.http.HttpRequest.BodyPublishers.ofString(${JSON.stringify(r.body || "")})).build();\nSystem.out.println(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body());`;
  return `curl -X ${r.method} ${quote(r.url)}${Object.entries(r.headers || {})
    .map(([k, v]) => " -H " + quote(k + ": " + v))
    .join("")}${r.body ? " --data-raw " + quote(r.body) : ""}`;
}
export async function safeRegex(
  input: string,
  pattern: string,
): Promise<unknown> {
  if (input.length > 100000 || pattern.length > 1000)
    throw new Error("Input or pattern is too large");
  const worker = new Worker(new URL("./regex.worker.ts", import.meta.url), {
    type: "module",
  });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      worker.terminate();
      reject(new Error("Pattern exceeded the 1 second execution limit"));
    }, 1000);
    worker.onmessage = (e) => {
      clearTimeout(timer);
      worker.terminate();
      e.data.error ? reject(new Error(e.data.error)) : resolve(e.data.matches);
    };
    worker.onerror = () => {
      clearTimeout(timer);
      worker.terminate();
      reject(new Error("Regex worker failed"));
    };
    worker.postMessage({ input, pattern });
  });
}
