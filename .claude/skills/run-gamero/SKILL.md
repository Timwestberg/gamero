---
name: run-gamero
description: Build, launch, stop and drive the Gamero desktop catalog app (Hovel companions: chat, Claude/Codex providers, teams, working members) on macOS. Use when asked to run, start, launch, restart or stop Gamero or Hovel, check whether it is running, read its logs, or screenshot its catalog UI.
---

Gamero's catalog app is a JCEF window around the game-platform catalog page, with native capabilities (chat, providers, workspaces, pet, embedded Godot game) behind the host bridge. Drive it with `.claude/skills/run-gamero/driver.sh`: `start` builds, launches and waits until it is ready; `page` drives the same catalog page in a headless Chromium and screenshots it; `stop` ends everything.

All paths are relative to the gamero checkout. It expects sibling checkouts beside it: `../game-platform` and `../server-platform`.

## Prerequisites (macOS only)

```bash
.claude/skills/run-gamero/driver.sh preflight
```

Every line must say `ok`; each `MISS` names its fix. It checks: JDK 21, a JetBrains Runtime with JCEF (`~/Library/Java/JavaVirtualMachines/jbrsdk_jcef-*`, or `GAMERO_BROWSER_JAVA_HOME`), the sibling checkouts, game-platform `node_modules`, the server-platform contracts `dist`, Godot 4.8-beta1 at `~/Applications/godot-4.8-beta1` (embedded game), Godot 4.7 at `/Applications/Godot.app` (desktop pet), voice files (optional) and the Claude Code login (optional).

After pulling new contracts, refresh the copy game-platform installs (it is a `file:` dependency, copied at install time):

```bash
(cd ../server-platform/event-contracts && pnpm build) && (cd ../game-platform && pnpm install)
```

## Run (agent path)

```bash
.claude/skills/run-gamero/driver.sh start
.claude/skills/run-gamero/driver.sh status
.claude/skills/run-gamero/driver.sh page build/run-shots/catalog.png
.claude/skills/run-gamero/driver.sh page build/run-shots/providers.png Profile Providers
.claude/skills/run-gamero/driver.sh logs 40
.claude/skills/run-gamero/driver.sh stop
```

- `start` runs `Play From Catalog.command` in the background (log `build/run-logs/catalog.log`) and returns when the catalog answers 200 and the app process exists. A warm start takes ~5 s; a cold one builds the embed library and packages the app first (minutes, 10-minute limit).
- `page <out.png> [text ...]` opens `http://127.0.0.1:8133/`, clicks each text in order (button, link or tab first, then any text), screenshots, and prints `{errors, heading, title, url}`. Read the PNG.
- `stop` ends the app and the catalog server and waits until the port is free.

The native window itself (approval dialogs, start-of-work confirmation, native picker, chat backed by the host) can only be inspected with the user's screen-access grant (computer-use `app_screenshot` on bundle `com.gamero.adventures.prototype`). Ask; don't fall back to `screencapture`.

## Run (human path)

```bash
GAMERO_BROWSER_JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jbrsdk_jcef-21.0.9/Contents/Home" ./'Play From Catalog.command'
```

The window opens; quitting the app (or Ctrl-C) stops the catalog server too.

## Gotchas

- The headless `page` view has no native host: desktop features read "Not available" ("Open Hovel on your Mac to connect…"), and only the Codex card shows, because `providers.list` comes from the native app. Use it for catalog/UI smoke, not for host-backed flows.
- `GAMERO_BROWSER_JAVA_HOME` must be a JBR with JCEF, not the JDK 21 used for building; `scripts/dev-env.sh` sets everything else (including `HOVEL_CLAUDE_PROVIDER=1`).
- Voice files are gitignored per checkout (`spikes/ember-voice/mac/deps`, `models`). A new checkout or worktree has none until `scripts/fetch-voice.sh` runs (or you copy them from another checkout); the launcher then warns and spoken replies are off.
- `pnpm install` in game-platform re-resolves `pnpm-lock.yaml` locally (a `tailwind-merge` peer entry). It is harmless; don't commit it unless intended.
- Don't run `start` while another catalog app is running from this checkout: packaging rewrites `build/catalog-launcher` under it. `start` refuses when it finds one.
- When launching the app in the background yourself, background a plain command (`nohup ./'Play From Catalog.command' >log 2>&1 </dev/null &`), never `cd … && … &`: bash forks a subshell for the list that keeps your stdout open for the app's lifetime, so a piped caller hangs forever.
- The log is noisy: JCEF prints `SharedImageManager … Invalid mailbox` GPU errors (harmless). `GAMERO_EDIT_EVENT` lines mean the window is receiving keys; a `sherpa-onnx … OfflineTtsConfig` line means a spoken reply loaded the voice engine.

## Troubleshooting

- `page.goto: net::ERR_CONNECTION_REFUSED at http://127.0.0.1:8133/` → the app (and with it the catalog server) is not running; quitting the window stops both. `driver.sh status`, then `driver.sh start`.
