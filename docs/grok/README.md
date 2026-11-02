# Grok Build integration

Development companion conversations (talk roles) and project work are implemented for **Grok Build 1.0.70 (f50904eaee6c)**. Launch Hovel with `HOVEL_GROK_PROVIDER=1`; optionally select an absolute executable with `GAMERO_GROK_PATH`. Release and unmarked builds keep Grok off. Project work has a separate development opt-in, `HOVEL_GROK_WORK=1`. Grok Bot remains unavailable.

## Sign in and talk

Use Manage subscriptions → Grok → installation instructions or **Sign in to Grok Build**. The explicit button opens the installed runtime's `grok login` in Terminal. Return to Hovel and choose **Check again**. Hovel checks the runtime without a model prompt or interactive login. A verified personal OIDC subscription shows **Connected** and supplies its model catalog. A saved credential without verified access shows **Sign-in detected**. It does not ask you to sign in repeatedly.

Bind a brain role to Grok in the companion's brain editor. Its turns run in the companion's conversations on this Mac, through the conversation runner, with Stop and explicit Retry. Status cannot establish that a future remote request will succeed; the adapter rechecks account access and isolation before each prompt. Unsupported versions or managed policies remain unavailable.

## Native composition

```kotlin
val connection = GrokConnection(environment)
val provider = GrokProvider(connection)
val talk = GrokChatRuntime(grokRoot, environment, connection, retained)
val work = GrokWorkRuntime(grokRoot, environment, connection, broker, retained, NativeGrokWorkTrust(dialogs))
```

`DesktopServices` builds these for the conversation runner only behind the development gate; `retained` reads the bindings the account's conversations can still resume (`ConversationStore.bindingHandles`). No Grok-specific web transport is needed. `GrokConnection` owns no-prompt subscription/model checks. `GrokLaunch` (talk isolation) and `GrokWorkLaunch` (the player's own setup) are the two `GrokLaunchPolicy` launches. `GrokSession` owns one ACP session's open, prompt, Stop and teardown for both modes; `GrokTalkSession` and `GrokWorkSession` add only how each revalidates before a prompt. `GrokChatRuntime` and `GrokWorkRuntime` adapt the existing turn interfaces and share one turn start (`resolveGrokTalkTurn`/`resolveGrokWorkTurn`). Pure protocol/account/tool parsers and the private binding store are independently tested.

The transport (`GrokAcp`), both launch policies, account and handshake parsing, and the release pin live in `providers/grok`; sessions, turns, approvals and activity live in `runtimes/grok`, which also holds both runtimes.

Every chat process uses a fresh private HOME/GROK_HOME and the supported `GROK_AUTH_PATH` reference to the runtime-owned login. Hovel never reads or copies credential contents. Only provider session data survives through a native-owned directory link; configuration, downloaded bundles and preference changes are discarded after process teardown. Chat launches do not modify the user's Grok config. See the [source audit](source-audit.md) for the profile, reload exclusion and narrowly accepted internal watcher replies.

Only Hovel UUID handles enter a conversation's record. Sidecars bind the raw provider ID to account fingerprint, account, companion, concrete model and mode. A persona or model change, or a missing mapping, loses the conversation before submission, and the role's next turn starts fresh. Foreign/corrupt mappings and ambiguous transport failures never automatically replay a prompt. Bounded orphan cleanup keeps every binding a conversation can still resume (each coordinator's, each thread's specialist) plus active handles; an unreadable record deletes nothing.

## Validation

[Sanitized live evidence](talk-1.0.70.json) records subscription access, no-tool sessions, streamed replies, restart/resume and terminal cancellation. It contains no identity, session ID, credential, prompt or response content. The official source and installed binary have different revision identifiers; source claims are separately pinned in the audit.

