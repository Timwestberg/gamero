# host-bridge: threat model

| Threat | Today | Control in the plan |
|---|---|---|
| An iframe or other origin calls native | Desktop blocks. Mobile legacy has no check. | Main frame + trusted origin on all transports, from platform facts only. |
| Script injection through native → web calls | `JsCaller` interpolates strings. | `postMessage` replies; JSON-encode any pushed value. |
| A stale page uses an old context after navigation | Desktop blocks. | Router ends the context on navigation on every platform. |
| Large payload or flood | Desktop: 4096 chars. | 64 KiB limit; single-flight `busy` for long capabilities. |
| Secrets reach the page | Desktop keeps tokens native. | Rule kept; no capability returns tokens. |
| `link.open` used for non-web schemes | n/a | HTTPS only; reject `intent:`, `file:`, `javascript:`. |

Open item: Android release stays HTTPS-only (Tim's call), so the trusted origin on release builds is always HTTPS.
