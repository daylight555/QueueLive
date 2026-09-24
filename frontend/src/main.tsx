import {
  StrictMode,
  useEffect,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import { createRoot } from "react-dom/client";
import { QRCodeSVG } from "qrcode.react";
import {
  ApiError,
  clearKey,
  labels,
  request,
  retryKey,
  session,
  type Counts,
  type Public,
  type Session,
  type Staff,
  type Student,
} from "./api";
import { useLive } from "./useLive";
import "./style.css";

function Shell({
  children,
  connection,
}: {
  children: ReactNode;
  connection?: string;
}) {
  const path = window.location.pathname;
  return (
    <>
      <header>
        <a className="brand" href="/">
          <span className="brand-icon">Q</span>QueueLive
          <span className="lab-label"> / CODING LAB</span>
        </a>
        <nav aria-label="Main navigation">
          <a aria-current={path === "/" ? "page" : undefined} href="/">
            Get help
          </a>
          <a
            aria-current={path === "/display" ? "page" : undefined}
            href="/display"
          >
            Live board
          </a>
          <a
            aria-current={path === "/staff" ? "page" : undefined}
            href="/staff"
          >
            Staff ↗
          </a>
        </nav>
      </header>
      <main>{children}</main>
      <footer>
        <span>Less time in line. More time learning.</span>
        {connection && (
          <span role="status" className="connection">
            <i className={connection === "Connected" ? "online" : ""} />
            {connection}
          </span>
        )}
        <span>QueueLive · University help desk</span>
      </footer>
    </>
  );
}
function ErrorNote({ message }: { message: string }) {
  return message ? (
    <div className="error" role="alert">
      {message}
    </div>
  ) : null;
}
function Status({ open }: { open: boolean }) {
  return (
    <span className={"badge " + (open ? "green" : "")}>
      {open ? "● Queue open" : "○ Queue closed"}
    </span>
  );
}
function Stats({ counts }: { counts: Counts }) {
  return (
    <div className="stats">
      {(
        [
          ["Waiting", counts.waiting],
          ["Being helped", counts.active],
          ["Completed", counts.completed],
          ["No-show", counts.noShow],
        ] as const
      ).map(([label, n]) => (
        <div key={label}>
          <span>{label}</span>
          <strong>{n}</strong>
        </div>
      ))}
    </div>
  );
}
function useAction(refresh: () => Promise<void>) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  async function act(path: string, body?: unknown, operation?: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const payload = JSON.stringify(body ?? {});
      const key = operation ? retryKey(operation, payload) : undefined;
      const result = await request<{ message?: string } | undefined>(path, {
        method: "POST",
        body: body === undefined ? undefined : payload,
        headers: key ? { "Idempotency-Key": key } : {},
      });
      if (operation) clearKey(operation);
      setNotice(result?.message ?? "Queue updated");
      await refresh();
    } catch (e) {
      if (
        operation &&
        e instanceof ApiError &&
        e.status >= 400 &&
        e.status < 500
      )
        clearKey(operation);
      setError(
        e instanceof Error
          ? e.message
          : "Action failed. Retry to recover its result.",
      );
      await refresh();
    } finally {
      setBusy(false);
    }
  }
  return { busy, error, notice, act };
}
function StudentPage() {
  const live = useLive<Student>("/student");
  const action = useAction(live.refresh);
  const [name, setName] = useState(
    () => sessionStorage.getItem("join-name") ?? "",
  );
  const d = live.data,
    t = d?.ticket;
  const active = t && ["WAITING", "CALLED", "IN_SERVICE"].includes(t.state);
  return (
    <Shell connection={live.connection}>
      <section className="intro">
        <div className="eyebrow">A LITTLE HELP. A BIG STEP FORWARD.</div>
        <h1>
          Great questions
          <br />
          start here<span>.</span>
        </h1>
        <p>
          Stuck on your code? Save your place, keep working,
          <br className="desktop" /> and we’ll let you know when it’s your turn.
        </p>
      </section>
      <div className="student-grid">
        <section className="card primary-card">
          <div className="card-heading">
            <h2>
              {active ? "Your place in the lab" : "Let’s get you unstuck"}
            </h2>
            {d && <Status open={d.open} />}
          </div>
          <ErrorNote message={live.error || action.error} />
          {!d ? (
            <p role="status">Loading the help desk…</p>
          ) : active ? (
            <>
              <div className={"ticket-panel " + t.state.toLowerCase()}>
                <span className="eyebrow">YOUR TICKET</span>
                <div className="ticket-number">{t.number}</div>
                <span className="badge green">{labels[t.state]}</span>
                <p>
                  {t.state === "WAITING"
                    ? "You’re in. Keep this page handy."
                    : t.state === "CALLED"
                      ? `Please head to Desk ${t.assignedTo}.`
                      : "You’re with your teaching assistant. Happy debugging!"}
                </p>
              </div>
              {t.state === "WAITING" && (
                <>
                  <div className="ticket-metrics">
                    <div>
                      <strong>{d.peopleAhead}</strong>
                      <span>people ahead</span>
                    </div>
                    <div>
                      <strong>
                        {d.approximateMinutes === null
                          ? "—"
                          : `~${d.approximateMinutes} min`}
                      </strong>
                      <span>approximate wait</span>
                    </div>
                  </div>
                  <p className="muted small">{d.estimateNote}</p>
                  <button
                    className="secondary full"
                    disabled={action.busy}
                    onClick={() => void action.act("/student/leave")}
                  >
                    Leave queue
                  </button>
                </>
              )}
              <p className="small muted">
                Joined{" "}
                {new Date(t.createdAt).toLocaleTimeString([], {
                  hour: "2-digit",
                  minute: "2-digit",
                })}{" "}
                · Your ticket stays here when you refresh.
              </p>
            </>
          ) : (
            <>
              {t && (
                <div className="notice">
                  {t.number} · {labels[t.state]}.{" "}
                  {t.state === "COMPLETED"
                    ? "Nice work! You’re welcome back anytime."
                    : "You can join again when the queue is open."}
                </div>
              )}
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void action.act(
                    "/student/join",
                    { displayName: name },
                    "join",
                  );
                }}
              >
                <label htmlFor="display-name">
                  What should we call you?{" "}
                  <span className="muted">(optional)</span>
                </label>
                <input
                  id="display-name"
                  maxLength={40}
                  value={name}
                  onChange={(e) => {
                    setName(e.target.value);
                    sessionStorage.setItem("join-name", e.target.value);
                  }}
                  placeholder="Your first name"
                  autoComplete="given-name"
                />
                <p className="field-help">
                  Only staff can see your name. The live board shows ticket
                  numbers.
                </p>
                <button className="full" disabled={!d.open || action.busy}>
                  {action.busy
                    ? "Saving your place…"
                    : d.open
                      ? "Join the queue  →"
                      : "The queue is currently closed"}
                </button>
              </form>
              <div className="privacy">
                <span>↳</span>
                <p>
                  No account needed. Your place is saved in this browser.
                  <br />
                  One active ticket per browser session.
                </p>
              </div>
            </>
          )}
          {action.notice && (
            <p role="status" className="small">
              {action.notice}
            </p>
          )}
        </section>
        <aside className="how">
          <span className="eyebrow">HOW IT WORKS</span>
          <h2>
            A calmer way
            <br />
            to ask for help.
          </h2>
          {[
            [
              "01",
              "Save your place",
              "Join once. No account, no standing in line.",
            ],
            [
              "02",
              "Keep making progress",
              "Your position updates here automatically.",
            ],
            ["03", "Come on over", "When your number is called, meet your TA."],
          ].map(([n, h, p]) => (
            <div className="step" key={n}>
              <span>{n}</span>
              <div>
                <h3>{h}</h3>
                <p>{p}</p>
              </div>
            </div>
          ))}
          <div className="tip">
            <span>GOOD TO KNOW</span>
            <p>
              Have your code and question ready. A small, clear example goes a
              long way.
            </p>
          </div>
        </aside>
      </div>
    </Shell>
  );
}
function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const data = new FormData(e.currentTarget);
    try {
      await session();
      await request("/login", {
        method: "POST",
        body: new URLSearchParams({
          username: String(data.get("username")),
          password: String(data.get("password")),
        }),
      });
      onLogin(await session());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Login failed");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Shell>
      <section className="login card">
        <div className="eyebrow">STAFF SPACE</div>
        <h1>Welcome back.</h1>
        <p className="muted">Sign in to help the next student move forward.</p>
        <ErrorNote message={error} />
        <form onSubmit={submit}>
          <label htmlFor="username">Username</label>
          <input
            id="username"
            name="username"
            required
            maxLength={50}
            autoComplete="username"
          />
          <label htmlFor="password">Password</label>
          <input
            id="password"
            name="password"
            type="password"
            required
            maxLength={72}
            autoComplete="current-password"
          />
          <button className="full" disabled={busy}>
            {busy ? "Signing in…" : "Sign in →"}
          </button>
        </form>
      </section>
    </Shell>
  );
}
function StaffPage({ onLogout }: { onLogout: () => void }) {
  const live = useLive<Staff>("/staff");
  const action = useAction(live.refresh);
  const d = live.data;
  const mine = d?.active.find((t) => t.assignedTo === d.username);
  async function logout() {
    try {
      await request("/logout", { method: "POST" });
      await session();
      onLogout();
    } catch {
      await live.refresh();
    }
  }
  return (
    <Shell connection={live.connection}>
      <div className="dashboard-title">
        <div>
          <div className="eyebrow">STAFF WORKSPACE</div>
          <h1>Make room for progress.</h1>
          <p className="muted">
            One question at a time. {d && `Signed in as ${d.username}.`}
          </p>
        </div>
        <button className="secondary" onClick={() => void logout()}>
          Sign out
        </button>
      </div>
      <ErrorNote message={live.error || action.error} />
      {!d ? (
        <p>Loading workspace…</p>
      ) : (
        <>
          <Stats counts={d.counts} />
          <div className="queue-toolbar">
            <div>
              <Status open={d.open} />
              <span className="muted small">
                {" "}
                {d.open
                  ? "Students can join the help desk."
                  : "Existing tickets can still be served."}
              </span>
            </div>
            <button
              className="secondary"
              disabled={action.busy}
              onClick={() => void action.act("/staff/queue", { open: !d.open })}
            >
              {d.open ? "Close queue" : "Open queue"}
            </button>
          </div>
          <div className="staff-grid">
            <section className="card">
              <div className="card-heading">
                <h2>
                  Waiting room <span className="count">{d.waiting.length}</span>
                </h2>
                <span className="muted small">First come, first served</span>
              </div>
              {d.waiting.length ? (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>Ticket</th>
                        <th>Student</th>
                        <th>Joined</th>
                        <th>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {d.waiting.map((t) => (
                        <tr key={t.id}>
                          <td className="mono">{t.number}</td>
                          <td>{t.displayName || "Student"}</td>
                          <td>
                            {new Date(t.createdAt).toLocaleTimeString([], {
                              hour: "2-digit",
                              minute: "2-digit",
                            })}
                          </td>
                          <td>
                            <span className="badge">Waiting</span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="empty">
                  <span>✓</span>
                  <h3>A little breathing room.</h3>
                  <p>New students will appear here when they join.</p>
                </div>
              )}
            </section>
            <aside>
              <section className="card assignment">
                <div className="eyebrow">YOUR DESK</div>
                {mine ? (
                  <>
                    <h2 className="assignment-number">{mine.number}</h2>
                    <p>
                      {mine.displayName || "Student"} · {labels[mine.state]}
                    </p>
                    {mine.state === "CALLED" ? (
                      <>
                        <button
                          className="full"
                          disabled={action.busy}
                          onClick={() =>
                            void action.act(
                              `/staff/tickets/${mine.id}/transition`,
                              { state: "IN_SERVICE" },
                            )
                          }
                        >
                          Start service
                        </button>
                        <button
                          className="secondary full"
                          disabled={action.busy}
                          onClick={() =>
                            void action.act(
                              `/staff/tickets/${mine.id}/transition`,
                              { state: "NO_SHOW" },
                            )
                          }
                        >
                          Mark no-show
                        </button>
                      </>
                    ) : (
                      <button
                        className="full"
                        disabled={action.busy}
                        onClick={() =>
                          void action.act(
                            `/staff/tickets/${mine.id}/transition`,
                            { state: "COMPLETED" },
                          )
                        }
                      >
                        Complete service ✓
                      </button>
                    )}
                  </>
                ) : (
                  <>
                    <h2>Ready when you are.</h2>
                    <p className="muted">Call the next student to your desk.</p>
                    <button
                      className="full"
                      disabled={action.busy}
                      onClick={() =>
                        void action.act(
                          "/staff/call-next",
                          undefined,
                          `call-next:${d.username}`,
                        )
                      }
                    >
                      Call next student →
                    </button>
                  </>
                )}
                {action.notice && (
                  <p role="status" className="small">
                    {action.notice}
                  </p>
                )}
              </section>
              <section className="card other-desks">
                <h2>Across the lab</h2>
                {d.active
                  .filter((t) => t.assignedTo !== d.username)
                  .map((t) => (
                    <div className="desk-row" key={t.id}>
                      <strong>{t.number}</strong>
                      <span>
                        Desk {t.assignedTo}
                        <br />
                        <small>{labels[t.state]}</small>
                      </span>
                    </div>
                  ))}
                {!d.active.some((t) => t.assignedTo !== d.username) && (
                  <p className="muted">No other active assignments.</p>
                )}
              </section>
            </aside>
          </div>
        </>
      )}
    </Shell>
  );
}
function DisplayPage() {
  const live = useLive<Public>("/public");
  const d = live.data;
  return (
    <Shell connection={live.connection}>
      <div className="dashboard-title">
        <div>
          <div className="eyebrow">CODING LAB · LIVE BOARD</div>
          <h1>A helping hand is near.</h1>
          <p className="muted">
            Listen for your number. Keep your next idea moving.
          </p>
        </div>
        {d && <Status open={d.open} />}
      </div>
      <ErrorNote message={live.error} />
      {!d ? (
        <p>Loading live board…</p>
      ) : (
        <>
          <Stats counts={d.counts} />
          <div className="display-grid">
            <section className="card">
              <h2>At the help desk</h2>
              {d.called.length ? (
                d.called.map((t) => (
                  <div className="called-row" key={t.number}>
                    <strong>{t.number}</strong>
                    <div>
                      <h3>{t.counter}</h3>
                      <span className="badge green">
                        {t.state === "CALLED" ? "Now calling" : "In service"}
                      </span>
                    </div>
                  </div>
                ))
              ) : (
                <div className="empty">
                  <span>↗</span>
                  <h3>Your next breakthrough starts here.</h3>
                  <p>Called ticket numbers will appear on this board.</p>
                </div>
              )}
            </section>
            <aside className="card qr-card">
              <span className="eyebrow">NEED A HAND?</span>
              <h2>Scan. Join. Keep coding.</h2>
              <div className="qr">
                <QRCodeSVG
                  value={d.joinUrl}
                  size={190}
                  level="M"
                  title="Scan to join the help queue"
                />
              </div>
              <p>
                No account needed.
                <br />
                Just a question worth asking.
              </p>
              <a href={d.joinUrl}>Open the join page ↗</a>
            </aside>
          </div>
        </>
      )}
    </Shell>
  );
}
function App() {
  const [identity, setIdentity] = useState<Session | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    void session()
      .then(setIdentity)
      .catch((e) => setError(e.message));
  }, []);
  if (error)
    return (
      <Shell>
        <div className="card">
          <ErrorNote message={error} />
          <button onClick={() => location.reload()}>Reconnect</button>
        </div>
      </Shell>
    );
  if (!identity)
    return (
      <Shell>
        <p role="status">Connecting to the help desk…</p>
      </Shell>
    );
  if (location.pathname === "/display") return <DisplayPage />;
  if (location.pathname === "/staff")
    return identity.staff ? (
      <StaffPage onLogout={() => setIdentity({ ...identity, staff: "" })} />
    ) : (
      <Login onLogin={setIdentity} />
    );
  return <StudentPage />;
}
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
