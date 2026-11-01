# Verification report — October 29, 2026

## Results

| Check | Result |
| --- | --- |
| Java 17 `mvn clean verify` with bundled UI | Passed |
| JUnit / MockMvc / WireMock tests | 25 passed, 0 failed |
| JaCoCo backend line coverage | 86.84% (1,372 / 1,580 lines); 80% gate enforced |
| TypeScript + Vite production build | Passed |
| Vitest component and utility tests | 11 passed |
| Playwright browser tests | 5 passed |
| Mobile width | 390 px; no horizontal document overflow |
| Git diff whitespace check | Passed |

## Verified behavior

- Real BCrypt login success and failure, CSRF rejection, admin-only vault operations and connector role enforcement.
- H2 migrations, seed data, optimistic revisions, concurrent-update rejection, history, restore and deletion.
- OpenAPI import/lint/review, recursive diff, collection generation, governance rules and score snapshots.
- Schema examples, references, bounds, path/query parameters and authentication omission in generated drafts.
- Test regeneration preserves manual edits; execution records pass/fail evidence and suites run their saved cases.
- Capacity calculations, leave/holiday reduction, styled Excel template/export and atomic row-validation imports.
- Test-case Excel round trips and Jira CSV validation/import.
- AES-GCM vault rotation and plaintext non-disclosure.
- SSRF restrictions, header filtering, mocked successful HTTP requests and request failures.
- GitHub Bearer, Jira Basic, AI API-key and OAuth2 client-credentials HTTP contracts via WireMock.
- Single-use write approvals, connection-revision binding, failure consumption, Friday event proposals and SSE signal completion.
- Every sidebar page, command palette, theme switching, contract lint, guarded try-it, Request Studio send, Excel upload, capacity matrix, test generation/history, Devin form and fallback assistant.
- Live Mermaid rendering, native draw.io node/edge import and source-preserving export.
- Curl import/export, Java/fetch snippets, and browser regex timeout.

The successful Request Studio browser response is mocked for deterministic UI testing; the backend HTTP proxy is separately exercised against WireMock. No production provider keys, paid AI sessions, real Jira writes or real GitHub writes from the built application were used. Docker configuration is supplied but the image was not built in this environment. Full vendor parity, SSO/interactive OAuth and vendor-specific Zephyr mappings are outside this version; see README scope.

## Screenshots

### Admin

![admin](screenshots/admin.png)

### Ai Copilot

![ai-copilot](screenshots/ai-copilot.png)

### Api Workspace

![api-workspace](screenshots/api-workspace.png)

### Architecture

![architecture](screenshots/architecture.png)

### Dashboard

![dashboard](screenshots/dashboard.png)

### Dev Utilities

![dev-utilities](screenshots/dev-utilities.png)

### Devin Configuration

![devin-configuration](screenshots/devin-configuration.png)

### Github

![github](screenshots/github.png)

### Governance

![governance](screenshots/governance.png)

### Integration Hub

![integration-hub](screenshots/integration-hub.png)

### Jira

![jira](screenshots/jira.png)

### Mobile Dashboard

![mobile-dashboard](screenshots/mobile-dashboard.png)

### Request Studio

![request-studio](screenshots/request-studio.png)

### Team Capacity

![team-capacity](screenshots/team-capacity.png)

### Test Library

![test-library](screenshots/test-library.png)
