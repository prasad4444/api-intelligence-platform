import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { createSseParser, type ChatStreamEvent, type Row } from "./api";

const api = vi.hoisted(() => ({
  request: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
  streamSse: vi.fn(),
}));

vi.mock("./api", async importOriginal => ({
  ...(await importOriginal<typeof import("./api")>()),
  request: api.request,
  post: api.post,
  del: api.del,
  streamSse: api.streamSse,
}));

import { ChatPanel } from "./Chat";

const provider: Row = {
  connectionId: "conn-1", provider: "custom", name: "Fixture Chat",
  account: "alice@x", host: "fixture", state: "CONNECTED",
  capabilities: { mode: "chat", streaming: true, multiTurn: true, contextAttach: true, browserBacked: false },
};

const taskProvider: Row = {
  connectionId: "conn-2", provider: "devin", name: "Devin",
  account: "devin@org", host: "api.devin.ai", state: "CONNECTED",
  capabilities: { mode: "task", streaming: false, multiTurn: false, contextAttach: false, browserBacked: false },
};

function mockRequest(routes: Record<string, unknown>) {
  api.request.mockImplementation((path: string) => {
    if (Object.prototype.hasOwnProperty.call(routes, path)) return Promise.resolve(routes[path]);
    let best = "";
    let value: unknown;
    for (const [key, v] of Object.entries(routes)) {
      if (path.startsWith(key) && key.length > best.length) { best = key; value = v; }
    }
    return Promise.resolve(best ? value : []);
  });
}

beforeEach(() => {
  api.request.mockReset(); api.post.mockReset(); api.del.mockReset(); api.streamSse.mockReset();
  mockRequest({
    "/chat/providers": [provider, taskProvider],
    "/chat/conversations": [{ id: "c1", title: "First chat", connection_id: "conn-1", provider: "custom", created_at: "", updated_at: "" }],
    "/chat/conversations/c1/messages": [{ id: "m1", seq: 1, role: "user", content: "hi", status: "complete", created_at: "" }],
    "/connections": [], "/discovery/requests": [],
  });
});
afterEach(cleanup);

describe("SSE parser", () => {
  it("handles events split across chunk boundaries and multi-line data", () => {
    const events: ChatStreamEvent[] = [];
    const parser = createSseParser(event => events.push(event));
    parser.feed("event: meta\nda");
    parser.feed("ta: {\"messageId\":\"m1\"}\n\nevent: del");
    parser.feed("ta\ndata: hello ");
    parser.feed("\ndata: world\n\nevent: done\ndata: {\"messageId\":\"m1\"}\n\n");
    parser.end();
    expect(events.map(e => e.type)).toEqual(["meta", "delta", "done"]);
    expect((events[1].data as { text: string }).text).toBe("hello \nworld");
  });
});

