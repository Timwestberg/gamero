# Hovel superfan brief

The Hovel superfan routine reads this file at the start of every run. It names
what the superfans watch over, what success looks like, each component superfan,
the design principles, the superfan rules, and the plan format.

When a run spawns a component superfan, it passes that component's full entry,
the design principles, and the superfan rules. A subagent knows only what it is
given.

Last refreshed 2026-10-14 from each repo's default branch: gamero `2a6c80a`,
game-platform `f696cd9`, server-platform `634f71b`, tool-webkit `8ac8b0a`.
Seeded from the Linear document "Hovel component superfan briefs".

## What the superfans watch over

Hovel is a room with one character that runs real tasks on the player's own
computer. It spans four repos:

| Repo | What Hovel keeps there |
| --- | --- |
| `Timwestberg/server-platform` | Hovel service, Brain, contracts (`event-contracts`) |
| `Timwestberg/game-platform` | Hovel web app, Godot room and character, room and character kits |
| `Timwestberg/gamero` | Kotlin Multiplatform shells: Mac companion, Android, iOS, host bridge |
| `Timwestberg/tool-webkit` | Shared SolidJS building blocks used by the web app |

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
  in `event-contracts/src/hovel/*` (`HOVEL_LIMITS`).
- **Healthy looks like:** `GET /v1/hovel` reads one REPEATABLE READ snapshot
  with no locks and lists task summaries in pages. Heartbeat, claim and progress
  lock only their own `hovel_hosts` row plus one task. Character, room,
  register, revoke and submit lock the account row then hosts, never tasks.
  Lock order everywhere: account, hosts, tasks, Brain, allowance. Limits 5 hosts,
  100 tasks. Every route p95 under 200 ms at size limits.
- **Watch:** `pnpm test:unit`; `pnpm test`; `npx jest --selectProjects hovel`
  with `DB_PORT=5432 DB_NAME=pin_lockout_test`; DB specs need
  `ALLOWANCE_TEST_URL=postgres://identity:identity@127.0.0.1:5432/pin_lockout_test`;
  `pnpm test:cov` (per-file coverage gate); `pnpm migration:run:hovel`;
  `pnpm perf:http:hovel -- --check` (`--tasks`, `--size max`).
- **Known quirks and history:** hosts and tasks moved to their own tables in
  #124 (run the Hovel migration with that deploy). The home read pages task
  summaries since #125; clients must not expect full tasks in the snapshot. The
  coverage gate was red on main until #129. No Prometheus metrics or alerts exist
  for Hovel yet. The perf script never calls the feedback route.
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
  every task even for reads; #130 (open) gives Brain reads a lock-free snapshot.
  `GET brain/feedback` still locks three rows and writes the ledger on every read
  (plan FOR-143). All Brain errors map to 409. `verify` takes the lock twice.
- **Cares most about:** correctness of allowances and revocation, and not
  blocking the computer routes.

### 3. Hovel web app (game-platform)

- **Lives in:** `apps/game-bridge-host/src/hovel/` (`hovelService.ts`,
  `hovel.schemas.ts`, `hovelManager.ts`, `createHovelController.ts`,
  `conversations.ts`, `pendingTaskStore.ts`, `HovelView.tsx`, `BrainWorkspace.tsx`,
  `brainWorkspaceController.ts`, `brainWorkspaceService.ts`, `allowance/`,
  `brain-feedback/`), `hovel.html`; edge route `/api/hovel` in `infra/edge/edge.ts`.
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

### 4. Hovel room, character and kits (game-platform, Godot 4.6)

- **Lives in:** `packages/game-bridge/godot4/games/hovel/` (`room.gd`,
  `room.tscn`, `character/`, `environment/`, `assets/`, `tools/`); host side
  `HovelRoom.tsx`, `RoomEditor.tsx`, `createRoomController.ts`,
  `roomMessages.ts`, `kitRenderer.ts`, `hovelRuntimeMain.tsx`. Server room kit in
  server-platform (`/v1/hovel/room`).
- **Healthy looks like:** the room runs in an opaque sandboxed frame; only
  strict-schema `hovel:room`, `room-interaction`, `render-status`,
  `load-failure` messages cross; no cookies or prompts go in; optimistic
  `expectedRevision`, 409 when the server holds a room the client cannot show;
  readiness does not flicker on identical polls; runtime art copies follow one
  copy rule and a check fails when they drift.
- **Watch:** `godot --headless --script res://environment/tests/room_layout_check.gd`
  (also `appearance_check.gd`, `runtime_copies_check.gd`, `geometry_check.gd`),
  `roomEditor.browser.test.ts`, `roomService.test.ts`, `roomMessages.test.ts`,
  `roomKitArt.test.ts`, `pnpm build:web`; docs `docs/hovel/room/`.
