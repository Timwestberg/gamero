# Hovel superfan brief

Current architecture reference, refreshed during companion projects step H and
robot contract 4 (2026-10-30). Read code at the reviewed head before relying on
any historical review or measurement. Previous component surveys remain in Git.

## What the superfans watch over

Hovel is an account's companions, projects and conversations, with a selected
Ember robot in the room, pet and games. The server stores account state; a
project's Mac runs providers and owns paths, credentials and journals.

| Repo | Responsibility |
| --- | --- |
| server-platform | Contracts, Hovel account state, routing, memory and approvals |
| game-platform | Web app, sandboxed Godot room/preview, shared robot and games |
| gamero | Native bridge, provider execution, approvals, voice and game embedding |
| tool-webkit | Shared SolidJS primitives |
| hovel-editor | Godot fork and matching room export templates |

## What success looks like

- A stored message runs under its claim, with ambiguous work reconciled rather
  than replayed. Real outcomes reach the account conversation.
- Each trust boundary validates its own data and ownership; unsupported native
  capabilities are explicit.
- Room, preview, pet and games use the same companion's four colors.
- Test results distinguish fixtures, real runtime evidence and unrun acceptance.

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

### Service and contracts

Inspect server-platform `event-contracts/src/hovel/companions`,
`apps/hovel-service/src/companions`, `memory`, `allowance` and core host/room
routes. The service README and companions README own the route/lifecycle
reference. Contract 4 stores robot colors directly; the reset migration is
irreversible. The task API and hosted two-role brain are removed. Frozen
migrations stay registered. Counted action grants remain supported, although
all robot actions currently use always-available grants.

### Web account UI

Inspect game-platform `apps/game-bridge-host/src/{account,companions,projects,
conversations,brain,providers,approvals}`. HTTP/SSE owns account state;
host-bridge is only for device capabilities. There are no team-chat, local
profile or workspace bridge APIs. Read `docs/launcher/companion-projects.README.md`
and its ARCH/TEST before reviewing behavior. Contract-4 desktop games read the
current selected companion at launch; closing during lookup cancels dispatch.

### Room, preview and robot

Inspect `packages/game-bridge/godot4/games/hovel`, `ember-rogue-addons/addons/
ember_core/robot`, `ember-desktop-pet` and the two games. The room combines its
2D environment with the shared 3D robot, movement driver, equipment and dummy.
The editor uses the same export in preview mode, with an editable portrait
fallback. Its frame has no account or native-bridge access. Messages use
`companionId` and `look`; preview acknowledgments identify the draft they drew.

The old human creator, character bake tools, sprites and kit validation are
deleted. Use robot movement/equipment/action checks and current browser tests,
not those old commands. Keep room 4.8, games 4.8-beta1 and pet 4.7.2 separate.
Show rendered evidence before visual merges. A fallback or native screenshot
is not proof of physical-phone or web performance acceptance.

### Native execution and bridge

Inspect Gamero `conversations/`, `conversation/voice/`, `runtimes/`, `hovel/`,
`bridge/`, `progression/` and `progression/embedded/`. `ConversationRunner` and
`CoordinatorEngine` run project work; `MemoryJobLane` uses the execution journal
for memory jobs. Paths and provider logins stay native. Approvals use native
brokers and paired device authority. Game IDs resolve under `HOVEL_GAMES_ROOT`;
`DesktopGames.kt` owns launch environment and per-companion profile/save paths.
See [Hovel host](../../docs/hovel-companion.md) and
[launch settings](../../docs/companion-presentation/README.md).

### Mobile, shared UI and engine

Review common contracts against JVM/iOS/Android fixtures. Mobile hosts expose
only their implemented capabilities; real phone pairing and rendering remain
separate tests. Shared UI stays in tool-webkit; product rules stay in its callers.
Engine/export changes must use the matching Hovel build and preserve the
sandbox, single-threaded export and precompressed Brotli delivery.

## Watch sources

Use current source/tests and the accepted companion-projects and companion-robot
plans. Track deferred engineering work in game-platform
`docs/launcher/companion-projects.FOLLOWUPS.md`. Live providers, context-folder
probes, phone pairing and embedded Overclock are not authorized by reading this
brief; obtain the owner's permission for those checks.
