# Review: web catalog to embedded companion game

**Verdict:** Ready as a local development prototype
**Reviewed:** native game adapter, catalog runtime selection, same-window mounting/return, launch packaging and feature docs. Earlier progression and character work are outside this review.

## Blockers
None for the approved local test path. This does not approve production packaging.

## Fixed in this pass
- Native code validates an installed game ID and rejects paths or extra fields.
- Window close detaches and stops the game before Compose disposes its peer; the fallback checks that the Canvas is still displayable. The native crash was reproduced before correcting this order. Retesting with the game attached confirmed clean app, helper and server shutdown.
- Existing browser content is hidden and retained, then restored; returning does not recreate its session.
- Back to games uses a Compose layer so it remains visible above Godot.
- Packaging preserves the installed JBR Chromium frameworks; the dev server runs as an owned process with cleanup.
- Web launch responses are bounded and validated, and plain browsers display an actionable error.

## Notes
- This native checkout predates the shared v2 host bridge. The documented v1 adapter is temporary compatibility work; migrate to a games capability pack when the shared native kit is integrated. Adding a new native game currently requires a native allowlist/configuration entry as well as a catalog record.
- The catalog fixture is explicit local data. Production catalog publication and native result-service integration are separate work.
- Existing CALayerHost and Godot editor embedding limits still apply.

## What works
- The existing glass catalog opens the existing embedded game and Kotlin screens in one window.
- The standalone development entry point reuses the same surface and progression transport.
- Automated validation and the actual native launch/upgrade/review/return path passed with isolated test progress.

Merge recommendation: keep this bounded to the local prototype and retain the documented bridge and packaging migration requirements.
