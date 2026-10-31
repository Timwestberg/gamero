# Native stage consent

Persist the answer applied to each native stage dialog, bound to its attempt,
configuration revision and exact input digests. This is observational evidence,
not approval of a plan artifact and not permission to bypass a future dialog.

Keep Continue, Request changes, Not now, Stop and withdrawal distinct. Record
not-required only when the pinned configuration needs no stage dialog. Older
records without evidence mean unknown. Changed inputs and invalidated attempts
must not expose current consent.

This first implementation slice is local persistence. Server checkpoints,
advisor consumption and live QA remain subsequent slices of the approved
workflow provenance architecture in server-platform/docs/agent-decisions.
