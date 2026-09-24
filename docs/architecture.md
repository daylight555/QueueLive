# Architecture

QueueLive is a small modular monolith: HTTP controllers, the queue domain service, authentication configuration, and live-update delivery are separate classes within one application. They share one PostgreSQL database and one deployment. The flat Java package keeps this first portfolio version easy to navigate; a larger application should split queue, identity, and notifications into packages with enforced boundaries.

## Request flow

1. React calls `GET /api/session` to establish a server-issued JDBC-backed session and obtain a CSRF token. The HTTP-only session cookie is never read by JavaScript.
2. A student submits a join with an operation UUID. The server derives ownership from its session attribute; there is no student-ID request field.
3. The Spring transaction locks `help_queue(id=1) FOR UPDATE` before reading idempotency records or changing tickets.
4. A successful mutation changes the ticket, writes an audit row, stores its retry result if relevant, and commits atomically.
5. A transactional listener publishes a generic SSE refresh hint after commit. Browsers fetch their own permitted snapshot.

## Tables and invariants

| Table | Purpose |
|---|---|
| `help_queue` | Singleton open/closed flag and next ticket number |
| `ticket` | Owner, optional name, state, assignment, UTC timestamps |
| `staff` | BCrypt password hash, enabled flag, dashboard heartbeat |
| `operation_result` | Caller + operation + key, normalised payload and original JSON result |
| `audit_event` | Actor, ticket, previous/new state and timestamp; also queue toggles |
| `spring_session*` | Persisted server sessions, including CSRF and security context |

Partial unique indexes enforce one active ticket per owner per queue and one active assignment per staff member. The database constrains state names, assignment shape, terminal timestamps and ticket-number uniqueness. The service enforces legal transitions and assigned-staff ownership; constraints are the second line of defence, not the entire state machine.

`WAITING → CALLED → IN_SERVICE → COMPLETED`, `WAITING → CANCELLED`, `CALLED → NO_SHOW`. Queue closure blocks new joins but permits existing tickets to finish. A student with an existing ticket gets it back even if the queue subsequently closes.

## Ordering and transactions

Numbers are allocated from the locked queue row, are never reset during normal operation, and are formatted `Q-001`, `Q-002`, … `Q-1000`. Number allocation and insert roll back together. FIFO is `ORDER BY number, id`; UUID is an explicit deterministic tie-breaker although the number is unique. A transaction's successful acquisition of the queue lock determines join order, rather than potentially skewed browser clocks.

All mutation paths acquire the same lock first, including leave, transitions and queue toggles. This serialises writes and avoids deadlocks caused by inconsistent lock ordering. Concurrent call-next requests cannot both select the same waiting ticket. PostgreSQL's default READ COMMITTED level is used for writes. Read snapshots use REPEATABLE READ to avoid mixing queue and ticket facts from different commits. Staff snapshot also records last-seen activity.

## Idempotency

`(caller, operation, key)` is a primary key. Caller is either the server-derived student owner or authenticated staff username. The queue lock serialises requests before the retry lookup. An identical retry returns the stored result, including an empty call-next result, even if that ticket later changes. The caller then fetches the current snapshot. The same key with a different normalised name returns 409. Failed requests roll back and are not saved as successful outcomes. Whitespace around names is normalised before comparison.

This design deliberately stores response JSON so it can return the original result. That JSON contains private ticket data and needs the same retention/protection as ticket rows. Outcomes currently remain indefinitely: changing to expiring keys requires a documented retry horizon.

## Live updates and recovery

The public SSE endpoint emits `ready`, `changed`, and `heartbeat`, with only `refresh` as data. It never sends a ticket or user identifier. Connections expire after two minutes, heartbeat every 20 seconds, and are removed on timeout/error/completion. Maximum 300 concurrent emitters per process. Clients clean up EventSource, timers and pending fetches on unmount.

A connection opening triggers a new snapshot. Every ten seconds, a snapshot fetch reconciles state even without a notification. Requests time out after ten seconds. A snapshot older than 30 seconds is labelled stale/offline. An SSE failure with fresh polling data is labelled reconnecting/polling. Event IDs, replay and exactly-once delivery are not promised. A process crash between database commit and notification is harmless after reconciliation.

## Wait estimate

Use the mean of the last 20 completed `finished_at - started_at` durations from seven days, requiring at least three observations. Staff with dashboard activity within 90 seconds count as capacity. Estimated minutes = ceiling(mean minutes × (waiting ahead + active assignments) / capacity), clamped to 1–1440. This approximates remaining work, not an appointment time; active work is counted as a full service duration. With no recent staff or insufficient history, return no number and an explanatory message. Durations exclude time spent called but not yet in service. Outliers, breaks and difficult questions reduce accuracy.

Timestamps use PostgreSQL `TIMESTAMPTZ` and Java `Instant`; JSON uses UTC instants, and browsers render local time.
