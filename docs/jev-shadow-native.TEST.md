# Jev native shadow acceptance

The opt-in `JevNativeSmokeTest` consumes one queued local QA conversation through
real HTTP transport, CoordinatorEngine and the signed-in Codex talk runtime. It
asserts ANSWERED and fails if approval or folder selection is requested. It does
not test desktop window rendering. Jev observations are checked separately in
the server metadata log and durable receipt database.

Create a temporary QA host, companion, project and conversation with assigned
talk specialists and a queued message. Use a loopback gateway with the owner
explicitly allowlisted for shadow observations. Put settings in a private file
(mode 600); never commit credentials:

```json
{
  "baseUrl": "http://127.0.0.1:3017/v1/hovel",
  "ownerId": "QA owner UUID",
  "hostId": "temporary host UUID",
  "hostToken": "temporary host token",
  "conversationId": "queued conversation UUID",
  "codex": "/absolute/path/to/codex"
}
```

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
HOVEL_JEV_NATIVE_SMOKE=/path/to/private-settings.json \
./gradlew :composeApp:jvmTest --tests '*JevNativeSmokeTest' --rerun-tasks
```

This uses the current Codex account and can incur Jev charges. Without the
settings variable the test is skipped. The test only accepts HTTP on 127.0.0.1.
Afterward revoke the temporary host, remove the temporary companion, stop isolated
services and delete the private settings. Preserve paid receipts for replay safety.

On 2026-10-30, a code-review conversation delegated Reviewer and answered; Jev
independently chose the same specialist. A second greeting answered while
reaction and notification observations deferred on low confidence. Regression
coverage passed 29 CoordinatorEngine and 37 ConversationRunner tests.

See the sibling server-platform `docs/agent-decisions/agent-decisions.TEST.md`
for live receipt IDs, timings, configuration and remaining acceptance boundaries.
