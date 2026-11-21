export type Row = Record<string, any>;
let csrf = "";

export class PlatformRequestError extends Error {
  constructor(message: string, public readonly status: number, public readonly requestId: string | null) {
    super(message + (requestId ? " · Request ID " + requestId : ""));
  }
}
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
    const failure = r.headers.get("X-Platform-Failure");
    if (failure === "platform_session_required") message = "Your platform session is missing or expired. Sign in and retry.";
    else if (failure === "login_rejected") message = "Platform sign-in failed. Check your account and password.";
    else if (failure === "platform_access_denied") message = "Your platform account does not have permission for this action.";
    else if (failure === "csrf_missing" || failure === "csrf_invalid") {
      resetCsrf();
      message = "The platform security token is missing or stale. Retry the action.";
    }
    throw new PlatformRequestError(message, r.status, r.headers.get("X-Request-ID"));
  }
  if (r.status === 204 || r.headers.get("content-length") === "0") return null;
  const text = await r.text();
  return text ? JSON.parse(text) : null;
}
export const post = (path: string, data: unknown = {}) =>
  request(path, { method: "POST", body: JSON.stringify(data) });

export const del = (path: string) => request(path, { method: "DELETE" });
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

// ---------------------------------------------------------------- unified chat

export type ChatCapabilities = {
  mode: "chat" | "task";
  streaming: boolean;
  multiTurn: boolean;
  contextAttach: boolean;
  browserBacked: boolean;
};

export type ChatProvider = {
  connectionId: string;
  provider: string;
  name: string;
  account: string;
  host: string;
  state: string;
  definition?: boolean;
  capabilities: ChatCapabilities;
};

export type ChatConversation = {
  id: string;
  connection_id?: string;
  provider?: string;
  title: string;
  created_at: string;
  updated_at: string;
};

export type ChatMessage = {
  id: string;
  seq: number;
  role: "user" | "assistant" | "system" | "task";
  content: string;
  provider?: string;
  account?: string;
  status: "complete" | "streaming" | "cancelled" | "error";
  meta?: string | { provider?: string; connectionId?: string; account?: string; task?: { sessionId: string; status: string; url: string }; error?: { code: string; message: string; action: string } };
  created_at: string;
};

export type ChatPreview = {
  confirmationId: string;
  destination: string;
  provider: string;
  account: string;
  prompt: string;
  context: unknown;
  capabilities: ChatCapabilities;
  historyTransfer?: { role: string; content: string }[];
};

export type ChatStreamEvent =
  | { type: "meta"; data: { messageId: string; provider: string; account: string } }
  | { type: "delta"; data: { text: string } }
  | { type: "task"; data: { sessionId: string; status: string; url: string } }
  | { type: "done"; data: { messageId: string; requestId: string } }
  | { type: "error"; data: { code: string; message: string; action: "RECONNECT" | "FIX_CONFIG" | "RETRY"; upstreamStatus?: number } };

/** Incremental text/event-stream parser; feed raw chunks, get complete events. */
export function createSseParser(onEvent: (event: ChatStreamEvent) => void) {
  let buffer = "";
  const flush = () => {
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() ?? "";
    for (const block of blocks) {
      if (!block.trim()) continue;
      let event = "message";
      const data: string[] = [];
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
      if (!data.length) continue;
      let parsed: unknown = {};
      try {
        parsed = JSON.parse(data.join("\n"));
      } catch {
        parsed = { text: data.join("\n") };
      }
      onEvent({ type: event as ChatStreamEvent["type"], data: parsed } as ChatStreamEvent);
    }
  };
  return {
    feed(chunk: string) {
      buffer += chunk;
      flush();
    },
    end() {
      buffer += "\n\n";
      flush();
    },
  };
}

/** POST an SSE endpoint and stream parsed events; abort via the returned controller. */
export async function streamSse(
  path: string,
  body: unknown,
  signal: AbortSignal,
  onEvent: (event: ChatStreamEvent) => void,
) {
  if (!csrf) {
    const r = await fetch("/api/csrf");
    csrf = (await r.json()).token;
  }
  const response = await fetch("/api" + path, {
    method: "POST",
    signal,
    headers: {
      "X-XSRF-TOKEN": csrf,
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    let message = "Request failed (" + response.status + ")";
    try {
      message = (await response.json()).message || message;
    } catch {}
    throw new PlatformRequestError(message, response.status, response.headers.get("X-Request-ID"));
  }
  const parser = createSseParser(onEvent);
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.feed(decoder.decode(value, { stream: true }));
    }
    parser.end();
  } finally {
    reader.releaseLock();
  }
}

// ------------------------------------------------------------ connection studio

export type StudioCapture = {
  id?: string;
  method: string;
  url: string;
  status: number;
  contentType: string;
  body?: string;
  responsePreview?: string;
  sse?: boolean;
  headers?: Record<string, string>;
};

export type StudioAction = {
  type: string;
  description: string;
  locator?: string;
  valueLength?: number;
  sensitive?: boolean;
};

export type StudioSession = {
  id: string;
  connectionId: string;
  status: "OPENING" | "WAITING_FOR_SIGN_IN" | "READY" | "RECORDING" | "REVIEW" | "VALIDATED" | "CANCELLED" | "ERROR";
  currentUrl?: string;
  url?: string;
  error?: string;
  identity?: string;
  promptMarker?: string;
  captures: StudioCapture[];
  actions: StudioAction[];
  inferred?: Row;
};

export type ActivityEvent = {
  id: number;
  type: string;
  message: string;
  created_at: string;
};

export type OAuthStart = {
  authorizeUrl?: string;
  state?: string;
  requirement?: { code: string; message: string; alternatives?: string[] };
};

export type JiraSearchResult = {
  issues: { key: string; summary?: string; status?: string }[];
  nextPageToken?: string;
};
