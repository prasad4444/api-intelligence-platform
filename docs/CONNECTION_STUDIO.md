# Connection Studio and unified AI chat

One workspace for connecting your own apps (Jira, GitHub, Microsoft 365 Copilot, GitHub Models, Devin, Stylus, custom web apps) and chatting with them in a single owner-scoped conversation UI. Open **Connection Studio** from the left navigation to add or pair apps, then use **AI Tools → Chat** to talk to any validated provider.

## Why a separate Chromium window

Providers block framing — `X-Frame-Options` and CSP `frame-ancestors` — and browsers isolate cross-origin sessions, so the provider page cannot be embedded in this app. Instead, the local Spring Boot process launches a **controlled headed Chromium window via Playwright** using a persistent per-user, per-connection profile under `data\browser-profiles\{ownerId}\{connectionId}`. Connection Studio shows the session's live status over SSE (`/api/studio/sessions/{id}/events` plus `/api/studio/timeline`). When you confirm sign-in, the browser's storage state is encrypted into the vault. Normal chat traffic afterwards uses **direct HTTP replay** — the browser-backed adapter is used only when validation detects signed or device-bound requests that plain HTTP cannot reproduce.

## Connect Jira

- **Jira Cloud — OAuth 2.0 (3LO):** register an OAuth app at https://developer.atlassian.com/console/myapps with callback `http://localhost:8080/api/oauth/callback` and scopes `read:jira-work write:jira-work read:jira-user` plus `offline_access`. Then **Connection Studio → Add app → Jira Cloud → OAuth 2.0 (3LO)**, enter the client id/secret, **Connect**, sign in, and select a Jira site. API traffic routes through `api.atlassian.com/ex/jira/{cloudId}/rest/api/3`.
- **Jira Cloud — email + API token:** create a token at https://id.atlassian.com/manage-profile/security/api-tokens and use the **email + API token** method; requests go to `{site}/rest/api/3`.
- **Jira Data Center:** choose **Jira Data Center** with a PAT; requests go to `{site}/rest/api/2`.

Issue create/update are proposed into the Approval queue bound to the connection and its revision, then approved and executed once — same as other writes.

## Pair an AI application

**Connection Studio → Add app → URL → Open browser.** Sign in (MFA included) in the separate Chromium window → **I've signed in** → enter a demo prompt → **Start recording** → send that prompt in the provider page → **Stop recording** → **Infer connector** → review the inferred request template, pointers, format and replay mode → **Validate & activate**. The provider then appears in **AI Tools → Chat** with a `browserBacked` capability flag when required.

CLI equivalent — drives the same studio recorder path:

```powershell
mvn -q exec:java "-Dexec.mainClass=com.apiintel.integrations.DiscoveryCommand" "-Dexec.args=https://host --interactive --user alice --connection <id>"
```

## Capability matrix

| Provider | Connection method | Chat / task operations | Session persistence | Refresh | External prerequisite |
|---|---|---|---|---|---|
| Microsoft 365 Copilot | Entra ID OAuth (auth code + PKCE, delegated) → Graph beta `/copilot/conversations` ; or browser pairing | Multi-turn chat, SSE streaming (`chatOverStream`) | Refresh token in vault; conversation id per chat | OAuth refresh, single-flight; else Reconnect | M365 Copilot add-on license; Entra app registration with delegated Sites.Read.All, Mail.Read, People.Read.All, OnlineMeetingTranscript.Read.All, Chat.Read, ChannelMessage.Read.All, ExternalItem.Read.All (admin consent typically); work/school accounts only; beta API |
| GitHub Copilot | GitHub Models API (`models.github.ai/inference/chat/completions`) with PAT | Chat completions, SSE streaming, multi-turn | PAT in vault | None (re-enter PAT) | Fine-grained PAT needs `models:read`; free tier rate-limited, paid usage opt-in. A Copilot subscription itself has no public chat API |
| Devin | Devin API v1 (`api.devin.ai/v1/sessions`) with personal/service API key | Task mode: session create, follow-up messages, polled status/progress, session link | Session id per conversation | None (re-enter key) | Devin plan with API access; usage consumes ACUs billed to the org |
| Stylus / company AI gateways | Configured endpoint (JSON template, JSON/SSE mapping, multipart) or browser pairing | Chat; streaming when SSE | Credential or encrypted browser session | OAuth client refresh or one session refresh; else Reconnect | Endpoint host approval (auto on save); company auth method |
| Custom web apps (paired) | Connection Studio recording → inferred connector | Chat via HTTP replay; browser-backed adapter when required; multi-step adapter | Encrypted storage state + profile | One coordinated refresh; MFA/CAPTCHA → User action required | User must be able to sign in interactively; site terms permitting automation |
| Jira Cloud | OAuth 2.0 (3LO) or email + API token | JQL search (`/rest/api/3/search/jql`), issue details, context attach, approved create/update | Rotating refresh token in vault | Single-flight refresh, persist rotated token | Atlassian developer console OAuth app (for 3LO) |
| Jira Data Center | PAT / existing auth | Search (`/rest/api/2/search`), issue, approved writes | Credential in vault | n/a | Network reachability, host approval |
| GitHub | PAT | Repo browsing, file context, approved writes | PAT in vault | n/a | PAT scopes for target repos |

## Verification status

Fixture-verified by automated tests:

- `UnifiedJourneyE2ETest` — file-backed SQLite, real Flyway, two-context restart, full journey (provisioning, pairing via fake browser launcher, chat SSE, replay, ownership isolation, secret redaction).
- `UnifiedChatStudioTest` — adapters, approvals, OAuth refresh, replay, ownership.
- `MigrationV4Test` — V1–V3 data survives the V4 migration on file-backed SQLite.
- Vitest — 68 frontend tests (SSE parser, chat streaming/stop, transfer modal, provider chips, studio tabs/timeline, Jira picker paging).
- Playwright e2e — 15 tests against the built JAR.

Not live-verified against real providers in this change: Microsoft Graph Copilot, GitHub Models, Devin API, Atlassian OAuth, Stylus — adapters follow official docs and are tested against local fixtures.
