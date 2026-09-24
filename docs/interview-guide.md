# Interview guide

## Walk me through a join request

The browser first receives an HTTP-only session cookie and a CSRF token. It sends the optional name plus an idempotency key. Spring checks CSRF, the controller gets the owner from the server session, and the transactional service locks the singleton queue row. It checks for an existing operation and active ticket, verifies the queue is open, allocates the next number, inserts the ticket and audit event, and stores the exact result. Commit happens before SSE notification. The browser fetches a fresh snapshot.

## Why these tables?

Tickets hold lifecycle state and UTC instants. Staff hold BCrypt hashes. A queue row holds configuration and numbering. Partial unique indexes express “one active” rules without preventing historical tickets. Operation results make retries durable. Audit events explain changes. Spring Session stores security and anonymous identity separately from domain rows, allowing browser refresh and server restart recovery.

## What stops two staff calling the same student?

Every mutation runs in a Spring-managed PostgreSQL transaction and first acquires `SELECT ... FOR UPDATE` on the same queue row. The other transaction waits, then sees the first commit before selecting a waiting ticket. FIFO uses number plus UUID as a deterministic tie-breaker. No Java lock or disabled button is relied on. The concurrency tests run independent worker transactions with a barrier, so both requests compete rather than executing sequentially.

## Idempotency versus a unique constraint

A unique index prevents invalid database state, but cannot alone tell a timed-out caller what happened. The operation table stores a result keyed by caller, operation and UUID. The queue lock covers the lookup, action and insertion. The original result survives restarts and is returned on retry. Different payloads return a conflict. A call on an empty queue is stored too, so retrying it after new students arrive still returns empty. Errors roll back. Keys currently do not expire; retention requires an explicit future retry horizon.

## SSE, polling, or WebSockets?

SSE is a browser-friendly one-way stream, matching this app's server-to-client notifications. HTTP requests perform actions with normal CSRF/auth checks. Polling is simpler and robust but adds reads and up to ten seconds of delay. We combine SSE speed with bounded polling recovery. WebSockets would make sense for frequent bidirectional communication but add lifecycle/protocol complexity here. The stream does not guarantee exactly-once delivery, replay events or replace the database.

## Authentication versus authorisation

Authentication answers who the staff member is, using a password hash and server session. Authorisation checks whether that role may call the endpoint and whether that staff member owns this assignment. Students have anonymous server sessions rather than accounts; the server derives their ticket owner. A public ticket number is a display label, never a credential. CSRF prevents another origin from issuing authenticated mutations using the victim's cookies. SameSite and Secure flags reinforce cookie safety but do not replace CSRF.

## Trade-offs I actually made

- One queue-row lock is simple to prove correct, but serialises every write. Fine for a lab; measure contention before improving concurrency.
- JDBC exposes the exact locking SQL and avoids ORM surprises, but manual mapping and queries require discipline.
- Snapshots and public invalidation hints simplify data privacy and reconnects, but produce extra reads and do not preserve every intermediate state.
- JDBC sessions make restarts recoverable, but add writes and retain server-side session data.

These are implemented decisions, not claims of optimisations that have not been measured.

## Where does scaling stop?

SSE connections and rate limits live in one JVM; broadcasts do not fan out across instances. A crash between commit and notification is recovered by polling. One queue-row lock limits write throughput. Staff snapshots return the entire waiting queue and counters scan accumulated tickets. Unbounded history needs retention/index/query improvements. Session traffic, BCrypt cost, and synchronous emitter sends also consume resources. Add metrics and run the provided workload before quoting capacity.

## What would you improve next?

1. Run the Docker/Java 21 CI path and extend failure/restart tests; collect actual latency and errors under a documented workload.
2. Implement staff lifecycle management and retention that includes idempotency/audit/session data.
3. Add operational health/metrics, pagination, and trusted-edge rate limiting; only introduce multi-instance event delivery after measurements justify it.

## Explain the estimate honestly

It uses recent mean service durations and recently active staff dashboards, not a scheduling guarantee. It requires three completed samples, considers people ahead and active work, and suppresses the number with no recent staff. Large variance and unattended dashboards make it approximate. The UI should say that plainly.
