import { useEffect, useRef, useState } from "react";
import { request } from "./api";

export function useLive<T>(path: string, enabled = true) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [connection, setConnection] = useState("Connecting");
  const reload = useRef<() => Promise<void>>(async () => {});
  useEffect(() => {
    if (!enabled) return;
    let stopped = false,
      inFlight = false,
      again = false,
      lastSuccess = 0,
      sseOpen = false;
    const controller = new AbortController();
    async function refresh() {
      if (inFlight) {
        again = true;
        return;
      }
      inFlight = true;
      try {
        const snapshot = await request<T>(path, {
          signal: AbortSignal.any([
            controller.signal,
            AbortSignal.timeout(10000),
          ]),
        });
        if (!stopped) {
          setData(snapshot);
          setError("");
          lastSuccess = Date.now();
          setConnection(sseOpen ? "Connected" : "Reconnecting · polling");
        }
      } catch (e) {
        if (!stopped) {
          setError(e instanceof Error ? e.message : "Unable to refresh");
          if (Date.now() - lastSuccess > 30000)
            setConnection("Stale / offline");
        }
      } finally {
        inFlight = false;
        if (again && !stopped) {
          again = false;
          void refresh();
        }
      }
    }
    reload.current = refresh;
    void refresh();
    const events = new EventSource("/api/events");
    events.onopen = () => {
      sseOpen = true;
      void refresh();
    };
    events.addEventListener("changed", () => void refresh());
    events.onerror = () => {
      sseOpen = false;
      if (!stopped)
        setConnection(
          Date.now() - lastSuccess > 30000
            ? "Stale / offline"
            : "Reconnecting · polling",
        );
    };
    // Bounded reconciliation even when an SSE notification was lost.
    const interval = window.setInterval(() => {
      if (Date.now() - lastSuccess > 30000) setConnection("Stale / offline");
      void refresh();
    }, 10000);
    const wake = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    window.addEventListener("online", refresh);
    document.addEventListener("visibilitychange", wake);
    return () => {
      stopped = true;
      controller.abort();
      events.close();
      clearInterval(interval);
      window.removeEventListener("online", refresh);
      document.removeEventListener("visibilitychange", wake);
    };
  }, [path, enabled]);
  return { data, error, connection, refresh: () => reload.current() };
}
