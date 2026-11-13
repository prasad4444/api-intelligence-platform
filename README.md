# API Intelligence Platform

An engineering workspace for API contracts, requests, reusable tests, delivery planning and AI integrations. A Java 17 / Spring Boot 3 monolith serves a React + TypeScript + Tailwind UI from one executable jar. Flyway manages an embedded SQLite database by default.

## Run locally as one Spring Boot application

The production build runs the backend and React UI together from **one executable Spring Boot JAR**. You do not need a separate frontend server, Node process or external database at runtime.

For the initial build, install **Java 17**, **Maven 3.9+**, **Node 22+**, and npm. Extract the source ZIP and open a terminal inside the `api-intelligence-platform` folder. On Windows, use **PowerShell** (below) or Git Bash.

### Windows PowerShell quick start

Requires **JDK 17** (not JRE 8). Point `JAVA_HOME` at a JDK 17 install before Maven:

```powershell
git clone https://github.com/prasad4444/api-intelligence-platform.git
cd api-intelligence-platform
git checkout feat/personal-connections-discovery

# Java 17 only — example Temurin path; adjust if yours differs
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
java -version   # should report 17.x

# Generate VAULT_KEY once and reuse it (do not regenerate on each start)
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:VAULT_KEY = [Convert]::ToBase64String($bytes)
$env:ADMIN_PASSWORD = 'choose-a-long-unique-password'
$env:DEVELOPER_PASSWORD = 'choose-a-different-password'
$env:REVIEWER_PASSWORD = 'choose-another-password'
# INTEGRATION_HOSTS is optional. Saving/connecting a connector auto-approves its host.

mvn clean verify
mvn -q exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
java -jar target/api-intelligence-platform-1.0.0-SNAPSHOT.jar
```

Open http://localhost:8080 and sign in as `admin`. Provision individual users under **Admin**, then each person connects their own Jira/GitHub/AI accounts. SQLite persists at `./data/platform.sqlite`. Keep the same `VAULT_KEY` so encrypted credentials remain readable. See [Personal connections, discovery and replay](docs/PERSONAL_CONNECTIONS.md).

### Git Bash / macOS / Linux

Generate a vault encryption key **once**:

```bash
openssl rand -base64 32
```

Save that output securely. Paste the same saved key into `VAULT_KEY` below; do not generate a new key on each startup.

```bash
export ADMIN_PASSWORD='choose-a-long-unique-password'
export VAULT_KEY='paste-your-saved-base64-32-byte-key-here'
# Optional separate accounts:
export DEVELOPER_PASSWORD='choose-a-different-password'
export REVIEWER_PASSWORD='choose-another-password'
mvn clean verify
mvn -q exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
java -jar target/api-intelligence-platform-1.0.0-SNAPSHOT.jar
```

Open http://localhost:8080 and sign in as `admin`. Optional accounts are `developer` and `reviewer`. There is no checked-in default password. Keep `VAULT_KEY` stable between restarts so saved credentials remain decryptable. The app binds to localhost by default and seeds a generic Commerce API, team, sprint, stories, collection, diagram and disabled connector catalog.

The admin password is the value you assigned to `ADMIN_PASSWORD`. SQLite creates `data/platform.sqlite` in the working directory of the JAR. Existing SQLite files are upgraded with Flyway forward migrations (`V3__personal_connections.sql`). The application does not migrate existing H2 records into SQLite.

### Start again after building

Only Java is required to run an already-built JAR. Set your password and **the same vault key** in each new terminal, then start the application:

```bash
export ADMIN_PASSWORD='choose-a-long-unique-password'
export VAULT_KEY='paste-the-same-saved-key-here'
java -jar target/api-intelligence-platform-1.0.0-SNAPSHOT.jar
```

Stop it with **Ctrl+C**. Rebuild with `mvn clean verify` after changing source code. Use a consistent working directory to keep using the same `data/` database. Maven and Node/npm are build tools; they do not need to keep running after the JAR starts.

`mvn verify` installs/builds the UI, runs backend tests and enforces **80% backend line coverage**. For frontend tests:

```bash
cd frontend
npm ci
npm test
npx playwright install --with-deps chromium
npm run e2e
```

Browser tests start the packaged jar using an isolated in-memory database. Evidence is written to `frontend/test-results/screenshots` and `frontend/playwright-report`; CI uploads it with coverage reports and the jar.

The separate Vite server is optional and is only for frontend development: run the backend and `cd frontend && npm run dev`. Vite proxies `/api` to the backend. Normal local use requires only the packaged Spring Boot JAR described above.

## Unified contract workflow

One YAML/JSON upload now prepares the API endpoint browser, a linked Request Studio collection, and built-in test cases, suites and a plan. Review actions use saved revisions and role checks. Compare complete files in a colored side-by-side dialog, and rename, duplicate and delete collections, and move folders/requests within a collection. See [Unified workspace workflow](docs/UNIFIED_WORKSPACE.md).

## Request Studio update

The Postman-style workbench now includes request tabs, nested collections, parameter/header tables, auth/body editors, assertions, a collection runner, response inspection, and encrypted variable profiles. Test Library generates linked cases, suites and plans from the selected saved OpenAPI contract, with an optional reviewed AI-gateway flow. See [Request Studio features, configuration and limits](docs/REQUEST_STUDIO.md).

## Workspace

| Module | Available workflows |
| --- | --- |
| Dashboard | Contract, test, execution and integration KPIs; sprint delivery pulse |
| API Workspace | OpenAPI 3 JSON/YAML import, Swagger-style operation explorer, guarded Try it out, lint, full-file comparison, review/approval, collection generation |
| Request Studio | HTTP methods, headers, JSON/XML/text/form bodies, ephemeral auth, environments, nested collections, v2.1 import/export, runner, history, curl snippet |
| Test Library | Generated editable positive/negative/boundary/security drafts based on schema examples and bounded values; manual cases, search, type filter, clone, revisions, restore, selected-case and suite/plan runs, recorded results; JSON/CSV/Excel/Postman/JUnit/Cucumber exports |
| GitHub | PAT via central vault, contents and PR browser; separately approved branch, file commit and PR actions; Jira key links |
| Jira | Live JQL connector, local sprint board, bulk local status updates, validated CSV import/export, local stories from failed executions |
| Team Capacity | Dev/Test and USA/India availability, dates, allocation, leave, holidays, configurable hours/point, commitment matrix, heatmap, CSV and styled XLSX template/import/export |
| Governance | Configurable lint rules, contract scores, actionable findings and persisted score trends |
| Architecture | Live Mermaid preview, API-derived diagrams, saved sources, Mermaid and draw.io import/export with basic flowchart shapes and preserved source |
| AI Copilot | OpenAI-compatible, Azure, Ollama and Anthropic adapters; assistants; explicit rule-based fallback; Friday space listing, SSE chat and approved streaming workflow signals |
| Dev Utilities | JSON/YAML/XML formatting, text/JSON diff, Base64, JWT decode, SHA-256, regex, AES-GCM with PBKDF2 |
| Integration Hub | Provider catalog, custom HTTP connectors, encrypted credential rotation, Bearer/Basic/API-key/OAuth2 client-credentials auth, per-connector roles, single-use action approvals |
| Admin | Workspace role matrix, operational settings and audit trail |

Use **Ctrl+K / Cmd+K** to jump between modules. Theme preference persists locally. API/version selection scopes workspace operations and test generation.

## Central integrations and credentials

Configure tools under **Integration Hub → Manage connection**. Credentials are encrypted in the vault and referenced by name; the application never offers an endpoint to retrieve their plaintext. For Basic authentication store `username:password`; for Bearer/PAT/API-key authentication store the token itself. Supplying a new credential rotates it.

Destination hosts are approved automatically when you **save a connector** or **connect a personal account** (from that connector’s `baseUrl`). `INTEGRATION_HOSTS` / `INTEGRATION_PRIVATE_HOSTS` are optional bootstrap lists only.

```bash
# Optional bootstrap (usually unnecessary):
# export INTEGRATION_HOSTS='api.github.com'
```

