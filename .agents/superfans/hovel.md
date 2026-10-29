# Hovel superfan brief

The Hovel superfan routine reads this file at the start of every run. It names
what the superfans watch over, what success looks like, each component superfan,
the design principles, the superfan rules, and the plan format.

When a run spawns a component superfan, it passes that component's full entry,
the design principles, and the superfan rules. A subagent knows only what it is
given.

Last refreshed 2026-10-15 from each repo's default branch: gamero `a994b61`,
game-platform `e6bf399`, server-platform `f617258`, tool-webkit `0abbb0f`, and
hovel-editor `hovel/main` at `1a3b18f`.
Seeded from the Linear document "Hovel component superfan briefs".

The brief lives in gamero because the Hovel routine is told to read it here. It
covers all five repos; gamero does not use the shared agent-skills library.

## What the superfans watch over

Hovel is a room with one character that runs real tasks on the player's own
computer. It spans five repos:

| Repo | What Hovel keeps there |
| --- | --- |
| `Timwestberg/server-platform` | Hovel service, Brain, contracts (`event-contracts`) |
| `Timwestberg/game-platform` | Hovel web app, Godot room and character, room and character kits |
| `Timwestberg/gamero` | Kotlin Multiplatform shells: Mac companion, Android, iOS, host bridge |
| `Timwestberg/tool-webkit` | Shared SolidJS building blocks used by the web app |
| `Timwestberg/hovel-editor` | The Hovel Godot fork: editor and export templates, built from `hovel/main` |

## What success looks like

- A task the player submits runs exactly once, and its real result or failure
  reaches the player.
- The same Hovel behaves the same on web, Mac, Android and iOS. Where a platform
  cannot do something, Hovel says "unsupported" instead of pretending.
- Every API route answers in under 200 ms at p95 at the size limits.
- Nothing crosses a trust boundary (account, host, room frame, native bridge)
  that the boundary did not check.
- Every change says what was verified and what was not.

## Design principles

Every plan and every review is judged against these.

- **Reusable.** A new piece gets its behavior from one shared rule or module,
  not from a copy.
- **Extensible.** Adding a case (a capability, a room piece, a route) needs data
  or one new entry, not edits across the codebase.
- **Consistent.** One behavior on every platform and every client. The server
  owns the rules; clients follow them.

Design priorities, in order:

1. **Composability.** Small pure parts with clear inputs and outputs, combined
   by the caller.
2. **Maintainability.** One rule in one place, with a test that fails when it
   drifts.
3. **Reusability.** Reuse what exists before adding a layer or a tool.
4. **Extensibility.** See above.

Standing rules that come from Hovel's history:

- Never run a task twice. An uncertain launch is reported as failed, never
  retried.
- Fail closed and fail loudly. Corrupt state, unknown fields and missing
  permissions stop the action and say why.
- Reads take no locks and write nothing.
- GitHub Actions does not run jobs on these repos. Verify locally, and state in
  the PR what was not verified. Never claim a check passed unless it ran.

## Superfan rules

1. Read the code at the PR's head commit, not at a summary of it. Cite
   `file:line` for every finding.
2. Review against the design principles and the component's "healthy looks
   like". Do not review style the formatter owns.
3. Mark each finding **blocking** or **suggestion**. Blocking means the PR breaks
   a principle, a boundary, a test, or what its own plan promised.
4. A finding outside the PR's plan is a suggestion for the issue, not a request
   on the PR. Keep PRs small and on one feature.
5. Run the component's watch checks when the environment can. Say which ran and
   which could not, and why.
6. Post feedback as PR review comments ending with the Claude Code footer.
7. One review per round. Do not start a new round on a PR with no new commits
   since the last review. Three rounds at most; after round 3 the PR moves to
   Needs discussion with a comment that says why.
8. Never merge, never push to a default branch, and never approve on Tim's
   behalf. Ready to merge means it waits on Tim.
9. When a superfan learns something durable (a new quirk, a moved path, a new
   check), the run updates this brief through the brief PR.

## Plan format

