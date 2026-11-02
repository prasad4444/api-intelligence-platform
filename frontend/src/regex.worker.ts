self.onmessage = (event: MessageEvent<{ input: string; pattern: string }>) => {
  try {
    const matches: { match: string; index: number | undefined }[] = [];
    for (const match of event.data.input.matchAll(
      new RegExp(event.data.pattern, "g"),
    )) {
      matches.push({ match: match[0], index: match.index });
      if (matches.length === 100) break;
    }
    self.postMessage({ matches });
  } catch (error) {
    self.postMessage({ error: String(error) });
  }
};