describe("ChatPanel", () => {
  it("lists providers grouped with capability chips", async () => {
    render(<ChatPanel />);
    await screen.findByText("First chat");
    const select = screen.getByLabelText("Chat provider") as HTMLSelectElement;
    expect(select.querySelector("optgroup[label='devin']")).toBeTruthy();
    expect(screen.getByText("Streaming")).toBeInTheDocument();
    expect(screen.getByText("CONNECTED")).toBeInTheDocument();
  });

  it("shows the transfer modal when preview returns historyTransfer and sends transferHistory on confirm", async () => {
    api.post.mockImplementation((path: string, body?: Row) => {
      if (path.includes("/preview")) return Promise.resolve({
        confirmationId: "conf-1", destination: "h", provider: "devin", account: "d",
        prompt: "p", context: {}, historyTransfer: [{ role: "user", content: "earlier" }],
      });
      if (path.includes("/conversations") && !path.includes("/c1")) return Promise.resolve({ id: "c1" });
      return Promise.resolve({});
    });
    render(<ChatPanel />);
    await screen.findByText("First chat");
    fireEvent.click(screen.getByText("First chat"));
    // Switch to devin (bound to conn-1)
    fireEvent.change(screen.getByLabelText("Chat provider"), { target: { value: "conn-2" } });
    fireEvent.change(screen.getByLabelText("Message"), { target: { value: "hello" } });
    fireEvent.click(screen.getByText("Send"));
    expect(await screen.findByRole("dialog", { name: "Transfer history" })).toBeInTheDocument();
    expect(screen.getByText("earlier")).toBeInTheDocument();
    api.post.mockClear();
    api.post.mockImplementation((path: string) => path.includes("/preview")
      ? Promise.resolve({ confirmationId: "conf-2", destination: "h", provider: "devin", prompt: "p", context: {} })
      : Promise.resolve({}));
    api.streamSse.mockImplementation(async (_p, _b, _s, onEvent) => {
      onEvent({ type: "done", data: { messageId: "m", requestId: "r" } });
    });
    fireEvent.click(screen.getByText("Send history to devin"));
    await waitFor(() => expect(api.streamSse).toHaveBeenCalled());
    const sentBody = api.streamSse.mock.calls[0][1] as Row;
    expect(sentBody.transferHistory).toBe(true);
  });

  it("streams deltas into the thread and Stop cancels", async () => {
    api.post.mockImplementation((path: string, body?: Row) => {
      if (path === "/chat/conversations") return Promise.resolve({ id: "c1", title: "New chat", connection_id: "conn-1" });
      if (path.includes("/preview")) return Promise.resolve({
        confirmationId: "c", destination: "h", provider: "custom", account: "a", prompt: "hi", context: {},
      });
      if (path.includes("/cancel")) return Promise.resolve({ cancelled: true });
      return Promise.resolve({});
    });
    api.streamSse.mockImplementation(async (_p, _b, signal, onEvent) => {
      onEvent({ type: "meta", data: { messageId: "m2", provider: "custom", account: "a" } });
      onEvent({ type: "delta", data: { text: "partial " } });
      onEvent({ type: "delta", data: { text: "answer" } });
      await new Promise(() => {}); // stay streaming until aborted
    });
    render(<ChatPanel />);
    await screen.findByText("First chat");
    fireEvent.change(screen.getByLabelText("Message"), { target: { value: "hi" } });
    fireEvent.click(screen.getByText("Send"));
    fireEvent.click(await screen.findByText("Confirm and send"));
    expect(await screen.findByText("partial answer")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Stop"));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith("/chat/conversations/c1/cancel"));
  });

  it("Jira picker pages via nextPageToken", async () => {
    mockRequest({
      "/chat/providers": [provider],
      "/chat/conversations": [],
      "/connections": [{ id: "jc", provider: "jira", name: "J", verifiedIdentity: "me" }],
      "/discovery/requests": [],
      "/jira/jc/search?jql=project%20%3D%20ABC": { issues: [{ key: "ABC-1", summary: "one" }], nextPageToken: "t2" },
      "/jira/jc/search?jql=project%20%3D%20ABC&nextPageToken=t2": { issues: [{ key: "ABC-2", summary: "two" }] },
    });
    render(<ChatPanel />);
    fireEvent.click(await screen.findByLabelText("Attach context"));
    fireEvent.change(screen.getByLabelText("Attach"), { target: { value: "jira" } });
    fireEvent.change(screen.getByLabelText("Jira connection"), { target: { value: "jc" } });
    fireEvent.change(screen.getByLabelText("JQL"), { target: { value: "project = ABC" } });
    fireEvent.click(screen.getByText("Search"));
    expect(await screen.findByText("ABC-1")).toBeInTheDocument();
    fireEvent.click(screen.getByText("Load more"));
    expect(await screen.findByText("ABC-2")).toBeInTheDocument();
    expect(api.request).toHaveBeenCalledWith(expect.stringContaining("nextPageToken=t2"));
  });
});
