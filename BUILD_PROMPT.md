# API Intelligence Platform: lean build prompt

Build a Spring Boot 3 / Java 17 / Maven monolith with file-based H2 + Flyway and a premium React + Vite + TS + Tailwind UI bundled into the jar. Keep it generic (no employer names or hosts), seed demo data, and open a PR.

Modules (sidebar, API/version selector, Ctrl+K, light/dark):
1. Dashboard: KPIs, governance score, tests, PRs, Jira, capacity summary.
2. API Workspace: Swagger UI replica (OpenAPI import, try-it via backend proxy, lint, version diff, approval workflow).
3. GitHub: PAT, browse, and after approval create a branch, commit and open a PR; PR status; Jira links.
4. Postman replica: collections, auth, body types, env vars, history, runner, OpenAPI to collection, v2.1 import/export, code snippets.
5. Jira: JQL + CSV, sprint board, bulk actions, issues from failed tests.
6. Team Capacity: roles (Dev/Test), region (USA/India), sprint dates, leave, regional holidays, Dev/Test points per story, capacity vs committed, heatmap, CSV.
7. Test generation: positive/negative/boundary/security cases, exported to Postman, JUnit5 + RestAssured, Cucumber and CSV; run and record results.
8. Governance: rules, scores, findings, trends.
9. Architecture: Mermaid live preview, diagrams from specs, draw.io import/export.
10. AI Copilot: pluggable providers (OpenAI-compatible, Anthropic, Azure, Ollama), assistants, rule-based fallback.
11. Dev Utilities: JSON/XML/YAML/text compare, formatters, JWT, Base64, hashes, curl, AES, regex.
12. Admin: roles, AES-GCM vault, integrations, audit trail, H2 console toggle.

Quality: modular packages, validation, springdoc, WebClient proxy with SSRF guard, tests (`mvn verify` green), Docker, GitHub Actions CI, README.
Token discipline: reuse shared components, don't regenerate unchanged files, keep commits incremental.

## Excel template import/export (Team Capacity + Stories)
- "Download template" button gives an .xlsx (Apache POI) with sheets: Stories (Key, Summary, Priority, Status, Sprint, Dev Points, Test Points, Developer, Tester, Epic, Fix Version), Team (Name, Role, Region, Hours/Day, Allocation %), Sprints (Name, Start, End), Leaves (Name, From, To, Type), Holidays (Date, Region, Name). Include header styling, data validation dropdowns (Priority, Role, Region) and an Instructions sheet.
- "Upload" validates rows (per-row error report), upserts into H2, and every screen (Dashboard, Capacity matrix, heatmap, sprint charts, story grid) reflects it immediately.
- "Export" downloads the current data in the same format.
