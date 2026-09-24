export type State =
  | "WAITING"
  | "CALLED"
  | "IN_SERVICE"
  | "COMPLETED"
  | "CANCELLED"
  | "NO_SHOW";
export type Ticket = {
  id: string;
  number: string;
  displayName: string | null;
  state: State;
  assignedTo: string | null;
  createdAt: string;
};
export type Counts = {
  waiting: number;
  active: number;
  completed: number;
  noShow: number;
};
export type Student = {
  open: boolean;
  ticket: Ticket | null;
  peopleAhead: number;
  approximateMinutes: number | null;
  estimateNote: string;
};
export type Staff = {
  open: boolean;
  counts: Counts;
  waiting: Ticket[];
  active: Ticket[];
  username: string;
};
export type Public = {
  open: boolean;
  counts: Counts;
  called: { number: string; state: State; counter: string }[];
  joinUrl: string;
};
export type Session = { csrfToken: string; staff: string };
let csrfToken = "";
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch("/api" + path, {
    credentials: "same-origin",
    ...options,
    headers: {
      ...(options.body instanceof URLSearchParams
        ? {}
        : options.body
          ? { "Content-Type": "application/json" }
          : {}),
      ...(options.method && options.method !== "GET"
        ? { "X-CSRF-TOKEN": csrfToken }
        : {}),
      ...options.headers,
    },
    signal: options.signal ?? AbortSignal.timeout(10000),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiError(
      error.message || `Request failed (${response.status})`,
      response.status,
    );
  }
  return response.status === 204 ||
    response.headers.get("content-length") === "0"
    ? (undefined as T)
    : await response.text().then((s) => (s ? JSON.parse(s) : undefined));
}
export async function session() {
  const s = await request<Session>("/session");
  csrfToken = s.csrfToken;
  return s;
}
// Persist unfinished actions across refresh. Retry the SAME action/key after an ambiguous network failure.
export function retryKey(operation: string, payload: string) {
  const stored = sessionStorage.getItem("pending:" + operation);
  if (stored) {
    const previous = JSON.parse(stored) as { key: string; payload: string };
    if (previous.payload !== payload)
      throw new Error(
        "Retry your previous action with the same input first, or refresh to review its result.",
      );
    return previous.key;
  }
  const key = crypto.randomUUID();
  sessionStorage.setItem(
    "pending:" + operation,
    JSON.stringify({ key, payload }),
  );
  return key;
}
export function clearKey(operation: string) {
  sessionStorage.removeItem("pending:" + operation);
}
export const labels: Record<State, string> = {
  WAITING: "Waiting",
  CALLED: "You’re up",
  IN_SERVICE: "In service",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
  NO_SHOW: "Missed call",
};
