# Unified workspace verification

Validated 2026-11-03 with Java 17 and the project Node/Vite toolchain.

| Check | Result |
| --- | --- |
| Frontend unit/component suite | 57 passed |
| TypeScript and Vite production build | Passed |
| Maven verify, backend tests | 62 passed; no failures, errors or skips |
| JaCoCo line coverage | 87.93% (1588 of 1806 lines) |
| Playwright browser suite | 10 passed |

The browser suite exercises upload and linked artifacts, endpoint request/response details, review/approve/draft transitions, complete-file comparison, confirmed collection deletion and retained unsaved tabs, request tabs, environments, connector configuration and optional Windows authentication. Backend tests cover stale revisions, workflow roles, idempotent preparation, source preservation and changed-contract artifact isolation. Component tests cover collection identity, moves, deletion, and saving into a different collection without sharing request identity.

Tests use synthetic contracts and local test services. They do not verify live internal Friday.ai/Stylus, Microsoft Copilot, Jira, GitHub or enterprise Windows authentication infrastructure. Generated cases and examples require review before execution against real services. See UNIFIED_WORKSPACE.md for behavior and remaining feature limits.
