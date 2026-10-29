# Grok Build integration

Development companion chat and project work are implemented for **Grok Build 1.0.70 (f50904eaee6c)**. Launch Hovel with `HOVEL_GROK_PROVIDER=1`; optionally select an absolute executable with `GAMERO_GROK_PATH`. Release and unmarked builds keep Grok off. Project work has a separate development opt-in, `HOVEL_GROK_WORK=1`. Grok Bot remains unavailable.

## Sign in and chat

Use Manage subscriptions → Grok → installation instructions or **Sign in to Grok Build**. The explicit button opens the installed runtime's `grok login` in Terminal. Return to Hovel and choose **Check again**. Hovel checks the runtime without a model prompt or interactive login. A verified personal OIDC subscription shows **Connected** and supplies its model catalog. A saved credential without verified access shows **Sign-in detected**. It does not ask you to sign in repeatedly.

Add Grok to a companion's team through the existing member picker. Conversation history, switching, bounded handover, Stop and explicit Retry use the existing `DesktopChat` flow. Status cannot establish that a future remote request will succeed; the adapter rechecks account access and isolation before each prompt. Unsupported versions or managed policies remain unavailable.

## Native composition

```kotlin
val connection = GrokConnection(environment)
val provider = GrokProvider(connection)
val talk = GrokChatRuntime(providerRoot, environment, connection)
val work = GrokWorkRuntime(providerRoot, environment, connection, broker, NativeGrokWorkTrust(dialogs))
```

`DesktopServices` registers these only behind the development gate. No Grok-specific web chat transport is needed. `GrokConnection` owns no-prompt subscription/model checks; `GrokLaunch` owns isolation; `GrokTalkSession` owns one ACP session; `GrokChatRuntime` adapts existing turn and summary interfaces. Pure protocol/account/tool parsers and the private binding store are independently tested.

Every chat process uses a fresh private HOME/GROK_HOME and the supported `GROK_AUTH_PATH` reference to the runtime-owned login. Hovel never reads or copies credential contents. Only provider session data survives through a native-owned directory link; configuration, downloaded bundles and preference changes are discarded after process teardown. Chat launches do not modify the user's Grok config. See the [source audit](source-audit.md) for the profile, reload exclusion and narrowly accepted internal watcher replies.

Only Hovel UUID handles enter shared native history. Sidecars bind the raw provider ID to account fingerprint, profile, companion, concrete model and mode. Persona/model changes or missing mappings can request context recovery before submission. Foreign/corrupt mappings and ambiguous transport failures never automatically replay a prompt. Bounded orphan cleanup uses all readable histories plus active handles; incomplete snapshots delete nothing. Summary sessions are disposable.

## Validation

[Sanitized live evidence](talk-1.0.70.json) records subscription access, no-tool sessions, streamed replies, restart/resume and terminal cancellation. It contains no identity, session ID, credential, prompt or response content. The official source and installed binary have different revision identifiers; source claims are separately pinned in the audit.

```sh
# Local fake peers, bridge/provider/history and approval regressions.
./gradlew :composeApp:jvmTest --tests '*Grok*' --tests '*bridge*' \
  --tests '*ChatHistoryTest*' --tests '*DesktopChat*' --tests '*Provider*' --tests '*approvals*'

# Explicit live subscription opt-in; these flags permit controlled model turns.
HOVEL_GROK_CHAT_SMOKE=/absolute/path/to/grok \
HOVEL_GROK_TALK_SMOKE=/absolute/path/to/grok \
HOVEL_GROK_LIFECYCLE_SMOKE=1 \
HOVEL_GROK_ACP_SMOKE=/absolute/path/to/grok \
  ./gradlew :composeApp:jvmTest --tests '*Grok*' --tests '*Provider*' --tests '*bridge*'
```

The live selection passed with no skips; the broader regression selection skips only explicit runtime/audio checks. The older initialization-only probe remains available as `python3 tools/probe-grok-initialize.py /absolute/path/to/grok`; it uses an empty home and does not authenticate or prompt. Its results are historical transport evidence, not the current readiness implementation.

The work continuation passed its focused regression and two separate real adapter smoke tests. Native Mac acceptance passed working-role setup, project trust, exact edit approval/denial, activity, Stop, Close chat, quit with a pending approval, and a fresh work reply after restart. See the work evidence below.

## Project work

Enable both development flags, select Grok in a companion's team, choose its project folder, and select Work. Hovel uses its existing native start confirmation, activity feed, Stop, history and handover flow.

