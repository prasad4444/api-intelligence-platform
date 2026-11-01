function xmlDoc() {
  return document.implementation.createDocument("", "mxfile");
}
export function exportDrawio(source: string) {
  const doc = xmlDoc();
  const create = (
    name: string,
    attrs: Record<string, string>,
    parent: Element,
  ) => {
    const el = doc.createElement(name);
    Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
    parent.appendChild(el);
    return el;
  };
  const diagram = create(
      "diagram",
      { name: "Architecture", id: "architecture" },
      doc.documentElement,
    ),
    model = create("mxGraphModel", { apiintelMermaid: source }, diagram),
    root = create("root", {}, model);
  create("mxCell", { id: "0" }, root);
  create("mxCell", { id: "1", parent: "0" }, root);
  const nodes = new Map<string, string>(),
    edges: { from: string; to: string }[] = [];
  const token = '([A-Za-z_][A-Za-z0-9_]*)(?:\\["?([^\\]]*?)"?\\])?';
  for (const line of source.split("\n")) {
    const match = line.match(new RegExp(token + "\\s*--?>\\s*" + token));
    if (match) {
      nodes.set(match[1], match[2] || nodes.get(match[1]) || match[1]);
      nodes.set(match[3], match[4] || nodes.get(match[3]) || match[3]);
      edges.push({ from: match[1], to: match[3] });
    }
  }
  if (!nodes.size) nodes.set("source", "Mermaid source: " + source);
  let index = 0;
  for (const [id, label] of nodes) {
    const cell = create(
      "mxCell",
      {
        id: "n_" + id,
        value: label,
        vertex: "1",
        parent: "1",
        style: "rounded=1;whiteSpace=wrap;html=0;",
      },
      root,
    );
    create(
      "mxGeometry",
      {
        x: String(40 + (index % 3) * 220),
        y: String(40 + Math.floor(index / 3) * 130),
        width: "170",
        height: "70",
        as: "geometry",
      },
      cell,
    );
    index++;
  }
  edges.forEach((edge, i) => {
    const cell = create(
      "mxCell",
      {
        id: "e" + i,
        edge: "1",
        parent: "1",
        source: "n_" + edge.from,
        target: "n_" + edge.to,
        style: "endArrow=classic;",
      },
      root,
    );
    create("mxGeometry", { relative: "1", as: "geometry" }, cell);
  });
  return new XMLSerializer().serializeToString(doc);
}
export async function importDrawio(text: string) {
  let doc = new DOMParser().parseFromString(text, "application/xml");
  if (doc.querySelector("parsererror")) throw new Error("Invalid draw.io XML");
  const diagram = doc.querySelector("diagram");
  if (!diagram) throw new Error("No diagram found");
  if (!diagram.querySelector("mxGraphModel")) {
    const encoded = diagram.textContent?.trim() || "";
    if (
      encoded.startsWith("flowchart") ||
      encoded.startsWith("sequenceDiagram")
    )
      return encoded;
    const bytes = Uint8Array.from(atob(encoded), (c) => c.charCodeAt(0));
    const inflated = await new Response(
      new Blob([bytes])
        .stream()
        .pipeThrough(new DecompressionStream("deflate-raw")),
    ).text();
    doc = new DOMParser().parseFromString(
      decodeURIComponent(inflated),
      "application/xml",
    );
  }
  const model = doc.querySelector("mxGraphModel");
  if (!model) throw new Error("No supported graph model found");
  if (model.hasAttribute("apiintelMermaid"))
    return model.getAttribute("apiintelMermaid")!;
  const vertices = Array.from(model.querySelectorAll('mxCell[vertex="1"]'));
  const mapping = new Map(
    vertices.map((cell, i) => [cell.getAttribute("id"), "N" + i]),
  );
  const label = (value: string) =>
    new DOMParser()
      .parseFromString(value, "text/html")
      .body.textContent!.replace(/["\[\]<>\r\n]/g, " ")
      .slice(0, 200);
  const lines = [
    "flowchart TD",
    ...vertices.map(
      (cell) =>
        " " +
        mapping.get(cell.getAttribute("id")) +
        '["' +
        label(cell.getAttribute("value") || "Node") +
        '"]',
    ),
  ];
  model.querySelectorAll('mxCell[edge="1"]').forEach((cell) => {
    const a = mapping.get(cell.getAttribute("source")),
      b = mapping.get(cell.getAttribute("target"));
    if (a && b) lines.push(" " + a + " --> " + b);
  });
  return lines.join("\n");
}
