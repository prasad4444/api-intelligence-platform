# Observability and monitoring

Connect Splunk and company tools → compare operational data over time → schedule
reports → receive deduplicated, actionable alerts → investigate via the unified
AI chat → draft a Jira incident through the reviewed-write pipeline → keep
searchable knowledge and AI chat history.

## Splunk prerequisites

- The Splunk **management port** (default `8089`) must be reachable from this
  application. For Splunk Cloud use `https://<stack>.splunkcloud.com:8089`;
  REST API access must be enabled and, on Splunk Cloud, your source IP must be
  allowlisted via the Admin Config Service (the `search-api` app/IP allowlist).
- The connecting role must allow **search on the approved indexes** and REST
  access (`rest_properties_get`/`rest_apps_view` as applicable). Token auth
  requires Splunk authentication tokens enabled on the platform
  (`authorize.conf` / token management).
- Supported authentication:
  - `SPLUNK_TOKEN` — `Authorization: Bearer <authentication token>`
    (Enterprise and Cloud).
  - `BASIC` — username/password (Enterprise only; not available on Cloud).
  - `SESSION` — `POST /services/auth/login` → session key →
    `Authorization: Splunk <key>`; the session key is cached and re-logged-in
    once on 401 via the single-flight `TokenRefresher`.
- Configure `defaultTimezone` (IANA), the app namespace
  (`servicesNS/{owner|-}/{app}`), `approvedIndexes`, field mappings
  (`timestamp:_time`, `service`, `environment`, `severity`, `httpStatus`,
  `durationMs`, `transactionType`, `correlationId`, `exception`) and limits
  (`maxWindowHours`, `maxConcurrentJobs` default 2, `maxResults` default 50k,
  `pageSize` 1000, `maxJobSeconds` 300).
- `webUrl` is configured separately from the management URL and is used only
  for drill-down links into Splunk Web.

## v1 vs v2 search endpoint selection

On connection validation the platform reads `GET /services/server/info` and
stores the detected `apiVersion` on the connection. Enterprise ≥ 9.0.1 (or
Splunk Cloud ≥ 9.0.2208) uses the v2 search endpoints
(`/services/search/v2/jobs`, `/services/search/v2/jobs/{sid}/results`,
`.../control` for cancel) with an automatic fallback to v1 on 404. Older
versions use v1 (`{ns}/search/jobs`). v1 export is never used. This follows
Splunk's "Search endpoint descriptions / semantic API versioning" guidance on
help.splunk.com.

## Comparison semantics

- Windows are **half-open** `[earliest, latest)`; `latest_time` is exclusive.
- `ROLLING` mode uses instant arithmetic; `CALENDAR` mode uses local
  `ZonedDateTime` arithmetic in the report's zone — week-ago windows follow
  local clock time and can be 23/25 hours across DST transitions (overlap picks
  the earlier offset; gaps shift forward and are recorded in window `notes`).
- `lag` (default 2m) delays evaluation so late-arriving events are counted;
  with `completedBucketsOnly` (default) the current window is truncated to the
  last complete bucket and the same offset applies to baselines.
- **Missing ≠ zero**: a failed/unavailable/never-reporting search yields
  metric status `MISSING` (or `PARTIAL`); a measured count of 0 yields
  `MEASURED` 0. `relativeChange` is `null` when the baseline is zero or missing
  (with reason `baseline zero` / `baseline missing`); `ppChange` is used for
  rates and `ratePerMinute` normalizes unequal durations.
- Percentiles are computed from raw samples per window/bucket — precomputed
  percentiles are never averaged.

## Scheduler operating boundary

The scheduler is in-process, single-JAR, with SQLite-backed leases
(`schedules`, `schedule_runs`, unique `execution_key = scheduleId|plannedAt`).
Multiple app instances sharing one DB file are dedup-safe via
`INSERT OR IGNORE` + atomic lease claim; **SQLite on network shares is
unsupported**. Runs execute only while the app is running; on restart the
missed-run policy (`SKIP` | `RUN_ONCE` | `RUN_ALL_CAPPED`) decides catch-up.
Failures retry with exponential backoff up to `retry_limit` then `DEAD`; each
run has a `deadline_s`. Disable entirely with
`platform.scheduler.enabled=false`. A 401/403 at execution marks the
connection `RECONNECT_REQUIRED`, fails the run with
`CREDENTIALS_EXPIRED`/`ACCESS_REVOKED` and notifies the owner once (deduped)
without pausing the schedule.