Work runs in the player's own environment with their login-shell `PATH` (Grok's folder first), minus inherited `XAI_*` and `GROK_*` variables. It preserves the selected HOME/GROK_HOME and uses the runtime-owned credential reference. It requires the [player's OIDC setting](#player-requirement-for-work); a bounded TOML parser checks the actual setting, and Hovel never writes it. Runtime inspection accepts ordinary user preferences, skills and project instructions, and refuses extra configuration layers, hooks/plugins/MCP/LSP, managed policy, custom models/endpoints/auth providers, config overrides and command prefixes. Unsupported configurations remain unavailable rather than being silently ignored.

An untrusted project gets a separate native **Trust project in Grok** question. It names the project folder chosen in Hovel and the folder Grok will trust, says plainly when Grok trusts an enclosing folder, and explains that Grok remembers trust for future runs outside Hovel. When Grok's trust root is the home folder, a folder above it, or `/`, work does not start and no question is asked. After trusting, Grok must report exactly the confirmed folder. Not now, dismissal and Stop do not grant trust. Only confirmation invokes Grok's own `--trust inspect --json`, followed by a fresh inspection before starting the agent. Test trust grants use a disposable provider home; credentials are referenced, never copied.

The per-run work profile appends the working persona and retains project rules. Its exact allowlist is read_file, list_dir, grep, search_replace and run_terminal_cmd. In the pinned runtime, a nonempty allowlist without Agent directives also forces shell backgrounding off after user/remote parameter merges. MCP, subagents, goals and workflows are not offered. Commands/edits go through Grok's tools and the existing native approval broker. **Allow once** returns the exact verified option; **Deny** never becomes a persistent grant. A denied operation can make Grok return `cancelled`; Hovel shows that work stopped because approval was not given.

One process owns one turn. The next turn resumes its private provider session only after the prior process is reaped. Work bindings have a separate directory and include canonical workspace, account, persona and model ownership. Stop/release/close cancel visible and queued approvals immediately, request ACP cancellation, then force local teardown after five seconds if necessary. Unconfirmed cleanup disables further work in that runtime. Activity retains bounded category/path summaries, never raw command output or reasoning.

[Work acceptance evidence](work-1.0.70.json) distinguishes protocol, production-adapter and native-screen checks. The production adapter has passed edit allow/deny, repeat permissions, project-rule loading, activity and restart/resume. Real Stop/release/close checks observed a shell and sleep child, then confirmed both exited without the final write. A command remained foreground beyond 35 seconds without timeout/background parameters. This validates disabled runtime backgrounding; it is not a guarantee for arbitrarily self-detaching or remote commands.

```sh
# Controlled production-adapter turns; only disposable files/commands are approved.
HOVEL_GROK_WORK_ADAPTER_SMOKE=/absolute/path/to/grok \
HOVEL_GROK_WORK_CLEANUP_SMOKE=/absolute/path/to/grok \
  ./gradlew :composeApp:jvmTest --tests '*GrokWorkRuntimeSmokeTest'

# Deterministic regressions, with unrelated live checks skipped unless explicitly enabled.
./gradlew :composeApp:jvmTest --tests '*Grok*' --tests '*approvals*' \
  --tests '*DesktopChat*' --tests '*DesktopServicesTest*'
```

The older direct protocol probes remain available through `HOVEL_GROK_WORK_AUTH_SMOKE`, plus `HOVEL_GROK_WORK_PERMISSION_SMOKE=1` and `HOVEL_GROK_WORK_LIFECYCLE_SMOKE=1`. See the [source audit](source-audit.md). Mixed-provider Work handover passes through the production coordinator and native UI, and explicit Retry passes (see [Profile and Retry lifecycle](#profile-and-retry-lifecycle)). Release activation, unsupported integrations, arbitrary detached/remote work, actual authenticated profile switching and modal-approval Stop remain separate acceptance work.

### Player requirement for work

Grok work signs in only with the player's Grok account, never an API key. Grok has no per-process way to pin that, so the player adds this to their own Grok config (`$GROK_HOME/config.toml`, by default `~/.grok/config.toml`):

```toml
[auth]
preferred_method = "oidc"
```

This setting is global: it also changes how Grok Build signs in outside Hovel, for every project, until the player removes it. Hovel never writes or edits this file; when the setting is missing, work readiness names the exact setting, the file and this effect. Companion chat does not need it, because each chat process pins its own disposable config.

## Integrated main acceptance

The feature is combined with main's usage/model-effort interfaces, and the matching contract and web changes include the current Brain editor. Grok usage has no reported windows, so the existing UI shows Unavailable. The native Codex adapter now ignores threadless account notifications after handling transport/security events; a failing-before/passing-after regression and native context handover verify the fix.

The native suite passes with only opt-in checks skipped; contract tests, focused web and Storybook tests, typecheck and contract/Kotlin fixture parity pass. Six broad-web failures also reproduce on the main baseline and remain outside this feature. The macOS package was rebuilt and launched with the matching sources.

```sh
# Live Work handovers; requires signed-in providers and the supported OIDC Grok config.
# Uses a disposable project/config home; only the exact test edit is approved.
HOVEL_GROK_HANDOVER_SMOKE=/absolute/path/to/grok \
HOVEL_CLAUDE_HANDOVER_SMOKE=/absolute/path/to/claude \
  ./gradlew :composeApp:jvmTest --tests '*GrokHandoverSmokeTest'

# Live Codex readiness/configuration, without a model turn.
HOVEL_CHAT_CODEX_SMOKE=/absolute/path/to/codex \
  ./gradlew :composeApp:jvmTest --tests '*DesktopChatSmokeTest'
```

The real coordinator test verifies Grok Work → Claude Work → Grok Work, edited-file work logs, read activity and retained conversation context. Native Mac checks also completed Grok/Claude Work handovers, Grok → Codex talk with a context-based reply, and a return to Grok. Codex Work stays disabled. A separate fake-process test verifies profile-switch cancellation, late-approval rejection and history/workspace isolation; it does not claim an authenticated profile-switch UI pass. See [sanitized evidence](work-1.0.70.json). Release gates remain closed.

## Profile and Retry lifecycle

```sh
# Real Grok, two disposable Hovel profile leases, no Hovel account-service dependency.
HOVEL_GROK_PROFILE_SMOKE=/absolute/path/to/grok \
  ./gradlew :composeApp:jvmTest --tests '*GrokProfileSmokeTest'

# Dropped-turn Retry, duplicate suppression and pending-approval cancellation.
./gradlew :composeApp:jvmTest --tests '*GrokWorkRuntimeTest' --tests '*approvals*'
```

The real profile check passes cancellation and process cleanup, rejects a late Allow, keeps histories/workspaces separate, and restores the first profile's context. It references the existing credential store and grants trust only in a disposable configuration home. It does not authenticate Hovel accounts. Native Retry after a forced work-process failure also passes: reconnect does not resend, explicit Retry obtains a real reply, and the saved user message is not duplicated.

Native dialogs now use modeless presentation so the main chat can receive Stop/Close. The desktop Stop-with-approval-open run is still unverified: its edit was applied and the action taken needs clarification. Authenticated Hovel account-switch UI acceptance requires the currently unavailable local identity service and test accounts. Release gates remain closed.

## Bumping the supported Grok version

Hovel accepts exactly one Grok Build release. Talk, work, the setup card and the provider copy all read it from `GROK_VERIFIED_VERSION` in `composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/providers/grok/GrokCompatibility.kt`; any other release shows **Update** on the card and cannot sign in or start a conversation.

1. Review the new release against the [source audit](source-audit.md): auth fallback, folder trust, the talk and work profiles, permission metadata, watcher replies and the `inspect --json` fields Hovel checks. Pin the audit to the new source commit and record anything that moved.
2. Set `GROK_VERIFIED_VERSION` to the new release's exact `grok --version` output. The player-facing version and messages derive from it.
3. Run the deterministic suite: `./gradlew :composeApp:jvmTest`.
4. On a Mac signed in to a test Grok subscription, run every live smoke with the new binary. These send controlled model turns:
   - Talk: `HOVEL_GROK_CHAT_SMOKE`, `HOVEL_GROK_TALK_SMOKE` with `HOVEL_GROK_TALK_PROMPT_SMOKE=1` and `HOVEL_GROK_LIFECYCLE_SMOKE=1`, and `HOVEL_GROK_ACP_SMOKE`.
   - Work: `HOVEL_GROK_WORK_AUTH_SMOKE` with `HOVEL_GROK_WORK_PERMISSION_SMOKE=1` and `HOVEL_GROK_WORK_LIFECYCLE_SMOKE=1`, `HOVEL_GROK_WORK_ADAPTER_SMOKE`, `HOVEL_GROK_WORK_CLEANUP_SMOKE` and `HOVEL_GROK_PROFILE_SMOKE`.
   - Handover: `HOVEL_GROK_HANDOVER_SMOKE` with `HOVEL_CLAUDE_HANDOVER_SMOKE`.
5. Refresh the evidence: rerun `python3 tools/probe-grok-initialize.py` for `initialize-<version>.json`, replace `startup-`, `talk-` and `work-<version>.json` with the new sanitized results, and update the links here and in the audit.
6. Repeat the native Mac checks under [Project work](#project-work): trust dialog, edit approval and denial, activity, Stop and Close chat.
