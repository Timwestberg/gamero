# Native stage consent review

Scope: local evidence records, broker callbacks, coordinator persistence and tests.
Applied the implementation-review passes for composition, reuse, extension,
DevEx, duplication, documentation and style. No blocking findings for this local
persistence slice. Do not treat this as approval for model consumption yet.

- Reuses existing broker race handling and ConversationStore persistence.
- Pure binding/settlement logic stays in the brain domain; broker hooks default
  to no-op and preserve existing consumers.
- No execution path reads stored consent to bypass a native dialog.
- Legacy records decode with an empty evidence map; conflicting terminal replay
  and changed input digests are rejected.
- Fixed during validation: invalidated review feedback is intentionally retained
  for corrective workflow attempts. Recording it must not block that workflow;
  current-evidence lookup conservatively refuses it instead.
- Local applied answers can differ from eventual server winners while offline.
  This is documented and must be reconciled in the next checkpoint slice.

Validation: focused StageConsent, CoordinatorEngine, RemoteApprovals and
BrainTransitions suites passed. The real coordinator fixture reloads the native
store from disk; dialogs/providers are scripted, not a live QA account test.
Native compilation and whitespace checks passed. No paid inference calls.

Recommendation: retain this local slice; complete authenticated evidence sync
and live QA before using consent in Advisor decisions.
