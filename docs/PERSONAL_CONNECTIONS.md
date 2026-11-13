# Personal connections, discovery and replay

Build and run with **JDK 17** (`java.version` / `maven.compiler.release` are pinned to 17). A newer JDK can compile the project, but the supported runtime for local Windows installs is Java 17.

## Individual platform accounts

Platform users are stored in SQLite (`platform_users`). Bootstrap accounts (`admin`, optional `developer` / `reviewer`) are created or refreshed from `ADMIN_PASSWORD`, `DEVELOPER_PASSWORD` and `REVIEWER_PASSWORD` at startup. Admins provision additional users under **Admin → Platform accounts**.

Login remains form-based at `/api/login`. Provider credentials are never shared across users.

## Shared definitions vs personal instances

| Kind | Owner | Purpose |
| --- | --- | --- |
| `integrations` | Admin-managed definitions | Approved hosts, AI templates, role policy |
| `connections` | The signed-in person | That person's Jira / GitHub / Copilot / Devin / Stylus / custom account |

States: **NOT_CONNECTED**, **CONNECTED**, **RECONNECT_REQUIRED**, **ERROR**. Disconnect deletes scoped secrets and cancels pending approvals bound to that connection.

## Scoped vault

Legacy global secrets remain in `secrets` (AES-GCM, AAD = secret name). Personal credentials, browser sessions and capture bodies use `scoped_secrets` with AAD `ownerId|connectionId|name`. Keep `VAULT_KEY` stable across restarts.

## Browser discovery (Playwright)

1. Install Chromium once:  
   `mvn -q exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`
2. From Integration Hub / Jira / GitHub / AI Tools, connect your account, then run **Discover**.
3. Headless discovery is for approved public pages. **Interactive sign-in** launches a headed Chromium window for local Windows SSO/MFA pairing. Complete sign-in in the provider page; the app stores encrypted session cookies for replay.
4. CLI equivalent: `mvn -q exec:java -Dexec.mainClass=com.apiintel.integrations.DiscoveryCommand -Dexec.args="https://approved-host.example [--interactive]"`

Pairing boundary: interactive discovery is for the local install where the user can see and control the browser. The Spring Boot JAR remains the application entry point; Chromium is an explicit install dependency only for discovery.

## Direct HTTP replay

Saved captures replay through Java `HttpClient` without starting Chromium. Cookies respect domain/path/secure. On session expiry the service attempts one coordinated refresh (OAuth refresh token or saved session) and returns **Reconnect required** when interactive sign-in is needed. Non-idempotent methods require explicit confirmation. Destinations are constrained by `INTEGRATION_HOSTS` / `INTEGRATION_PRIVATE_HOSTS` and the connection site host.

## AI Tools

Personal connections appear alongside company definitions. Preview/confirm still binds user, connection revision and payload. Scopes include OpenAPI context plus **replay**, **jira** and **github** content treated as untrusted data.
