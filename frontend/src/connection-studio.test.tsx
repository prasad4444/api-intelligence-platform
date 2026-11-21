import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import type { Row } from "./api";

const api = vi.hoisted(() => ({
  request: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock("./api", async importOriginal => ({
  ...(await importOriginal<typeof import("./api")>()),
  request: api.request,
  post: api.post,
  del: api.del,
}));

import { ConnectionStudio } from "./ConnectionStudio";

const connection: Row = {
  id: "c1", provider: "jira", name: "My Jira", baseUrl: "https://a.atlassian.net",
  siteHost: "a.atlassian.net", authType: "OAUTH", state: "CONNECTED",
  verifiedIdentity: "alice@a", jiraSites: [{ id: "cloud-1", name: "acme" }], revision: 1,
};

const props = { data: { connections: [connection] }, reload: vi.fn(), run: vi.fn(),
  edit: vi.fn(), spec: undefined, setPage: vi.fn() };

beforeEach(() => {
  api.request.mockReset(); api.post.mockReset(); api.del.mockReset();
  api.request.mockImplementation((path: string) =>
    path.startsWith("/studio/timeline") ? Promise.resolve([
      { id: 1, type: "BROWSER_OPENED", message: "opened", created_at: "t" },
      { id: 2, type: "CONNECTOR_VALIDATED", message: "validated", created_at: "t" },
    ]) : Promise.resolve({}));
});
afterEach(cleanup);

describe("ConnectionStudio", () => {
  it("renders a tab per connection plus Add app, with status and facts", async () => {
    render(<ConnectionStudio {...props} />);
    expect((await screen.findAllByText("My Jira")).length).toBeGreaterThan(0);
    expect(screen.getByText("Add app")).toBeInTheDocument();
    expect(screen.getAllByText("CONNECTED").length).toBeGreaterThan(0);
    expect(screen.getByText("alice@a")).toBeInTheDocument();
    expect(screen.getByText("a.atlassian.net")).toBeInTheDocument();
    expect(screen.getByText("BROWSER OPENED")).toBeInTheDocument();
    expect(screen.getByText("CONNECTOR VALIDATED")).toBeInTheDocument();
  });

  it("shows jira site selection for OAuth connections", async () => {
    render(<ConnectionStudio {...props} />);
    const select = await screen.findByLabelText("Jira site");
    fireEvent.change(select, { target: { value: "cloud-1" } });
    fireEvent.click(screen.getByText("Select site"));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith("/connections/c1/jira-site", { cloudId: "cloud-1" }));
  });

  it("renders structured requirement with alternative method switch", async () => {
    render(<ConnectionStudio {...props} />);
    fireEvent.click(screen.getByText("Add app"));
    fireEvent.change(screen.getByLabelText("URL"), { target: { value: "https://b.atlassian.net" } });
    api.post.mockImplementation((path: string) => {
      if (path === "/connections") return Promise.resolve({ id: "c9" });
      if (path.startsWith("/oauth/")) return Promise.resolve({
        requirement: { code: "OAUTH_APP_REGISTRATION", message: "Register an OAuth app", alternatives: ["JIRA_TOKEN"] },
      });
      return Promise.resolve({});
    });
    fireEvent.click(screen.getByText("Connect"));
    expect(await screen.findByText("OAUTH_APP_REGISTRATION")).toBeInTheDocument();
    fireEvent.click(screen.getByText("Use email + API token instead"));
    expect((screen.getByLabelText("Method") as HTMLSelectElement).value).toBe("jira-token");
  });

  it("drives the pairing stepper through the studio endpoints", async () => {
    render(<ConnectionStudio {...props} />);
    let session: Row = { id: "s1", connectionId: "c1", status: "WAITING_FOR_SIGN_IN", captures: [], actions: [] };
    api.post.mockImplementation((path: string) => {
      if (path === "/studio/sessions") return Promise.resolve(session);
      if (path.endsWith("/signed-in")) { session = { ...session, status: "READY" }; return Promise.resolve(session); }
      if (path.endsWith("/record/start")) { session = { ...session, status: "RECORDING" }; return Promise.resolve(session); }
      if (path.endsWith("/record/stop")) { session = { ...session, status: "REVIEW", captures: [{ method: "POST", url: "https://a/chat", status: 200, contentType: "application/json" }], actions: [{ type: "click", description: "Click button 'Send'" }] }; return Promise.resolve(session); }
      if (path.endsWith("/infer")) return Promise.resolve({ chatPath: "/chat", aiMethod: "POST", aiResponseFormat: "json", aiResponsePointer: "/text", replayMode: "http", aiRequestTemplate: { prompt: "{{prompt}}" }, steps: [] });
      if (path.endsWith("/validate")) { session = { ...session, status: "VALIDATED" }; return Promise.resolve({ id: "c1", state: "CONNECTED" }); }
      return Promise.resolve({});
    });
    api.request.mockImplementation((path: string) => {
      if (path.startsWith("/studio/timeline")) return Promise.resolve([]);
      if (path === "/studio/sessions/s1") return Promise.resolve(session);
      return Promise.resolve({});
    });
    fireEvent.click(await screen.findByText("Open browser"));
    expect(api.post).toHaveBeenCalledWith("/studio/sessions", { connectionId: "c1", url: "https://a.atlassian.net" });
    fireEvent.click(await screen.findByText("I've signed in"));
    fireEvent.change(await screen.findByLabelText("Demo prompt"), { target: { value: "probe-1" } });
    fireEvent.click(screen.getByText("Start recording"));
    fireEvent.click(await screen.findByText("Stop recording"));
    expect(await screen.findByText("https://a/chat")).toBeInTheDocument();
    expect(screen.getByText(/Click button 'Send'/)).toBeInTheDocument();
    fireEvent.click(screen.getByText("Infer connector"));
    fireEvent.click(await screen.findByText("Validate & activate"));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith("/studio/sessions/s1/validate",
      expect.objectContaining({ config: expect.objectContaining({ chatPath: "/chat" }) })));
  });
});
