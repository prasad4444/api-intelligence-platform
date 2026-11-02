import { it, expect } from "vitest";
import { parseCurl, snippet } from "./request-tools";
it("imports a quoted curl request without executing shell text", () => {
  expect(
    parseCurl(
      `curl -X POST 'https://example.com/a' -H 'X-Test: one two' --data-raw '{"a":1}'`,
    ),
  ).toEqual({
    method: "POST",
    url: "https://example.com/a",
    headers: { "X-Test": "one two" },
    body: '{"a":1}',
  });
});
it("rejects curl file references and unsupported commands", () => {
  expect(() => parseCurl("curl https://example.com --data @secret")).toThrow();
  expect(() => parseCurl("wget https://example.com")).toThrow();
});
it("exports headers and escaped payloads in a curl snippet", () => {
  const r = {
    method: "POST",
    url: "https://example.com",
    headers: { "Content-Type": "application/json" },
    body: '{"name":"sample"}',
  };
  expect(parseCurl(snippet(r, "curl"))).toEqual(r);
  expect(snippet(r, "fetch")).toContain("await fetch");
  expect(snippet(r, "java")).toContain("HttpRequest");
});
