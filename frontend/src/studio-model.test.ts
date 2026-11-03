import { describe, it, expect } from "vitest";
import {
  newDraft,
  pair,
  splitUrl,
  readItem,
  buildRequest,
  serializedItem,
  replaceItem,
  evaluateChecks,
  flattenRequests,
} from "./studio-model";
import { parseCurl } from "./request-tools";
describe("Request Studio request lifecycle", () => {
  it("interpolates URLs, enabled parameters and headers without losing repeated query keys", () => {
    const d = {
      ...newDraft(),
      url: "{{baseUrl}}/items",
      params: [
        pair("tag", "one"),
        pair("tag", "two"),
        { ...pair("skip", "yes"), enabled: false },
      ],
      headers: [pair("X-Team", "{{team}}")],
    };
    const r = buildRequest(d, { baseUrl: "https://example.com", team: "api" });
    expect(new URL(r.url).searchParams.getAll("tag")).toEqual(["one", "two"]);
    expect(r.url).not.toContain("skip");
    expect(r.headers["X-Team"]).toBe("api");
  });
  it("does not duplicate query parameters after typing a URL", () => {
    const d = {
      ...newDraft(),
      url: "https://example.com/items?a=1",
      params: [pair("a", "1")],
    };
    expect(buildRequest(d, {}).url).toBe("https://example.com/items?a=1");
  });
  it("blocks unresolved variables before sending", () => {
    expect(() =>
      buildRequest({ ...newDraft(), url: "{{baseUrl}}/items" }, {}),
    ).toThrow("Unresolved variable");
  });
  it("applies bearer auth case-insensitively over an existing header", () => {
    const r = buildRequest(
      {
        ...newDraft(),
        url: "https://example.com",
        auth: "bearer",
        token: "secret",
        headers: [pair("authorization", "old")],
      },
      {},
    );
    expect(r.headers).toEqual({ Authorization: "Bearer secret" });
  });
  it("handles UTF-8 Basic credentials", () => {
    const r = buildRequest(
      {
        ...newDraft(),
        url: "https://example.com",
        auth: "basic",
        username: "é",
        password: "pass",
      },
      {},
    );
    expect(r.headers.Authorization).toBe("Basic w6k6cGFzcw==");
  });
  it("sends API keys in query parameters when configured", () => {
    expect(
      buildRequest(
        {
          ...newDraft(),
          url: "https://example.com",
          auth: "apikey",
          apiKey: "access",
          apiLocation: "query",
          token: "abc",
        },
        {},
      ).url,
    ).toContain("access=abc");
  });
  it("encodes form bodies and JSON GraphQL variables", () => {
    const d = {
      ...newDraft(),
      url: "https://example.com",
      bodyMode: "urlencoded" as const,
      form: [pair("q", "a & b")],
    };
    expect(buildRequest(d, {}).body).toBe("q=a+%26+b");
    expect(
      JSON.parse(
        buildRequest(
          {
            ...d,
            bodyMode: "graphql",
            body: "query { ok }",
            graphqlVariables: '{"id":1}',
          },
          {},
        ).body,
      ).variables,
    ).toEqual({ id: 1 });
  });
  it("rejects malformed JSON bodies", () => {
    expect(() =>
      buildRequest(
        {
          ...newDraft(),
          url: "https://example.com",
          bodyMode: "raw",
          language: "JSON",
          body: "{",
        },
        {},
      ),
    ).toThrow();
  });
  it("preserves nested Postman request headers, body and auth on load", () => {
    const d = readItem(
      {
        name: "Create",
        request: {
          method: "POST",
          url: "https://example.com?q=one",
          header: [{ key: "X-Test", value: "yes", disabled: true }],
          body: {
            mode: "raw",
            raw: '{"ok":true}',
            options: { raw: { language: "json" } },
          },
          auth: {
            type: "bearer",
            bearer: [{ key: "token", value: "{{token}}" }],
          },
        },
      },
      "c",
      [0, 1],
    );
    expect(d.params[0].key).toBe("q");
    expect(d.headers[0].enabled).toBe(false);
    expect(d.bodyMode).toBe("raw");
    expect(d.auth).toBe("bearer");
    expect(d.itemPath).toEqual([0, 1]);
  });
  it("saves auth and sensitive parameter/header fields as variable references", () => {
    const d = {
      ...newDraft(),
      url: "https://example.com",
      auth: "bearer" as const,
      token: "secret-token",
      headers: [pair("X-API-Key", "secret-key")],
      params: [pair("token", "secret-query")],
    };
    const saved = JSON.stringify(serializedItem(d));
    expect(saved).not.toContain("secret-token");
    expect(saved).not.toContain("secret-key");
    expect(saved).not.toContain("secret-query");
    expect(saved).toContain("{{bearerToken}}");
  });
  it("replaces a nested request without duplicating it or losing siblings", () => {
    const items = [
      { name: "Folder", item: [{ name: "one" }, { name: "two" }] },
    ];
    expect(replaceItem(items, [0, 1], { name: "changed" })[0].item).toEqual([
      { name: "one" },
      { name: "changed" },
    ]);
    expect(items[0].item[1].name).toBe("two");
    expect(flattenRequests(items).map((x) => x.path)).toEqual([
      [0, 0],
      [0, 1],
    ]);
  });
  it("evaluates real response status, body, headers and JSON assertions", () => {
    const checks = [
      {
        name: "status",
        type: "status" as const,
        key: "",
        expected: "201",
        enabled: true,
      },
      {
        name: "header",
        type: "header" as const,
        key: "CONTENT-TYPE",
        expected: "application/json",
        enabled: true,
      },
      {
        name: "json",
        type: "json" as const,
        key: "data.id",
        expected: "2",
        enabled: true,
      },
    ];
    expect(
      evaluateChecks(checks, {
        status: 201,
        headers: { "content-type": "application/json" },
        body: '{"data":{"id":2}}',
      }).every((x) => x.passed),
    ).toBe(true);
    expect(
      evaluateChecks(checks, { status: 500, body: "oops" }).every(
        (x) => !x.passed,
      ),
    ).toBe(true);
  });
  it("imports multiline Bash curl without executing it", () => {
    expect(
      parseCurl("curl 'https://example.com' \\\n  -H 'X-Team: api'").headers,
    ).toEqual({ "X-Team": "api" });
  });
 it("defers only known saved secrets to the server and keeps Basic auth structured",()=>{const d={...newDraft(),url:"https://example.com",auth:"basic" as const,username:"user",password:"{{password}}"};const r=buildRequest(d,{password:"{{password}}"},["password"]);expect(r.auth).toEqual({type:"basic",username:"user",password:"{{password}}"});expect(r.headers.Authorization).toBeUndefined();expect(()=>buildRequest(d,{})).toThrow("Unresolved variable");});

});
