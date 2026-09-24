# A 90-second demo

Prepare a clean disposable database, explicitly configure the demo password, start Compose, and arrange four separate browser contexts (two staff and two students) plus the public board. Sign in staff `alex` and `sam`. Different tabs in one profile are not separate students. Have the board open and the queue closed initially.

| Time | Show and say |
|---|---|
| 0–12s | “QueueLive helps students get lab help without standing in line.” Show the public board and QR. Alex opens the queue. |
| 12–28s | Student one joins as Ada. Student two joins as Lin. Show different human-readable ticket numbers and Lin's people-ahead count. “Names are visible only to staff.” |
| 28–40s | Refresh Ada's page. The same ticket returns. “A server-issued session owns the ticket; the database keeps the state.” |
| 40–55s | Put Ada's browser offline using devtools. Alex and Sam each click Call next, close together. Show distinct assignments. “The database lock prevents duplicate assignment, even across concurrent requests.” |
| 55–68s | Restore Ada's connection. Her page recovers CALLED from a new snapshot without rejoining. Show the board has numbers/desks, not names. “SSE is a hint; reconnect and polling fetch the truth.” |
| 68–82s | Alex starts and completes service. Sam marks the other student no-show. Show the completed/no-show counters. |
| 82–90s | Close the queue. Show that new joins are disabled. “A small monolith, real transactions, retry-safe actions and automated concurrency tests.” |

Do not promise a numerical wait estimate in a fresh demo: at least three recent completed services and active staff are required. Explain the honest “not enough history” message instead. Do not present this script as user feedback or a recorded successful demo unless actually recorded.
