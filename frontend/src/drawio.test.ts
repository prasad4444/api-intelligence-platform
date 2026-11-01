import { it, expect } from "vitest";
import { exportDrawio, importDrawio } from "./drawio";
it("round trips Mermaid source in valid draw.io graph XML", async () => {
  const source = "flowchart TD\n A[Client] --> B[Gateway]\n B --> C[Orders]";
  const xml = exportDrawio(source);
  expect(xml).toContain("mxGraphModel");
  expect(xml).toContain('vertex="1"');
  expect(await importDrawio(xml)).toBe(source);
});
it("imports native graph edges while dropping HTML labels", async () => {
  const source = await importDrawio(
    '<mxfile><diagram><mxGraphModel><root><mxCell id="a" vertex="1" value="Client"/><mxCell id="b" vertex="1" value="API"/><mxCell edge="1" source="a" target="b"/></root></mxGraphModel></diagram></mxfile>',
  );
  expect(source).toContain("N0 --> N1");
  expect(source).toContain("Client");
});
