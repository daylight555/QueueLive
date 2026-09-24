# Correctness review — 23 September 2026

## Conclusion

QueueLive's core queue flow and database invariants pass the current automated tests. The implementation is a credible portfolio MVP, but passing those tests does not establish production readiness. This review found five actionable gaps in browser compatibility, retry handling, session revocation and request handling.

This was a review and blueprint task: application behavior was not changed. Diagnostic scripts were added under `scripts/review/` to retain reproducible evidence. They assert the **observed defective behavior**, so their success is not a release acceptance gate. Convert each observation into a regression test expecting the desired behavior when fixing it.

## Fresh checks actually executed

| Check | Result | Scope |
|---|---|---|
| Maven Wrapper `verify` | 19 passed, 0 failures/errors | Real PostgreSQL 17.6; Java 25.0.3 compiling for Java 21 |
| Frontend `format:check` | Passed | Existing frontend/test source |
| Frontend `build` | Passed | Strict TypeScript and Vite production bundle |
| Existing Playwright suite | 2 passed | Live backend + Vite; Chromium; 15.7 seconds |
| HTTP-origin browser probe | Defect reproduced | Real frontend on simulated non-loopback HTTP origin |
| Retry browser probe | Defect reproduced | Real frontend, intercepted network failure → 429 → retry |
| Disabled-account probe | Defect reproduced | Real HTTP login, database disable, same session GET staff returns 200 |
| Concurrent presence SQL probe | Conflict reproduced | Independent PostgreSQL transactions; SQLSTATE 40001 |

The database suite uses the disposable `postgres` database on loopback port 55432. Browser tests use the disposable `queuelive_demo` database through the local app. Docker is still unavailable: Compose/nginx, PostgreSQL 17.11 containers, the default Testcontainers path, GitHub Actions and an actual Java 21 runtime were **not** verified in this review. The prior npm-audit and load measurements were not rerun and are historical results in `testing.md`.

## Findings, ordered by priority

### 1. P1 — The documented HTTP phone demo cannot join

Location: `frontend/src/api.ts:100`; HTTP LAN instructions in README.

`retryKey()` calls `crypto.randomUUID()` without a compatibility check. The API is absent in the Chromium non-secure origin used by this probe. Loopback localhost is treated differently, so the existing local tests missed this. The actual join UI displayed `crypto.randomUUID is not a function` before sending a mutation. Staff call-next uses the same helper.

Evidence: the probe loaded the real frontend through `http://queue.test`, forwarding requests to the local server without changing browser security settings. It observed `isSecureContext=false`, `typeof crypto.randomUUID='undefined'`, and the visible error. This reproduces the relevant browser security condition of an HTTP LAN address; it is not a physical-phone test.

Fix: prefer a trusted HTTPS origin for phone demos; if HTTP LAN demos remain supported, generate UUID v4 using `crypto.getRandomValues()` when `randomUUID` is unavailable. Do not substitute Math.random. Add an explicit unsupported-browser message if neither API exists. Continue requiring HTTPS for real use.

Acceptance: both join and call-next work on the supported non-loopback HTTP demo origin; HTTPS remains tested separately.

### 2. P1 — A rejected retry can discard an unresolved operation key

Location: `frontend/src/main.tsx:125–132`, `frontend/src/api.ts:90–108`.

The UI clears the pending idempotency key for **every** 4xx response. After an ambiguous network failure, a subsequent 429, or a CSRF/session-related rejection, says nothing about whether the original attempt committed. Clearing the key means the next retry can become a different operation. The server's correctly implemented idempotency table cannot help when the client sends a new key.

Evidence: the real frontend issued keys A, A, B for an intercepted network failure, then 429, then another attempt. No ticket mutations were sent to the server by this diagnostic. It confirms key loss, not an end-to-end duplicate-ticket incident. A previous completed/otherwise released assignment is a scenario where repeating the intention under a new key can change the outcome.

Fix: model a pending action explicitly (caller scope, key, payload and resolution state). Retain its key across rate limits, temporary authentication/CSRF failures and ambiguous errors. Recover authentication/token state, honor Retry-After, and retry the same operation. Clear only when that logical operation's outcome is definitively known or the user explicitly starts a different resolved action. If the caller identity changes, require explicit reconciliation; do not silently replay under a different session owner. Provide a way to review/retry the original payload after editing a name.

Acceptance: commit-and-drop-response → 429 → recovery still returns the original result with one mutation/audit transition. Repeat for call-next, CSRF refresh, reload and changed form input.

### 3. P1 before real use — Disabling staff does not revoke existing sessions

Location: `backend/src/main/java/dev/queuelive/SecurityConfig.java:18–23` and staff route role checks.

`enabled` is checked when Spring loads the user at login. Subsequent requests trust the previously stored security context. There is no current-account check or session revocation on disable. With persistent sessions configured for seven days idle, an already signed-in disabled user can continue using protected endpoints.

Evidence: a temporary staff account logged in through the real API; after `enabled=false`, its existing cookie still received HTTP 200 from `/api/staff`. The probe removed its own staff account and sessions afterwards. It did not modify demo staff or print cookies.