- **Known quirks:** Hovel needs Godot 4.6, fixture games need 4.7. Kit revision 5
  (Hearth set) is merged (server #127, game-platform #100). #102 (open) gives the
  Godot export a content-versioned URL so a new kit never meets stale art. #103
  (open) fixes undo around drags. `derive.mjs` uses one prop scale for every
  starter copy (plan FOR-144). The character kit's teal tunic bakes olive.
- **Cares most about:** the frame isolation boundary and stable readiness.

### 5. Gamero Mac companion and host bridge (gamero)

- **Lives in:** module `:composeApp`, package `org.formulagen.wrapper`.
  `jvmMain/.../main.kt`, `BrowserLifecycle.kt`, `BrowserReadiness.kt`,
  `PersistentDesktopBrowser.kt`, `companion/NativeBridge.kt`,
  `hovel/HovelCompanion.kt`, `HovelHostAdapter.kt`, `HovelHostSession.kt`,
  `companion/MacHostCredentials.kt`. Docs `docs/desktop.md`,
  `docs/hovel-native-validation.md`, `docs/browser-profile-review.md`.
- **Healthy looks like:** `GAMERO_JCEF_INITIALIZED` before the browser shows,
  no `GAMERO_RUNTIME_FAILED`, close within 15 s. The bridge accepts only the
  trusted origin's main frame (origin from native frame facts), requests under
  4096 bytes, a context id plus frame id on every call, and reload invalidates.
  Companion: one native call at a time, user-approval dialog,
  `registration.json` 0600 in a 0700 dir, the web side sees only the token hash,
  transport 15 s timeout, 16 MiB cap, no redirects, heartbeat every 10 s.
- **Watch:** `./gradlew :composeApp:jvmTest :composeApp:jvmJar --console=plain`
  (JDK 21, works in the cloud); `HovelAdapterTest`, `BrowserLifecycleTest`,
  `BrowserReadinessTest`, `DesktopConfigurationTest`. Live JCEF, Keychain and real
  Codex runs need a Mac (`hovel-local-env-#` folder).
- **Known quirks:** Gradle success is never proof the page rendered. The
  persistent browser profile fix (FOR-80) is not yet Mac-validated. Account
  switching on one native profile is rejected by design. The host-bridge v2
  rewrite is a stack of draft PRs (#15 to #25, with the contract in
  server-platform #128, plan FOR-117/FOR-118); drafts are tracked but not reviewed
  until marked ready.
- **Cares most about:** failing loudly, bounded shutdown, and trusting only
  native frame facts.

### 6. Gamero execution journal, Codex and Brain workers (gamero)

- **Lives in:** `jvmMain/.../companion/ExecutionJournal.kt`,
  `ExecutionCoordinator.kt`, `CodexCliProvider.kt`; `hovel/BrainCodexWorker.kt`,
  `BrainHovelRemote.kt`. Doc `docs/desktop-execution.md`.
- **Healthy looks like:** phases PREPARED, LAUNCHING, SUCCEEDED or FAILED; a
  crash after LAUNCHING becomes RECONCILIATION_REQUIRED and is never relaunched;
  same claim id on retry; `host.lock` has one owner; atomic fsync writes; corrupt
  files fail closed. Codex runs `-a never exec --ignore-user-config --ephemeral
  --sandbox read-only`, prompt on stdin, `OPENAI_*`/`CODEX_API_KEY` stripped,
  10-minute timeout with process-tree kill. Brain keeps separate execution and
  presentation journals.
- **Watch:** `ExecutionCoordinatorTest`, `CodexCliProviderTest` (fixtures do not
  prove live CLI compatibility), `BrainCodexWorkerTest`.
- **Known quirks:** the Brain worker is not installed in `HovelHostSession` yet
  (`BrainCodexWorker.kt:64`); Brain routes are gated off
  (`HovelHostAdapter.kt:30`, `brainEnabled=false`) until server native ingress is
  enabled. Read-only sandbox only.
- **Cares most about:** never running a task twice.

### 7. Gamero iOS port (gamero)

- **Lives in:** `composeApp/src/iosMain/.../{MainViewController,IosWebUrl,NativeBridge.ios}.kt`,
  `iosApp/`, `tools/ios-smoke.sh`, `docs/ios.md`, `TEST.md`.
- **Healthy looks like:** logs `GAMERO_PAGE_LOADED origin=… title=Hovel` within
  90 s; the URL passes `WebUrlPolicy.kt`; with `GAMERO_PRODUCT=hovel` the launch
  and start screen show as on desktop and Android.
- **Watch:** `./gradlew -Pgamero.iosTargets=true
  :composeApp:compileKotlinIosSimulatorArm64 :composeApp:iosSimulatorArm64Test`
  (Mac only), `tools/ios-smoke.sh`, `WebUrlPolicyTest`, `IosWebUrlTest`.
- **Known quirks and gaps:** Hovel runs as remote UI only: no Connect computer,
  no companion, no `gameroQuery` bridge (`NativeBridge.ios.kt` only posts
  NSNotifications; plan FOR-120). No `iosX64` target. Sign-in needs HTTPS.
  `SessionManager.kt:31` logout is `TODO()` and crashes. The start screen is in
  #24 (open); the smoke script does not exercise the Hovel path yet.
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
  Test and smoke commands need `--conditions=browser`.
- **Known quirks:** ConnectionCard has no unit tests and `status` is a plain
  `string` (`connections.types.ts:4`); browser-storage is outside the coverage
  floor and its parser overrides never run on string or boolean keys.
- **Cares most about:** accessibility and never misreporting state.

## Watch sources

- Open PRs in the four repos and the Hovel PR Tracker artifact.
- Linear projects "Hovel PoC: one character, Codex, and a connected Mac" and
  "Hovel Brain: local runtimes and role routing" (labels `claude-plan`,
  `approved`, `needs-input`).
- `TODO` and `FIXME` comments in the paths above.
- The iOS gaps listed under component 7.
