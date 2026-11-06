import { describe, expect, it } from "vitest";
import { exportAiCurl, importAiCurl } from "./ai-curl";

describe("Stylus cURL templates", () => {
  it("round-trips method, body and vault references without credentials", () => {
    const generated = exportAiCurl("https://company.example/stylus/chat", "POST",
      {"X-Internal-Key":"{{vault:stylus-key}}"}, '{"message":"{{prompt}}"}', "BEARER", "stylus-token");
    expect(importAiCurl(generated)).toMatchObject({
      method:"POST", authType:"BEARER", secretRef:"stylus-token", headers:{"X-Internal-Key":"{{vault:stylus-key}}"},
    });
  });

  it("rejects literal authorization, cookies, file access and shell expressions", () => {
    const base = "curl 'https://company.example/stylus'";
    for (const suffix of [
      " -H 'Authorization: Bearer sensitive-value'", " -H 'Cookie: sid=sensitive-value'",
      " --data-binary '@/etc/passwd'", " $(cat example)", 
      " --data-raw '{\"message\":\"{{prompt}}\",\"access_token\":\"sensitive-value\"}'",
    ]) expect(() => importAiCurl(base + suffix)).toThrow();
  });
});