Fix: add an administrative disable/password-change service that invalidates that principal's persisted sessions, and verify current account eligibility for protected staff actions. Give staff authentication a deliberate timeout policy rather than implicitly inheriting the student recovery lifetime. Clear private frontend snapshots and return to login after 401/403 account revocation.

Acceptance: disable a signed-in staff member, then test read and mutation endpoints with the old cookie. They must reject access; unrelated staff sessions must still work. Test password changes and a second browser session too.

### 4. P2 — Presence updates can abort overlapping dashboard snapshots

Location: `backend/src/main/java/dev/queuelive/QueueService.java:175–179`.

`staffSnapshot()` uses REPEATABLE READ and also updates the staff row. When two requests for the same staff member overlap, PostgreSQL can reject the second update because its snapshot predates the other commit. The error is not translated or retried by the service. Multiple dashboard tabs and simultaneous refreshes can therefore surface transient server errors.

Evidence: two independent JDBC transactions using this isolation level and update produced SQLSTATE `40001`. The second snapshot was established before the first commit, without arbitrary sleeps. This directly verifies the database failure mode; a deterministic full-HTTP regression test is still needed for the endpoint/error mapping.

Fix: move the presence heartbeat into a small READ COMMITTED transaction, then read the snapshot in a read-only transaction. Alternatively retry the entire snapshot transaction a small bounded number of times for serialization conflicts. Avoid weakening the mutation lock strategy.

Acceptance: coordinated overlapping snapshots for the same staff member return successful responses, and consistent counts/tickets are preserved during queue writes.

### 5. P2 — Compose users share one abuse-control bucket

Location: `backend/src/main/java/dev/queuelive/AbuseFilter.java:21–37`, `frontend/nginx.conf`.

The limiter keys by `getRemoteAddr()`. Behind the bundled nginx proxy, that is the proxy's address, so ordinary users share the 600/minute API bucket and 20/minute login bucket. The ten-second polling baseline alone gives 600 snapshot requests/minute for 100 open pages, before SSE reconnects, notifications or actions. This is a calculation from the implementation, **not a measured capacity figure**. One noisy client can also consume the shared allowance.

Fix: enforce per-client limits at the trusted edge, or use a strictly configured trusted-proxy chain with rate limits separated by anonymous session, staff principal and login-source IP. Do not simply trust arbitrary X-Forwarded-For. Honor Retry-After and add capped jitter/backoff in reconciliation while retaining a bounded maximum stale time.

Acceptance: requests from distinct simulated clients behind nginx do not share a per-client quota; spoofed forwarded headers do not bypass the limit; one abusive client does not block staff/student peers.

## What the existing implementation gets right

- Queue mutations acquire the same PostgreSQL row lock before selecting/assigning tickets and recording retry outcomes.
- Partial unique indexes protect one active ticket per owner and one active assignment per staff member.
- Ticket transitions check state and the assigned staff member on the server.
- Exact retry results, including empty call-next results, are persisted transactionally.
- Rollback tests check both records and after-commit notifications.
- Public data uses a separate projection; student ownership derives from the server session.
- CSRF, BCrypt and session-backed authentication are exercised by tests.
- Reconnect/polling recovery works in the existing browser suite.

## Additional risks to test, not confirmed incidents

- After-commit SSE writes occur synchronously on the publisher thread. Slow-client behavior needs a test before claiming bounded mutation latency.
- `now()` represents transaction start time. Long lock waits or overlapping state transitions deserve a lifecycle timestamp-order test; choose the intended timestamp semantics explicitly.
- Fresh sessions/bootstrap under concurrent tabs, server restart with a held ticket, and response loss after commit are not covered adequately by the two browser scenarios.
- Staff snapshots fetch the entire queue and counts scan history. Ticket, audit and operation-result retention is currently unbounded, as already documented.
- Java is organised in one flat package and frontend page components share a large `main.tsx`. These are maintainability issues, not evidence that the locking algorithm is wrong.

## Reproduce

First run the existing backend integration suite on the disposable test database and start the local demo as described in README. The backend suite resets its configured database, so never point it at data you want to retain.

```sh
TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/postgres \
TEST_DATABASE_USER=postgres TEST_DATABASE_PASSWORD='' \
MAVEN_USER_HOME=/tmp/queuelive-maven backend/mvnw -f backend/pom.xml \
-B -ntp -Dmaven.repo.local=/tmp/queuelive-m2 verify

cd frontend
npm run format:check
npm run build
BASE_URL=http://127.0.0.1:5173 DEMO_STAFF_PASSWORD='your-local-demo-password' npm run test:e2e
cd ..
BASE_URL=http://127.0.0.1:5173 node scripts/review/browser-probes.cjs
java --class-path /path/to/postgresql-42.7.11.jar scripts/review/DatabaseProbes.java
```

Browser probes require Playwright Chromium installed and the demo queue open; the HTTP-origin forwarding uses the Vite development server. Set PLAYWRIGHT_BROWSERS_PATH if using a nonstandard cache. DatabaseProbes intentionally targets the disposable local test databases on port 55432 with the existing test fixture and app on 8080; read its source before running. Its test account credentials come from the integration fixture. It is not a production diagnostic tool.
