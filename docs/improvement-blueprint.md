# QueueLive improvement blueprint

This plan follows the fresh test run and concrete findings in [review.md](review.md). Keep Java 21, Spring Boot, React, PostgreSQL, HTTP and SSE. The current database locking strategy is appropriate for one lab; there is no evidence justifying microservices, Kafka or Redis.

## Milestone 1 — Make browser actions dependable

**Work**
- Fix UUID generation for the supported phone-demo origin, or make trusted HTTPS the documented prerequisite and show a useful browser error.
- Extract `useAction` and pending-operation storage from `main.tsx`. Give an unresolved action an explicit caller/key/payload lifecycle.
- Preserve keys through ambiguous errors, 429 and CSRF recovery. Honor Retry-After; add “retry original action” and a clear resolution path when input changes.
- On authentication loss, clear staff-only data and navigate to login. Avoid showing an old private snapshot indefinitely.

**Done when**
- Browser tests reproduce lost responses after successful database commit for both join and call-next, then retry through 429 and reload without a second operation.
- Join and call-next are tested on the documented phone origin, not only localhost.
- Changing the display name during an unresolved join has an understandable recovery path.

**Why first:** these fixes affect the first student interaction and the reliability promise exposed to users. Preserve server idempotency; repair the client protocol around it.

## Milestone 2 — Harden staff access and request handling

**Work**
- Add staff disable/password-change operations with session revocation and current-account eligibility checks.
- Separate staff authentication lifetime from anonymous ticket-recovery lifetime. Document what expiry means for an assigned ticket.
- Separate presence writes from consistent read snapshots, or add bounded serialization retries.
- Fix proxy-aware abuse controls at the trusted edge. Use separate policies for login, mutations and snapshots; do not accept spoofable client-IP headers.
- Add lock/query timeouts and structured error mapping so contention causes a recoverable response rather than an unbounded wait. Keep the same idempotency key after a timeout.

**Done when**
- Disabled/password-changed staff are rejected across all existing browser sessions, with no effect on unrelated users.
- Coordinated simultaneous requests for the same dashboard succeed without SQLSTATE 40001 reaching clients.
- Through the real nginx proxy, one noisy client cannot exhaust every user's quota.

## Milestone 3 — Close the environment and failure-testing gaps

**Work**
- Run the existing CI workflow on Java 21, PostgreSQL 17.11 and the actual Compose/nginx images once publishing to GitHub is authorised. Local Docker can verify this sooner without a push.
- Add health/readiness checks for backend and frontend; make startup wait on readiness rather than container creation alone.
- Add deterministic browser fixtures: independent tests should not depend on a previous scenario leaving the queue clean.
- Add full HTTP concurrency tests, backend restart/session recovery, bootstrap across tabs, slow SSE clients, lifecycle timestamp ordering and database disconnect/recovery.
- Test response loss after commit, not only offline-before-request and blocked SSE. Add keyboard and small-screen checks to the routine browser suite.
- Pin release image digests, wrapper checksums and CI action revisions; document a scheduled dependency-update process rather than leaving pins indefinitely.

**Done when**
- A fresh clone and configured `.env` start the full demo reproducibly.
- Java 21 + Testcontainers + nginx browser checks pass, with artifacts and exact versions recorded.
- Failure-path tests demonstrate a current authoritative state after reconnect/restart and no duplicate business operation.

## Milestone 4 — Make the code easier to explain and extend

Suggested organisation within the same deployable application:

```text
backend/src/main/java/dev/queuelive/
  queue/       # queue commands, ticket state, snapshots, SQL repository
  identity/    # anonymous ownership, staff accounts and session policy
  live/        # committed-change notifications and SSE connections
  shared/      # small error/configuration helpers only
frontend/src/
  pages/       # StudentPage, StaffPage, DisplayPage, LoginPage
  components/  # StatusBadge, TicketCard, QueueTable, ConnectionStatus
  api/         # typed requests, auth/token handling, API models
  hooks/       # useLiveSnapshot, useQueueAction
```

**Work**
- Separate controllers, command service, snapshot queries and persistence mapping without moving transaction ownership into controllers.
- Centralise legal ticket transitions in an explicit, readable state-machine function or enum.
- Format Java consistently and replace dense multi-statement lines. Keep comments focused on locking and recovery decisions.
- Extract React pages and reusable components. Prefer descriptive variables over `d` and `t` in longer render functions.
- Keep error responses and DTOs consistent; add an OpenAPI contract or contract tests once endpoints stabilise.

**Done when**
- All existing correctness tests still pass with no API behavior changes.
- A junior developer can trace a join from page → request → controller → transaction → SQL and explain it using the interview guide.

## Milestone 5 — Prepare for a real lab

**Work**
- Define and implement retention covering terminal tickets, names, audit actors, retry payload/results, sessions and backups. Specify the retry horizon before deleting operation records.
- Add an audited recovery policy for an abandoned assignment or inaccessible anonymous ticket; ordinary staff must not silently bypass assignee rules.
- Add minimal metrics: failed mutations, lock waits, query duration, active SSE connections, reconciliation failures and stale clients. Keep personal data out of metrics/logs.
- Add pagination/limits and use EXPLAIN on growing ticket-history queries before adding indexes or summary tables.
- Run a sustained workload with active staff, many SSE viewers, polling, joins and completion—not only a short join/leave loop. Measure latency to full response, error rates, DB contention and resource usage.
- Demonstrate backup restore and document secure same-origin HTTPS deployment.

**Done when**
- Retention and backup restore are tested on disposable data.
- Capacity claims include actual hardware, versions, duration, workload and observed failures.
- There is a documented operational response to stale assignments, account revocation and database loss.

## Boundaries to preserve

Do not loosen owner/assignee checks, remove database constraints, rely on frontend button disabling, or replace PostgreSQL locking with Java locks. Do not add multiple queues or external identity integrations until these milestones justify that scope. Keep the public board free of student names and private ticket credentials. Continue describing estimates as approximate and SSE as best-effort notifications repaired by snapshots.

The first three milestones address correctness and verification; the last two improve maintainability and operations. None requires a public deployment or a GitHub push to begin.