Every plan is an issue labelled `claude-plan` (Linear project for the area, or a
GitHub issue in the repo it touches). Tim approves with the label `approved`.

- **Summary.** One paragraph: the problem and the change.
- **Why.** What players and developers get.
- **Evidence.** Numbered `file:line` facts, checked on the default branch.
- **Scope.** In scope and out of scope.
- **Types & interfaces.** New or changed signatures and contracts.
- **Approach.** Numbered steps, then how the plan meets each design principle
  and priority.
- **Repos & files touched.** One repo where possible. Two repos means one PR per
  repo with the same branch name, linked.
- **Test plan.** Numbered checks, including what cannot be verified in the cloud.
- **Open questions.** Say whether each one blocks approval.
- **Size.** S, M or L.
- **Other findings.** Out-of-scope findings, listed for later runs.

## Coordinator: API Performance superfan

Cares most about p95 under 200 ms on every API route. Measures with
`pnpm perf:http` and `pnpm perf:http:hovel -- --check` in server-platform.
server-platform, game-platform and tool-webkit have a
`.claude/skills/commit-planner`; gamero has none, so plan gamero commits by hand
and say so.

## Component superfans

### 1. Hovel service core (server-platform)

- **Lives in:** `apps/hovel-service/src/hovel.store.ts`, `hovel.service.ts`,
  `hovel.controller.ts`, `hovel-native-auth.ts`, `database/` (`hovel_accounts`,
  `hovel_hosts`, `hovel_tasks`). Port 3007, routes under `/v1/hovel`. Contracts
  in `event-contracts/src/hovel/*` (`HOVEL_LIMITS` holds text and size limits).
- **Healthy looks like:** `GET /v1/hovel` reads one REPEATABLE READ snapshot
  with no locks and lists task summaries in pages. Heartbeat, claim and progress
  lock only their own `hovel_hosts` row plus one task. Character, room,
  register, revoke and submit lock the account row then hosts, never tasks.
  Lock order everywhere: account, hosts, tasks, Brain, allowance. Limits 5 hosts and
  100 tasks (`HOVEL_HOST_LIMIT`, `HOVEL_TASK_LIMIT` in
  `apps/hovel-service/src/hovel-state.schemas.ts:11-12`). Every route p95 under 200 ms at size limits.
- **Watch:** `pnpm test:unit`; `pnpm test`; `npx jest --selectProjects hovel`
  with `DB_PORT=5432 DB_NAME=pin_lockout_test`; DB specs need
  `ALLOWANCE_TEST_URL=postgres://identity:identity@127.0.0.1:5432/pin_lockout_test`;
  `pnpm test:cov` (per-file coverage gate); `pnpm migration:run:hovel`;
  `pnpm perf:http:hovel -- --check` (`--tasks`, `--size max`).
- **Known quirks and history:** hosts and tasks moved to their own tables in
  #124 (run the Hovel migration with that deploy). The home read pages task
  summaries since #125; clients must not expect full tasks in the snapshot. The
  coverage gate was red on main until #129. No Prometheus metrics or alerts exist
  for Hovel yet. `perf/http/hovel.ts` calls the Brain feedback route, and
  `perf/http/hovel-brain.ts` seeds the data for that phase. #151 (FOR-84, 1 of
  3) added follow-ups: a task may carry `followUp`, only a follow-up stores
  `providerInput` (at most `HOVEL_LIMITS.providerInput`, 131,072 characters,
  else 409), and heartbeats declare `features` in `hovel_hosts.features`. A
  computer without `provider-input` is never sent or given a follow-up. Run the
  Hovel migration with that deploy.
- **Cares most about:** heartbeat cost staying flat as history grows, host-token
  auth, and caps.

### 2. Hovel Brain server (server-platform)

- **Lives in:** `apps/hovel-service/src/brain-accounts/*`, `brain-feedback/*`,
  `brain-allowance.*`; tables `hovel_brain_accounts`, `hovel_allowances`, the
  feedback table. Docs `docs/hovel/hovel.ARCH.md`, `apps/hovel-service/README.md`.
