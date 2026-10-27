# Review: launch Ember from Hovel

**Verdict:** Ready as a local desktop prototype
**Reviewed:** Pet lifecycle adapter, native bridge dispatch, shared web transport, pet control placements, launcher configuration, readiness marker, tests and documentation. All seven implementation-review passes completed.

## Blockers
None for the requested local launch path.

## Fixed in this pass
- Shared the existing native document context between games and pets. An unsupported pet action preserves that context, so older hosts cannot lose a game session through background pet checks.
- Stopped background polling after a bridge error and preserved failed-launch feedback until another explicit action.
- Reused the glass surface with existing color and spacing tokens. A stronger dark tint keeps the button legible on both the light Hovel welcome page and dark catalog.
- Removed unrelated Kotlin formatting produced by the pet build; its source change is one readiness marker.

## Notes
- One native host owns one pet. Independently launched pets are not adopted or terminated.
- The native installation and engine are configured outside the page. A separate executable preserves the existing desktop-overlay behavior and 4.7.2 engine boundary.
- The shared v1 transport remains a compatibility adapter for this checkout. Move the lifecycle methods into a v2 capability pack when the native host adopts that protocol.

## What works
- Hovel and the catalog share one injectable pet control and service.
- Readiness, cancellation, external exit, duplicate requests and shutdown have focused coverage; the actual desktop overlay passed show/hide/relaunch and host-close checks.
- Game progression and pet account/task behavior remain outside this change.

Merge recommendation: keep the documented local-prototype scope; production installation and other platforms require separate validation.
