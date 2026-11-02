# Stage consent evidence

Use `run.currentStageConsent(attemptId)` to inspect matching local evidence.
Null means unknown or stale. This API must never authorize work.

The record survives native disk persistence. Contract 5 now emits authenticated
workflow evidence checkpoints when the server claim advertises support. Advisor
consumption remains disabled. An applied Mac answer can differ from a later server winner
when offline; the server checkpoint handler classifies mismatched answers as unknown.

Original dialogs, native ordering, provider dispatch and Jev/Laya settings stay
unchanged. No paid model calls are needed to test local persistence.
