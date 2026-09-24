# Testing and verification

## Automated checks

The backend suite ran against PostgreSQL 17.6 here and uses `postgres:17.11-alpine3.24` through Testcontainers by default. H2 is never used. `cd backend && ./mvnw verify` starts a disposable PostgreSQL container when Docker is available. With a separately provisioned **disposable** PostgreSQL database, you can set `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD` instead. The test fixture truncates queue-related tables before each test and must never point at valuable data.

The integration suite covers join → call → start → complete; cancellation and no-show; queue closure; duplicate joins; same-key retry including empty call; incompatible retry payload; invalid transitions; assigned-staff ownership; one active assignment per staff; database unique constraints; simultaneous staff calls and same-key operations from independently transacted threads released by a barrier; rollback and after-commit notification; session ownership, CSRF, login/logout, staff restrictions and public privacy; wait-estimate preconditions and calculation. Unit coverage also checks numbering past three digits.

`cd frontend && npm ci && npm run format:check && npm run build && npm audit --audit-level=high` checks the frontend. With the full application and explicitly configured demo staff running, `DEMO_STAFF_PASSWORD='your-value' npm run test:e2e` executes two Chromium scenarios. They cover the two-student/two-staff flow, live updates, closed queue, leave, refresh, network reconnect, public privacy and recovery by polling when SSE is blocked. The browser tests need a clean disposable queue; they modify queue and staff state. Use separate browser contexts for students and staff.

The GitHub Actions workflow is configured to run Java 21 + Testcontainers tests and Docker Compose + Chromium tests on each push or PR. It uses disposable CI credentials. GitHub Actions results must be checked separately after a push; a local run does not verify the hosted workflow.

## Checks actually executed in this workspace

- Maven Wrapper `verify`: **19 tests passed**, 0 failures, 0 errors, against temporary PostgreSQL 17.6 on loopback. The host Java was 25.0.3; compilation targeted Java 21 bytecode.
- Flyway V1 applied to a separate fresh PostgreSQL demo database; the backend started and served live requests.
- `npm run format:check`, `npm run build`: **passed** with Node 24.13.1 and Vite 7.3.6.
- `npm audit --audit-level=high`: **passed**, 0 reported vulnerabilities in this local npm dependency tree at the time checked.
- Playwright Chromium: **2 tests passed**, 0 failed, against the local backend and Vite frontend. The successful run took 16.9 seconds; this is a test duration, not a product latency measure.
- Chromium captures of student desktop/mobile, staff desktop and public display: rendered without horizontal overflow. The student phone and staff desktop captures are included in `docs/screenshots/`; the other inspection captures stayed under `/tmp`.
- September 24 recheck: Maven Wrapper `verify` **passed all 19 tests**, 0 failures and 0 errors, using Testcontainers' default Docker path with PostgreSQL 17.11. The host JVM was Java 25.0.3; the container image uses Java 21.
- September 24 recheck: `npm ci`, `npm run format:check`, `npm run build`, and `npm audit --audit-level=high` **passed**; npm audit reported 0 vulnerabilities at the time checked.
- September 24 recheck: an isolated Compose project built and started the PostgreSQL 17.11, Java 21 backend and nginx/frontend containers. `/api/public` responded through nginx. Both Playwright Chromium tests **passed** against this stack (16.9 seconds total). The first browser attempt could not locate Chromium in Playwright's default cache; rerunning with the preinstalled `/tmp/queuelive-browsers` succeeded. The 16.9 seconds is test duration, not application latency.
- GitHub Actions workflow: **provided but not observed yet**.

Earlier test runs found and corrected two session-test fixture mistakes and one premature browser assertion. The final results above are from reruns after those fixes.

## Load test

`scripts/load.mjs` creates a new anonymous session for each round, joins with a UUID key, retries with the same key and checks that the ticket ID is identical, fetches a snapshot, then leaves. Defaults: four concurrent workers × ten rounds. All traffic targets `BASE_URL`, default `http://localhost:8080`. It is deliberately small, uses no paid service, and **modifies the queue**. Run only on a disposable, open queue:

```sh
BASE_URL=http://localhost:8080 CONCURRENCY=4 ROUNDS=10 node scripts/load.mjs
```

One local run was measured on a MacBook Pro with Apple M1 Pro (8 cores) and 16 GB memory, using Node 24.13.1, Vite's development proxy, Java 25.0.3, and temporary PostgreSQL 17.6. It completed 40 scenarios (200 HTTP requests) with **0 scenario errors**. Measured client-side request latencies: p50 **9.78 ms**, p95 **30.63 ms**, p99 **80.70 ms**; total run **0.69 s**. This was one short local run with no warm-up and a development proxy, so it is **not** a capacity claim or production benchmark. Use repeated runs, a production container build, system/resource metrics, a longer workload and recorded error rates before making performance claims.