Generic Request Studio calls still block private destinations. Connector traffic to a private host is allowed after that host is saved/connected (or listed in `INTEGRATION_PRIVATE_HOSTS`). The Friday daemon continues to use the exact server-configured `FRIDAY_URL`. The server checks DNS answers and the connected peer, refuses redirect following, caps buffered responses at 2 MB and applies timeouts.

**Writes:** propose the exact method/path/body → inspect it in Approval queue → reviewer approves → authorized operator executes once. A changed connector revision invalidates its earlier approvals. Failure consumes the approval to avoid accidental duplicate writes; inspect provider state before proposing a retry. AI chat requests can incur provider charges and are initiated directly by the user.

See [integration recipes](docs/INTEGRATIONS.md) for GitHub, Devin, Jira, Friday and custom tools.

## Security and operations

- Local accounts use BCrypt; workspace RBAC is enforced on the backend. Accounts/passwords are deployment configuration, not a multi-tenant identity directory.
- Session authentication uses HttpOnly/SameSite=Strict cookies and CSRF-protected writes. No credentials are persisted in browser localStorage.
- All connector use is checked against its `allowedRoles`; administrators have an override. Vault and connector changes are admin-only. Reviewers can approve actions.
- Vault values use AES-256-GCM, fresh 96-bit IVs and secret-name authenticated data. The master key is external configuration, never saved in the database.
- H2 console is disabled. API Workspace renders uploaded contracts with the platform's own Swagger-style explorer. The platform's OpenAPI JSON remains available at `/v3/api-docs` after sign-in.
- Database defaults to `./data/platform.sqlite`. Stop the application cleanly before copying the database, and preserve its vault key. Existing H2 installations can explicitly set `DATABASE_URL=jdbc:h2:file:./data/platform;DB_CLOSE_ON_EXIT=FALSE` to reopen their old workspace.
- Admin **Request and integration usage** stores redacted request outcomes and offers request-ID search. Correlation IDs in browser responses match the IntelliJ console. See [401 and 403 diagnostics](docs/INTEGRATIONS.md#diagnosing-http-401-and-403).
- When exposing beyond localhost, terminate HTTPS at a trusted reverse proxy and enable secure cookies with `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`. Use a managed secret store to inject deployment credentials.
- Direct Request Studio credentials are ephemeral. Saved variable profiles encrypt their values in the vault; secret values are masked and resolved by the backend. Use Integration Hub for durable connector credentials. Avoid putting secrets in URL query strings, request bodies, test fixtures or versioned JSON records.

## Docker

```bash
export ADMIN_PASSWORD='choose-a-long-unique-password'
export VAULT_KEY="$(openssl rand -base64 32)"
docker compose up --build
```

The image runs as a non-root user and stores data in a named volume. `localhost` inside Docker refers to the container; set `FRIDAY_URL` to the daemon address reachable from that container. Do not expose the daemon publicly.

## Scope and extension points

This is a runnable single-team product implementation, not a feature-complete replacement for the named vendor products. Generated tests need human review and operation-specific examples; Cucumber exports require step definitions and JUnit exports require a RestAssured test project. Collection execution supports declarative HTTP requests, not arbitrary Postman JavaScript sandboxes. The draw.io adapter preserves Mermaid source, exports basic flowchart shapes and imports native nodes/edges; complex styling is not preserved. Interactive OAuth authorization-code/PKCE, SSO provisioning, Zephyr-specific APIs, arbitrary Jira CSV field mappings are future extensions; explicit API actions are supported through the connector console. Friday signals can be queued from spec approvals, PR creation, failed individual tests and an explicit over-capacity check; queued actions still require approval. External services require your credentials and have not been exercised against paid production accounts.

Package boundaries: `workspace`, `tests`, `capacity`, `integrations`, `security`, `network`, `store`, and `api`. Records use optimistic revisions and retain history. Extend connectors with provider adapters or configured JSON actions; extend deployment identity independently of stored provider credentials.

See the [verification report and page screenshots](docs/VERIFICATION.md).
