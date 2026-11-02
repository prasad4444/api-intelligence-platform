# Request Studio and API-linked test generation

Request Studio uses a dense Postman-style workbench with its own application branding. It is not a complete implementation of Postman.

## Implemented

- Independent request tabs, duplicate/close, unsaved-change indication and confirmation, editable names, Ctrl/Cmd+Enter to send and Ctrl/Cmd+S to save.
- Nested collection/folder browsing, search, create collection/folder/request, update requests without duplicating them, collection v2.1 import/export and curl import.
- GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS; enabled query parameters and headers; no auth, Bearer, UTF-8 Basic, and API keys in headers or query strings.
- None, raw JSON/text/XML, URL-encoded and GraphQL bodies. JSON beautification and validation.
- Named variable profiles with global → collection defaults → collection profile → selected environment precedence. One global profile and one profile per collection. Environment JSON import/export without secret values. Active profile ID is remembered locally; credentials are not.
- Administrators can persist profiles: all variable values are encrypted with the existing vault key. Saved secret variables are masked in reads and resolved server-side. Blank saved-secret values retain the previous value; delete the row to remove it. Every applied profile must allow the exact destination hostname. These are shared single-workspace profiles, not per-user private vaults.
- Response status, duration, UTF-8 body size, pretty/raw/collapsible JSON tree, search highlighting, headers, copy/download, assertions and a credential-free event console.
- Declarative status, body substring, header and JSON-path assertions; sequential collection runs with iterations, stop-on-failure, cancellation and JSON result export.
- Auth credentials and recognized credential headers/parameters use placeholders in saved requests and generated code. Body text is saved as entered: use variables for sensitive fields. Inspect imported/exported documents for custom secret locations. Existing legacy records are not migrated automatically.

Collection variables from imported v2.1 collections participate as non-secret defaults. Use persisted collection profiles to manage encrypted collection-level variables. Environment/profile edits are saved separately from collection requests.

## Test generation

1. Import or save valid OpenAPI 3 YAML in API Workspace. Select that API/version in the top bar.
2. Open Test Library. The generation card shows its saved revision and path count. Unsaved editor text is not sent to generators.
3. Built-in generation creates positive/negative/boundary/security drafts, four suites and one regression plan. Repeating it for the same contract reuses those records; changed contract content generates new drafts. Existing edited cases are preserved.
4. For AI generation, configure an enabled connector in Integration Hub and opt it into test generation. Choose its protocol, endpoint, model, and response JSON pointer if needed. Select the connector in Test Library and explicitly consent to sending the selected document.
5. Review/edit the returned JSON, then save. The server validates operation/method/path matching, expected statuses, case keys and suite/plan references, and rejects stale contract revisions. Generated cases remain drafts.
6. Review payloads, configure base URL and session execution variables, then run cases, suites or plans. Execution records are created by actual runs, never by the AI.

Friday, Citi Stylus and Microsoft Copilot are not verified native integrations. Use only an organization-approved gateway/API. The configurable enterprise protocol posts `{ "prompt": "...", "stream": false }` (prompt field configurable) and accepts a JSON bundle directly or a text bundle at a configured JSON pointer. It does not reuse browser SSO or automate a chat website. Existing private-network restrictions remain in force; private enterprise hosts need a separately reviewed destination policy.

## Explicit limits

No arbitrary Postman pre-request/test JavaScript sandbox, package libraries, multipart file/binary upload, cookie jar, browser OAuth authorization-code login, client certificates, custom proxy settings, WebSocket/gRPC/MQTT clients, scheduled cloud monitors, cloud synchronization, mock servers or multi-user real-time collaboration. History reopens the method/URL; it does not retain credentials or complete request bodies. Unsaved request tabs are held in memory for the current Studio view. Cancelling the browser request cannot undo a request already processed by the destination.

## GitHub classic PAT configuration

For github.com, use base URL `https://api.github.com`, authentication `BEARER`, secret reference `github-token`, and paste the token alone in New credential. Enable the connector. Set IntelliJ `INTEGRATION_HOSTS=api.github.com` (include other approved hosts as needed). Repository is `prasad4444/api-intelligence-platform`. Classic tokens need appropriate scopes (typically `repo` for private repositories), and organization SSO authorization where required. Enterprise Server uses its own approved API URL and host policy.

## Updating locally

Pull `feat/platform-implementation`, preserve `data/` and the same `VAULT_KEY`, run `mvn clean verify`, and restart the JAR or your IntelliJ application configuration. Hard-refresh the browser. No new database migration is required because records are stored in the existing generic schema.