- **Healthy looks like:** `GET /v1/hovel/brain`, `/allowances`, `/replies`,
  `POST replies/retry-presentation`, `PUT configuration`, `POST links`,
  `links/verify`, `links/revoke`, `roles/allowance`, `GET/PUT brain/feedback`.
  Revoked hosts and replaced characters never authorize anything. Presentation
  leases and abandon fencing hold. Native ingress
  `POST /v1/hovel/native/:ownerId/:hostId/brain/:op` stays disabled (404) until
  the Mac side is wired.
- **Watch:** `brain-accounts.repository.spec.ts`, `brain-native.repository.spec.ts`
  (real Postgres), `brain-replies.service.spec.ts`,
  `brain-accounts.controller.spec.ts`, `brain-observation.transport.spec.ts`, the
  feedback PostgreSQL test (slow, 300 s budget).
- **Known quirks:** `lockBrainAccountState` locked the account, every host and
  every task even for reads until #130, which gave Brain reads a lock-free
  snapshot and stopped Brain writes locking computers and tasks. #131 reads
  `GET brain/feedback` without locks. All Brain errors map to 409. `verify`
  takes the lock twice. Since #152 a new link at the 256-link limit evicts the
  oldest revoked link; active and pending links are never evicted, and up to
  1,024 digests of evicted requests refuse replays.
- **Cares most about:** correctness of allowances and revocation, and not
  blocking the computer routes.

### 3. Hovel web app (game-platform)

- **Lives in:** `apps/game-bridge-host/src/hovel/` (`hovelService.ts`,
  `hovel.schemas.ts`, `hovelManager.ts`, `createHovelController.ts`,
  `conversations.ts`, `pendingTaskStore.ts`, `HovelView.tsx`, `BrainWorkspace.tsx`,
  `brainWorkspaceController.ts`, `brainWorkspaceService.ts`, `allowance/`,
  `brain-feedback/`), `hovel/hovelEntry.tsx` (the `/hovel` page of the app
  document; `hovel.html` only redirects there); edge route `/api/hovel` in
  `infra/edge/edge.ts`.
- **Healthy looks like:** polling only reads and never starts tasks; one
  IndexedDB pending-task store per account that clears only its exact request
  and blocks new tasks if corrupt; a read started before a save never overwrites
  it; Brain replies filtered to the current character; locked actions disabled;
  allowances fail closed.
- **Watch:** `pnpm --filter @formulagen/game-bridge-host test:hovel:unit` (90%
  coverage gate), `CHROME_PATH=/usr/bin/chromium pnpm --filter
  @formulagen/game-bridge-host test:hovel:ux`, `*.browser.test.ts`; docs
  `docs/hovel/HOVEL.{ARCH,SPEC,TEST,REVIEW}.md`. Needs sibling checkouts of
  event-contracts and tool-webkit for `file:` deps.