Checks against the installed runtime are in `*SmokeTest` classes and skip unless explicitly enabled. `HOVEL_GROK_SMOKE=/absolute/path/to/grok` runs the checks that send no model turn; several of them still authenticate with the player's account, and the work ones need the [player's OIDC setting](#player-requirement-for-work), so narrow the selection with `--tests` when that is not wanted. `HOVEL_GROK_SMOKE_TURNS=1` also runs the checks that send controlled model turns on the subscription. Checks that sign in find the player's provider home from `GROK_HOME` (default `~/.grok`) and reference the login at `GROK_AUTH_PATH` (default in that home), never reading it. Work checks that create sessions directly or grant folder trust copy only `config.toml` into a disposable provider home, so the player's session and trust stores stay untouched; the cleanup check alone runs ordinary work in the player's own provider home, and never grants trust. Live work checks fail rather than skip when the OIDC setting is missing. The rules are in `GrokSmoke.kt`.

```sh
# Local fake peers, bridge/provider/conversation-store and approval regressions.
./gradlew :composeApp:jvmTest --tests '*Grok*' --tests '*bridge*' \
  --tests '*ConversationStoreTest*' --tests '*Provider*' --tests '*approvals*'

# Live talk checks, including controlled model turns.
HOVEL_GROK_SMOKE=/absolute/path/to/grok HOVEL_GROK_SMOKE_TURNS=1 \
  ./gradlew :composeApp:jvmTest --tests '*GrokChatRuntimeSmokeTest' --tests '*GrokTalkSessionSmokeTest' \
  --tests '*GrokAcpSmokeTest'
```

Under the previous per-test smoke flags, the live selection passed with no skips on 2026-10-28; the broader regression selection skips only explicit runtime/audio checks. The older initialization-only probe remains available as `python3 tools/probe-grok-initialize.py /absolute/path/to/grok`; it uses an empty home and does not authenticate or prompt. Its results are historical transport evidence, not the current readiness implementation.

The work continuation passed its focused regression and two separate real adapter smoke tests. Native Mac acceptance passed working-role setup, project trust, exact edit approval/denial, activity, Stop, Close chat, quit with a pending approval, and a fresh work reply after restart. See the work evidence below.

## Project work

Enable both development flags, bind a working brain role to Grok, and choose the project's working folder. Hovel uses its existing native start confirmation, the thread's activity, Stop and the conversation's history.

Work runs in the player's own environment with their login-shell `PATH` (Grok's folder first), minus inherited `XAI_*` and `GROK_*` variables. It preserves the selected HOME/GROK_HOME and uses the runtime-owned credential reference. It requires the [player's OIDC setting](#player-requirement-for-work); a bounded TOML parser checks the actual setting, and Hovel never writes it. Runtime inspection accepts ordinary user preferences, skills and project instructions, and refuses extra configuration layers, hooks/plugins/MCP/LSP, managed policy, custom models/endpoints/auth providers, config overrides and command prefixes. Unsupported configurations remain unavailable rather than being silently ignored.

An untrusted project gets a separate native **Trust project in Grok** question. It names the project folder chosen in Hovel and the folder Grok will trust, says plainly when Grok trusts an enclosing folder, and explains that Grok remembers trust for future runs outside Hovel. When Grok's trust root is the home folder, a folder above it, or `/`, work does not start and no question is asked. After trusting, Grok must report exactly the confirmed folder. Not now, dismissal and Stop do not grant trust. Only confirmation invokes Grok's own `--trust inspect --json`, followed by a fresh inspection before starting the agent. Test trust grants use a disposable provider home; credentials are referenced, never copied.

The per-run work profile appends the working persona and retains project rules. Its exact allowlist is read_file, list_dir, grep, search_replace and run_terminal_cmd. In the pinned runtime, a nonempty allowlist without Agent directives also forces shell backgrounding off after user/remote parameter merges. MCP, subagents, goals and workflows are not offered. Commands/edits go through Grok's tools and the existing native approval broker. **Allow once** returns the exact verified option; **Deny** never becomes a persistent grant. A denied operation can make Grok return `cancelled`; Hovel shows that work stopped because approval was not given.

One process owns one turn. The next turn resumes its private provider session only after the prior process is reaped. Work bindings have a separate directory and include canonical workspace, account, persona and model ownership. Stop/release/close cancel visible and queued approvals immediately, request ACP cancellation, then force local teardown after five seconds if necessary. Unconfirmed cleanup disables further work in that runtime. Activity retains bounded category/path summaries, never raw command output or reasoning.

[Work acceptance evidence](work-1.0.70.json) distinguishes protocol, production-adapter and native-screen checks. The production adapter has passed edit allow/deny, repeat permissions, project-rule loading, activity and restart/resume. Real Stop/release/close checks observed a shell and sleep child, then confirmed both exited without the final write. A command remained foreground beyond 35 seconds without timeout/background parameters. This validates disabled runtime backgrounding; it is not a guarantee for arbitrarily self-detaching or remote commands.

```sh
# Controlled production-adapter turns; only disposable files/commands are approved.
HOVEL_GROK_SMOKE=/absolute/path/to/grok HOVEL_GROK_SMOKE_TURNS=1 \
  ./gradlew :composeApp:jvmTest --tests '*GrokWorkRuntimeSmokeTest'

# Deterministic regressions, with unrelated live checks skipped unless explicitly enabled.
./gradlew :composeApp:jvmTest --tests '*Grok*' --tests '*approvals*' \
  --tests '*CoordinatorEngineTest*' --tests '*DesktopServicesTest*'
```

The older direct protocol probes remain in `GrokWorkAuthSmokeTest`: `HOVEL_GROK_SMOKE` runs its authentication checks, and `HOVEL_GROK_SMOKE_TURNS=1` adds its permission and lifecycle turns. See the [source audit](source-audit.md). Release activation, unsupported integrations, arbitrary detached/remote work, actual authenticated profile switching and modal-approval Stop remain separate acceptance work.

### Player requirement for work

Grok work signs in only with the player's Grok account, never an API key. Grok has no per-process way to pin that, so the player adds this to their own Grok config (`$GROK_HOME/config.toml`, by default `~/.grok/config.toml`):

```toml
[auth]
preferred_method = "oidc"
```

This setting is global: it also changes how Grok Build signs in outside Hovel, for every project, until the player removes it. Hovel never writes or edits this file; when the setting is missing, work readiness names the exact setting, the file and this effect. Talk roles do not need it, because each talk process pins its own disposable config.

## Integrated main acceptance

The feature is combined with main's usage/model-effort interfaces, and the matching contract and web changes include the current Brain editor. Grok usage has no reported windows, so the existing UI shows Unavailable. The native Codex adapter now ignores threadless account notifications after handling transport/security events; a failing-before/passing-after regression and native context handover verify the fix.

The native suite passes with only opt-in checks skipped; contract tests, focused web and Storybook tests, typecheck and contract/Kotlin fixture parity pass. Six broad-web failures also reproduce on the main baseline and remain outside this feature. The macOS package was rebuilt and launched with the matching sources.

```sh
# Live Codex readiness/configuration, without a model turn.
HOVEL_CHAT_CODEX_SMOKE=/absolute/path/to/codex \
  ./gradlew :composeApp:jvmTest --tests '*TalkRuntimeSmokeTest'
```

The team chat and profile checks this section once listed (Work handovers between members, profile-lease switching) went with team chat; conversations run one coordinator and its threads for the signed-in account. Codex Work stays disabled. See [sanitized evidence](work-1.0.70.json). Release gates remain closed.

## Retry lifecycle

```sh
# Pending-approval cancellation and a lost session's binding.
./gradlew :composeApp:jvmTest --tests '*GrokWorkRuntimeTest' --tests '*approvals*'
```

Native dialogs now use modeless presentation so the main window can receive Stop. The desktop Stop-with-approval-open run is still unverified: its edit was applied and the action taken needs clarification. Authenticated Hovel account-switch UI acceptance requires the currently unavailable local identity service and test accounts. Release gates remain closed.

## Bumping the supported Grok version

Hovel accepts exactly one Grok Build release. Talk, work, the setup card and the provider copy all read it from `GROK_VERIFIED_VERSION` in `composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/providers/grok/GrokCompatibility.kt`; any other release shows **Update** on the card and cannot sign in or start a conversation.

1. Review the new release against the [source audit](source-audit.md): auth fallback, folder trust, the talk and work profiles, permission metadata, watcher replies and the `inspect --json` fields Hovel checks. Pin the audit to the new source commit and record anything that moved.
2. Set `GROK_VERIFIED_VERSION` to the new release's exact `grok --version` output. The player-facing version and messages derive from it.
3. Run the deterministic suite: `./gradlew :composeApp:jvmTest`.
4. On a Mac signed in to a test Grok subscription, with the [player's OIDC setting](#player-requirement-for-work) run every live smoke with the new binary. These send controlled model turns, and none may skip:
   ```sh
   HOVEL_GROK_SMOKE=/absolute/path/to/grok HOVEL_GROK_SMOKE_TURNS=1 \
     ./gradlew :composeApp:jvmTest --tests '*Grok*SmokeTest'
   ```
5. Refresh the evidence: rerun `python3 tools/probe-grok-initialize.py` for `initialize-<version>.json`, replace `startup-`, `talk-` and `work-<version>.json` with the new sanitized results, and update the links here and in the audit.
6. Repeat the native Mac checks under [Project work](#project-work): trust dialog, edit approval and denial, activity and Stop.
