# Native consent persistence

`RunRecord.stageConsents` is a default-empty map from attempt ID to
`StageConsentRecord`. Each record binds run ID, configuration revision, step,
attempt, input IDs/digests, broker approval ID, state and applied option/surface.
Its states are NOT_REQUIRED, PENDING, ANSWERED and WITHDRAWN. An absent record
means unknown. Contract 6 negotiates publication through workflow evidence checkpoints.

`RemoteApprovals.ask` has optional open/settled callbacks. The coordinator uses
these only for stage dialogs. Opening persists the actual broker ID before the
dialog; settling persists the applied race result before the caller dispatches,
pauses or stops. Existing calls retain default no-op callbacks.

The pure transitions reject mismatched IDs, changed inputs, invalidated attempts and
conflicting terminal answers; identical replay is idempotent. A new dialog for
the same attempt replaces its latest evidence. `currentStageConsent` refuses
stale input/configuration/attempt bindings. Returned review feedback remains
valid execution input, but evidence with an invalidated source is not exposed
as current consent. History remains observational and is
never consulted to skip a dialog. Disk writes use the existing coordinator change
and ConversationStore path.

The broker can apply a local answer while offline. This record describes that
applied answer, not a guarantee that the server agrees. Before Advisor observation, the server reconciles the actual approval ID with
authenticated evidence and classifies unresolved/conflicting receipts as unknown.
Observations remain shadow-only. A crash before settlement leaves pending
evidence; recovery must ask again.