- **Known quirks:** the snapshot schema is strict, so a new server field breaks
  every poll until the client follows; #101 moved the client to the paged home
  snapshot and fetches whole tasks on change. Past bugs: false sign-out with two
  tabs (#94), Brain draft lost across Check connection, overlay and Escape focus.
- **Cares most about:** never running a task twice and the account boundary.

### 4. Hovel room, character and kits (game-platform, Godot 4.8)

- **Lives in:** `packages/game-bridge/godot4/games/hovel/` (`room.gd`,
  `room.tscn`, `host/`, `character/`, `environment/`, `assets/`, `tools/`);
  host side `HovelRoom.tsx`, `RoomEditor.tsx`, `createRoomController.ts`,
  `roomInteraction.ts`, `kitRenderer.ts`, `HovelRuntime.tsx`,
  `hovelRuntimeMain.tsx`. The room reaches its host only through
  `host/room_host.gd`. Server room kit in server-platform (`/v1/hovel/room`).
- **Healthy looks like:** the room runs in an opaque sandboxed frame; only
  strict-schema `hovel:room`, `room-interaction`, `render-status`,
  `load-failure` messages cross; no cookies or prompts go in; optimistic
  `expectedRevision`, 409 when the server holds a room the client cannot show;
  readiness does not flicker on identical polls; runtime art copies follow one
  copy rule and a check fails when they drift. With no host the room says
  "Room host unavailable" instead of showing a room nobody can reach.
- **Watch:** `godot --headless --script res://environment/tests/room_layout_check.gd`
  (also `runtime_copies_check.gd`, `geometry_check.gd`, `room_host_check.gd`,
  and `res://character/tests/appearance_check.gd`), after one
  `godot --headless --editor --import`; `roomEditor.browser.test.ts`,
  `roomService.test.ts`, `roomInteraction.test.ts`, `roomKitArt.test.ts`,
  `pnpm build:web`; docs `docs/hovel/room/` and the Hovel project's `README.md`.
  The `assert`-based checks hang on failure, so wrap them in `timeout` and treat
  a missing passing line as a failure.
- **Known quirks:** Hovel needs Godot 4.8 from hovel-editor (#128), fixture
  games need 4.7. A 4.6 or 4.7 editor opens the project without a warning and
  rewrites every `.import` file. Room docs dated before #128 recorded checks on
  4.6.3. Kit revision 5 (Hearth set) is merged (server #127, game-platform
  #100). Since #102 the Godot export has a content-versioned URL, so a new kit
  never meets stale art. #103 fixed undo around drags. Since #104 `derive.mjs` sizes each starter copy per
  piece (plan FOR-144). `roomMessages.ts` became `roomInteraction.ts` in #124.
  Since #131 derive checks every `catalog.json` source, and since #132 an
  unknown flag stops the art tools and `-c` means `--check`. The character
  kit's teal tunic bakes olive.
- **Cares most about:** the frame isolation boundary and stable readiness.

### 5. Gamero Mac companion and host bridge (gamero)

- **Lives in:** module `:composeApp`, package `org.formulagen.wrapper`.
  `jvmMain/.../main.kt`, `BrowserLifecycle.kt`, `BrowserReadiness.kt`,
  `PersistentDesktopBrowser.kt`, `companion/NativeBridge.kt`,
  `hovel/HovelCompanion.kt`, `HovelHostAdapter.kt`, `HovelHostSession.kt`,
  `companion/MacHostCredentials.kt`; the shared host-bridge.v2 kit in
  `commonMain/.../bridge/` (`HostBridge.kt`, `BridgeRouter.kt`,
  `BridgeProtocol.kt`, `HostCapabilities.kt`, `CompanionCapability.kt`,
  `TrustedOrigin.kt`) and its desktop transport
  `jvmMain/.../bridge/JcefHostBridge.kt`. Docs `docs/desktop.md`,
  `docs/hovel-companion.md`, `docs/hovel-native-validation.md`,
  `docs/browser-profile-review.md`.
- **Healthy looks like:** `GAMERO_JCEF_INITIALIZED` before the browser shows,
  no `GAMERO_RUNTIME_FAILED`, close within 15 s. The bridge accepts only the
  trusted origin's main frame (origin from native frame facts), requests of at most
  4096 characters for v1 and untrusted queries (a trusted v2 request may be up to
  64 Ki characters, `BridgeProtocol.MAX_REQUEST_CHARS`), a context id plus frame id on every call, and reload invalidates.
  Companion: one native call at a time, user-approval dialog,
  `registration.json` 0600 in a 0700 dir, the web side sees only the token hash,
  transport 15 s timeout, 16 MiB cap, no redirects, heartbeat every 10 s.
- **Watch:** `./gradlew :composeApp:jvmTest :composeApp:jvmJar --console=plain`
  (JDK 21; in the cloud only where dl.google.com is reachable);
  `HovelAdapterTest`, `BrowserLifecycleTest`, `BrowserReadinessTest`,
  `DesktopConfigurationTest`, `BridgeRouterTest`, `CapabilityBinderTest`,
  `TrustedOriginTest`, `DesktopParityTest`, `JcefTransportContractTest` and the
  commonTest `TransportContractTest` and `MessagePortTransportContractTest`.
  `AndroidParityTest` needs the Android unit-test task, and `IosParityTest` and
  `WebKitTransportContractTest` run only on a Mac, so a JVM run alone does not
  verify parity. Live JCEF, Keychain and real Codex runs need a Mac (`hovel-local-env-#`
  folder).
- **Known quirks:** Gradle success is never proof the page rendered. The
  persistent browser profile fix (FOR-80) is not yet Mac-validated. Account
  switching on one native profile is rejected by design. The host-bridge v2
  plan merged as #15, its contract as server-platform #128 (plan
  FOR-117/FOR-118) and its transport shapes as server-platform #153. The kit,
  the JCEF, Android and iOS transports and the parity tests merged as #16 to
  #23, #25, #28 and #30; #32 stops events to a watch a newer watch replaced.
  On desktop, v2 and the v1 companion protocol share the `gameroQuery` router;
  `JcefHostBridge` claims v2 first, and v1 stays until `nativeCompanion.ts`
  moves (task B4). Companion calls time out after 5 min (prepare, which covers approval and
  Keychain) and 2 min (activate) in `CompanionCapability.kt`. Desktop links,
  haptics and lifecycle answer `unsupported` for now.
- **Cares most about:** failing loudly, bounded shutdown, and trusting only
  native frame facts.

### 6. Gamero execution journal, Codex and Brain workers (gamero)

- **Lives in:** `jvmMain/.../companion/ExecutionJournal.kt`,
  `ExecutionCoordinator.kt`, `CodexCliProvider.kt`; `hovel/BrainCodexWorker.kt`,
  `BrainHovelRemote.kt`. Doc `docs/desktop-execution.md`.
- **Healthy looks like:** phases PREPARED, LAUNCHING, SUCCEEDED or FAILED; a
  crash after LAUNCHING becomes RECONCILIATION_REQUIRED and is never relaunched;
  same claim id on retry; `host.lock` has one owner; atomic fsync writes; corrupt
  files fail closed. Codex runs `-a never -c forced_login_method="chatgpt" exec
  --ignore-user-config --ephemeral --sandbox read-only --skip-git-repo-check`
  (ChatGPT login is forced, so a paid API key is never used), prompt on stdin, `OPENAI_*`/`CODEX_API_KEY` stripped,
  10-minute timeout with process-tree kill. Brain keeps separate execution and
  presentation journals.
- **Watch:** `ExecutionCoordinatorTest`, `CodexCliProviderTest` (fixtures do not
  prove live CLI compatibility), `BrainCodexWorkerTest`.
- **Known quirks:** the Brain worker is not installed in `HovelHostSession` yet
  (`BrainCodexWorker.kt:64`); Brain routes are gated off
  (`HovelHostAdapter.kt:30`, `brainEnabled=false`) until server native ingress is
  enabled. Read-only sandbox only.
- **Cares most about:** never running a task twice.

### 7. Gamero mobile shells and shared code (gamero)

- **Lives in:** iOS: `composeApp/src/iosMain/.../{MainViewController,IosWebUrl,NativeBridge.ios}.kt`,
  `hovel/HovelIos.kt`, `iosApp/`, `tools/ios-smoke.sh`, `docs/ios.md`, `TEST.md`.
  Android: `composeApp/src/androidMain/` (`MainActivity.kt`, `HovelAndroid.kt`).
  Shared: `composeApp/src/commonMain/` (`WebUrlPolicy.kt`, `WebViewManager`,
  the Hovel launch and start screen, `SessionManager.kt`).
- **Healthy looks like:** logs `GAMERO_PAGE_LOADED origin=… title=Hovel` within
  90 s; the URL passes `WebUrlPolicy.kt`; with `GAMERO_PRODUCT=hovel` the launch
  and start screen show the same on iOS, Android and desktop; Android and iOS
  load the web app without the legacy bridge.
- **Watch:** `./gradlew -Pgamero.iosTargets=true
  :composeApp:compileKotlinIosSimulatorArm64 :composeApp:iosSimulatorArm64Test`
  (Mac only), `tools/ios-smoke.sh`, `WebUrlPolicyTest`, `IosWebUrlTest`.
- **Known quirks and gaps:** Hovel runs as remote UI only: no Connect computer,
  no companion. Android (`bridge/AndroidHostBridge.kt`) and iOS
  (`bridge/IosHostBridge.kt`, `WebKitHostTransport.kt`) carry the host-bridge.v2
  `gameroHost` transport (#28, plan FOR-120), with `companion` passed as
  `Unsupported`; there is no `gameroQuery` (`NativeBridge.ios.kt` only posts
  NSNotifications). No `iosX64` target. Sign-in needs HTTPS.
  `SessionManager.kt:31` logout is `TODO()` and crashes. The iOS start screen
  merged as #24; the smoke script does not exercise the Hovel path yet.
- **Cares most about:** parity with desktop where the platform allows it, and
  saying "unsupported" explicitly where it does not.

### 8. tool-webkit Hovel building blocks (tool-webkit)

- **Lives in:** `src/connections/` (ConnectionCard), `src/browser-storage/`,
  `src/identity/` and `src/identity/ui/` (AuthCard, OtpInput), `src/entity-kit/`,
  `src/feed/`, `src/sse/`. Package `@formulagen/web-toolkit` (SolidJS). The
  character creator, Brain tile and chat views live in game-platform.
- **Healthy looks like:** ConnectionCard never shows offline or unavailable as
  connected, every state has visible status text and accessible names;
  browser-storage reads consistently across reloads and tabs and fails typed when
  storage is full; OTP digits paint once.
- **Watch:** `pnpm test`, `pnpm test:coverage` (90% floor only for
  `COVERAGE_INCLUDE` paths), `pnpm test:ux` (Storybook in Chromium, axe
  violations fail), `pnpm check`, `pnpm smoke:{entity-kit,browser-storage,feed,feed-persist,sse}`.
  Tests pass `--conditions=browser`, and so do the smoke scripts for
  browser-only modules (`component-registry`, `entity-kit`, `game-results`,
  `monaco-editor`); the other smoke scripts run without it.
- **Known quirks:** entity-kit documents its states, data client contract,
  failures and accessibility since #65. ConnectionCard has no unit tests and
  `status` is a plain `string` (`connections.types.ts:4`); browser-storage is
  outside the coverage floor and its parser overrides never run on string or
  boolean keys.
- **Cares most about:** accessibility and never misreporting state.

### 9. Godot engine layer (game-platform, gamero)

- **Lives in:** game-platform `packages/game-bridge/godot4/games/hovel/`
  (`project.godot`, `export_presets.cfg`, `room.gd`, and `host/`, the seam
  between the room and whatever hosts it), `packages/game-bridge/src/godot.ts`
  (the web loader), `scripts/build-web.sh` (export and version check). gamero
  has no Godot dependency: every shell loads the Hovel web app, and with it the
  Godot web export, in a web view (`commonMain/.../managers/WebViewManager.kt`,
  `WebViewUserAgent.kt`).
- **Healthy looks like:** each platform runs the Godot build made for it when
  that build is Available (same source and Godot version as the editor, starts
  inside Hovel on that platform, passes that platform's Hovel tests), and the
  Godot web engine otherwise. Each platform has one build-time place that
  selects native or web. A platform with a native engine keeps both variants
  building. A capability behaves the same on either engine: API shape, errors,
  threading, lifecycle and "unsupported". Every engine and the editor come from
  one pinned hovel-editor commit.
- **Watch:** from the Hovel Godot project, `godot --headless --editor --import`,
  `godot --headless --export-release Web builds/web/hovel.html`, the room checks
  under component 4 (`room_host_check.gd` covers the host seam), and
  `GODOT=<bin> FORCE_GODOT=1 pnpm build:web` in game-platform. There are no
  official 4.8 templates: use the editor and non-threaded Web template built
  from hovel-editor (component 10). Android native builds need the Android SDK
  and iOS needs a Mac; neither exists in the cloud, so use a
  `hovel-local-env-#` folder.
- **Known quirks and history:** every platform runs the web engine today; no
  native engine is started. Since game-platform #128 Hovel declares 4.8
  (`project.godot:6`, GL Compatibility, non-threaded Web preset) built from
  hovel-editor, while the fixture games need 4.7, and `build-web.sh` refuses a
  mismatched major.minor but checks nothing finer. The Hovel `README.md` records
  the hovel-editor commit the room was last verified on (`92c9648`, upstream
  `4d10419`, Emscripten 6.0.12); update it when you re-verify. Since #133
  (FOR-153) `host/room_host.gd` picks the host once: the page through
  `JavaScriptBridge` on web, else an engine singleton named `HovelHost` with the
  same three calls and JSON, else none. With none the room shows "Room host
  unavailable", logs one error and stops polling. A native host owns the
  boundary the runtime page owns on web (schema, `characterId`, readiness
  rules); the room calls it on Godot's main thread. Android WebView's
  `Version/4.0` token made Emscripten refuse to start the export
  (`WebViewUserAgent.kt:9-15`). Notes on Godot 4.6's embeddable Android library
  (`org.godotengine:godot:4.6.3.stable`) and LibGodot (experimental,
  desktop-only in 4.6) predate the move to 4.8; recheck them before planning a
  native engine.
- **Cares most about:** one engine selector per platform, a web fallback that
  always builds, and identical behavior on both engines.

### 10. Hovel editor (hovel-editor)

- **Lives in:** `Timwestberg/hovel-editor`, a fork of `godotengine/godot`.
  `master` mirrors upstream and is never committed to; every Hovel build comes
  from `hovel/main`, which is the upstream commit in `hovel/UPSTREAM` (today
  `master` at `4d10419`, the 4.8 beta) plus the `.github/CODEOWNERS` entry
  `CORE_CHANGES` allows, plus the `hovel/` folder: `profile.py`,
  `modules/` (no modules yet), `CORE_CHANGES`, and `scripts/build.sh`,
  `sync-upstream.sh` and `check-divergence.sh`. Docs `hovel/README.md`.
- **Healthy looks like:** the editor and every engine (iOS, Android, web) build
  from the same fork and Godot version, and the engines include every custom
  module the editor uses. Hovel changes live in custom modules, editor plugins
  and build options, not Godot core. Godot's license, copyright notices and
  trademark policy are kept. Godot upgrades only through an approved plan.
- **Watch:** `hovel/scripts/check-divergence.sh` (fails when a file outside
  `hovel/` differs from the upstream commit and is not listed, with a reason, in
  `hovel/CORE_CHANGES`; today that list holds only `.github/CODEOWNERS`). Cloud build, verified 2026-10-15
  on `d9f0725` (engine the same as `92c9648`): `pip install scons zstandard`;
  the editor with `python3 -m SCons profile=hovel/profile.py platform=linuxbsd
  target=editor x11=no wayland=no alsa=no pulseaudio=no dbus=no speechd=no
  fontconfig=no udev=no -j4` (about 25 min on 4 cores); the Web template with
  emsdk 6.0.12 and `platform=web target=template_release threads=no` (about 10
  min), copied to
  `~/.local/share/godot/export_templates/4.8.beta/web_nothreads_release.zip`
  (also as `web_nothreads_debug.zip`). Then run component 9's export checks.
  Android needs the SDK and NDK, and iOS a Mac.
- **Known quirks:** `hovel/main` follows upstream `master`, not a stable
  release, so there are no official templates; `sync-upstream.sh <tag>` moves
  it to a release. A sync is a PR into `hovel/main`, then a rebuild and the
  Hovel checks in game-platform before merging. `hovel/main` moved past
  `d9f0725` only in `sync-upstream.sh` (#1, `1a3b18f`).
- **Cares most about:** small, mergeable divergence from upstream.

### 11. Hovel characters and animation (game-platform)

- **Lives in:** game-platform `packages/game-bridge/godot4/games/hovel/`:
  `assets/character/kit/` (`kit.json`, `kit.png`, `kit-mask.png`), baked by
  `tools/character-kit/bake.mjs` (`poses.mjs` makes the `wave` and `cry`
  poses); `character/kit.gd` (renderer), `appearance.gd` (action player),
  `action_request.gd`, `walker.gd`, `movement.gd`. The web renderer for the
  creator, messenger and thumbnails is `apps/game-bridge-host/src/hovel/kitRenderer.ts`.
  The saved look and option catalog are server-platform
  `event-contracts/src/hovel/appearance.{catalog,schemas}.ts`; the action catalog
  and emote unlocks are `actions.catalog.ts` (`HOVEL_ACTIONS`). Conversations live
  in `apps/game-bridge-host/src/hovel/conversations.ts` and `docs/hovel/CONVERSATIONS.*.md`.
  Plans and the state of every animation: the "Hovel Animation Sheet" artifact
  and Linear issues labelled `animation`.
- **Healthy looks like:** one base character model that every character shares
  (same parts, pivots, attachment points and face controls); each animation made
  once on it and played by every character; a customization option changes looks,
  never the skeleton; every option that exists keeps working and a saved character
  draws the same before and after a model change (frame hashes); one recipe to add
  an animation and one to add an option, neither editing the other; body, upper-body
  and face layers so a character can talk or emote while it walks; idle and
  conversation events reach animations through one mapping, never by name from
  conversation code; every animation tested on every reference character, loops
  without a seam, blends without a pop, and reads at the 110 px room size.
- **Watch:** `node tools/character-kit/bake.mjs --check`; from the Hovel project,
  after one `godot --headless --editor --import`, `timeout`-wrapped
  `godot --headless --script res://character/tests/appearance_check.gd` (also
  `action_player_check.gd`, `action_request_check.gd`, `movement_check.gd`,
  `movement_rate_check.gd`); `pnpm --filter @formulagen/game-bridge-host
  test` (`kitRenderer.test.ts` keeps `character/tests/kit_frames.json`, 88 looks
  x 6 frames, equal between the renderers; `test:hovel:unit` does not run it).
  The kit's Node renderer (`tools/character-kit/kit-render.mjs`) plus the settle
  and pose steps from `kit.gd` can render preview clips without Godot: a check
  on 2026-10-15 reproduced all 528 pinned hashes, which the suite itself does
  not verify (it compares the renderers for idle and blink only).
- **Known quirks and history:** there is no skeleton yet. The kit is one front-facing
  224 x 490 layered sprite (`hovel.traveler-v1`, one body); every option is a
  whole-frame sprite, and an action is `[frame, ms, lift]` steps over six frames
  (`idle`, `breathe`, `blink`, `crouch`, `wave`, `cry`, `kit.gd:9`). The action
  library came from FOR-184 to FOR-188 and the movement sprint FOR-192. `breathe`
  and `crouch` shift rows above the waist (row 312) and leave a seam through the
  hands and shirt hem; the generated `wave` arm is skin only, so the sleeve stays
  hanging; `walk` moves no legs. Godot shares animations by node path and
  retargets only 3D skeletons, so 2D characters share an animation only with the
  same node names, paths and rest poses. Rotating pixel-art parts blurs them at
  room size; prefer whole-pixel offsets and painted part poses.
- **Cares most about:** every animation playing correctly on every customization,
  and a player's saved character keeping its look.

## Watch sources

- Open PRs in the five repos and the Hovel PR Tracker artifact.
- Linear projects "Hovel PoC: one character, Codex, and a connected Mac" and
  "Hovel Brain: local runtimes and role routing" (labels `claude-plan`,
  `approved`, `needs-input`).
- `TODO` and `FIXME` comments in the paths above.
- The iOS gaps listed under component 7.
- The "Hovel Animation Sheet" artifact and Linear issues labelled `animation`.