## Notifications

Destinations: `IN_APP`, `EMAIL`, `SLACK`, `TEAMS`, `WEBHOOK` — all created
**disabled**; enabling an external destination requires payload **preview**,
recipient authorization (email recipients must be platform users or match
`platform.notify.allowed-email-domains`; webhook hosts are approved at
enable-time only), then an explicit `enable` confirmation. Webhook calls send
an `Idempotency-Key` header. Delivery uses a transactional outbox with bounded
retries + backoff → `DEAD`. Email requires `spring.mail.host` (PowerShell:
`$env:SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD`); without it `EMAIL` destinations report unavailable.

## Retention controls

- `platform.retention.raw-events-days` (default 7) — stored representative
  events / snapshot raw samples.
- `platform.retention.snapshots-days` (default 90) — report snapshots.
- A periodic purge job deletes expired rows. Cost limits
  (`platform.features.cost-controls`) allow per-user/per-connection caps on
  window size, concurrent jobs, result volume, retention days and AI context
  bytes, enforced server-side under **Monitoring → Ops → Cost limits**.

## Feature flags

`platform.features.service-dashboard`, `platform.features.playbooks`,
`platform.features.correlation-search`, `platform.features.template-catalog`,
`platform.features.cost-controls` — all default `true` in `application.yml`.

## Browser dependencies

Connection Studio recording/validation needs Chromium once:

```powershell
mvn -q dependency:get -Dartifact=com.microsoft.playwright:playwright:<version>
java -cp "$env:USERPROFILE\.m2\repository\com\microsoft\playwright\playwright\<version>\playwright-<version>.jar;$env:USERPROFILE\.m2\repository\com\microsoft\playwright\driver\*\*" com.microsoft.playwright.CLI install chromium
```

(Use the exact `com.microsoft.playwright.CLI install chromium` invocation from
README; the jar is produced by `mvn verify`.)

## Build and run (Windows PowerShell)

```powershell
git clone <repo>; cd api-intelligence-platform
git checkout feat/observability-monitoring
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:VAULT_KEY = [Convert]::ToBase64String((1..32 | %{Get-Random -Max 256}))
[Environment]::SetEnvironmentVariable('VAULT_KEY', $env:VAULT_KEY, 'User')
$env:ADMIN_PASSWORD = 'choose-a-long-unique-password'
# Optional:
# $env:DATABASE_URL = 'jdbc:sqlite:./data/platform.sqlite'
# $env:PUBLIC_URL = 'http://localhost:8080'
# $env:SPRING_MAIL_HOST = 'smtp.example.com'
mvn clean verify
java -jar target\api-intelligence-platform-1.0.0-SNAPSHOT.jar
```

Sign in at `http://localhost:8080` as `admin`, then add Splunk under
**Connection Studio → Add app → Provider: Splunk** and build reports under
**Monitoring**.

## Provider limitations

- Splunk Cloud requires REST access + IP allowlisting; BASIC auth is
  Enterprise-only; very old Enterprise (<9.0.1) uses v1 search only.
- Slack/Teams use incoming-webhook URLs (stored in the vault, never returned
  by the API). Email requires SMTP configuration.
- Scheduled runs only occur while the application is running.
- Generated connector code is for review/PR only — never loaded at runtime.

## Fixture-verified vs live-verified

| Capability | Status |
|---|---|
| Splunk (token/basic/session, v1+v2 endpoints, jobs, pagination, quota/permission errors) | Fixture-verified only |
| Jira (search, issue draft via reviewed write) | Fixture-verified only |
| AI chat providers (investigation, report summary) | Fixture-verified only |
| Slack / Teams / webhook notifications | Fixture-verified only |
| Email notifications | Fixture-verified only (requires `spring.mail.host`) |
| Scheduler, alerts, outbox, knowledge/FTS, retention | Integration-tested in-repo |

All tests use fixture HTTP servers (WireMock/embedded); no paid production
accounts have been exercised.
