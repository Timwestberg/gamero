# Native consent validation

Run from gamero:

```sh
./gradlew :composeApp:jvmTest --tests '*StageConsentTest' --tests '*CoordinatorEngineTest' --tests '*RemoteApprovalsTest' --tests '*BrainTransitionsTest'
```

Focused tests cover every applied stage option, withdrawal, legacy decoding,
serialization, identical replay, conflicting replies, changed digests,
invalidation and wrong dialog IDs. The coordinator fixture runs a real engine
with scripted providers/dialogs and reloads ConversationStore from disk to check
that the persisted ID matches the actual approval checkpoint.

Live acceptance is not yet performed. For the later integrated QA run: open a
stage dialog on the local QA companion; verify pending identity, answer Not now,
restart, then resume with a new dialog and Continue. Confirm the evidence tracks
the respective attempts/inputs, that no specialist starts before Continue and
that no Jev result bypasses approval. Include phone-wins and offline-conflict
cases before calling the evidence server-verified.

Result: all four focused suites passed, including the workflow-return regression
and coordinator store-reload case. Kotlin compilation passed. This is automated
native coverage, not live QA or a production rollout.
