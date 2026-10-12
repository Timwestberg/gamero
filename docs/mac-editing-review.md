# Mac WebView editing — FOR-79

Actual Mac trace at diagnostic commit `351142d` identifies the missing callback
path: Command editing events arrive in `onKeyEvent` (POST), with RAWKEYDOWN,
Command set, edit-key family true and editable field true. PRE is absent. The
previous candidate dispatched only in PRE, so it could not edit. Field
context-menu paste works; Command-C/V remain unaccepted until this correction
passes the actual Mac check.

## Correction

POST is the sole dispatch path. PRE, when a backend supplies it, only marks a
recognized shortcut with `isKeyboardShortcut=true` and returns false, following
the public [JCEF keyboard-handler contract](https://github.com/JetBrains/jcef/blob/master/java/org/cef/handler/CefKeyboardHandler.java).
PRE never edits. A backend delivering PRE followed by POST therefore executes
once without timing-based deduplication or stale key-history state. POST-only
backends execute the same guarded operation directly.

POST independently checks live browser, native ID plus exact hosted component,
cached AWT window activation and valid focused frame at dispatch time. PRE cannot
mark another browser's event or an inactive-window shortcut. Command-X/C/V/A/Z
and Command-Shift-Z remain the only mappings; other modifiers are left alone.
Only RAWKEYDOWN edits; KEYDOWN, CHAR and KEYUP cannot repeat it. Physical repeats
remain separate commands. Work stays synchronous on CEF's UI thread.

Global Compose accelerators remain removed. Top-menu click handling retains its
existing AWT focus guard and is a separate unaccepted path. No clipboard reads,
scripts, provider tasks, credentials or dependencies are added.

## Verification and remaining gate

Regression tests cover the observed POST-only path for all six actions, both
callbacks across repeated presses, ignored later event phases, unsupported
modifiers, browser/component identity, inactive/closed/missing browsers, invalid
frames and changing activation/browser identity between PRE and POST. Existing
menu guards and bounded diagnostic tests remain in place. Final JVM/JAR results
and independent review are recorded in PR #4.

The real Mac trace establishes callback delivery; JVM tests establish dispatch
logic, not a successful field mutation. Keep this PR draft until a coordinated
candidate test confirms one paste per press, copy/select-all/cut/undo/redo,
unrelated-control/inactive-window protection and behavior after reload. Use the
existing harmless `tools/native-edit-smoke.html`; no provider task is necessary.
Do not request another diagnostic-only attempt.

Bounded `GAMERO_EDIT_EVENT` diagnostics retain at most 16 structural shapes each
with/without Command; no raw codes, characters, text, URLs or per-keypress counts
are recorded. `GAMERO_EDIT_HANDLER` reports registration requested, not proven.
`GAMERO_EDIT_KEY` reports only action/outcome enums. DISPATCHED proves a native
call; field mutation must still be observed. No app restart, merge or deployment
is performed by the cloud worker.
