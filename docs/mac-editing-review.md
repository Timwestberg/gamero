# Mac WebView editing — FOR-79

Mac testing of the application's Compose Edit accelerators reached their callbacks
but rejected every edit with `FOCUS_OUTSIDE_BROWSER`. Field context-menu paste
works. Diagnostic-only commit `11672f3` did not correct shortcut routing.

The candidate removes global Compose accelerators and installs a public JCEF
`CefKeyboardHandler.onPreKeyEvent` on the dedicated WebView client. Browser-origin
Command-X/C/V/A/Z and Command-Shift-Z invoke the valid focused frame synchronously
on CEF's UI thread. Native browser ID plus exact hosted component identify the
wrapped browser. AWT window activation is cached on the event thread; closed,
missing, inactive, unrelated-browser and invalid-frame cases remain rejected.

Only RAWKEYDOWN is handled. CHAR, KEYDOWN, KEYUP and the post-renderer callback do
not repeat edits. Repeated physical key-down events retain normal repeat behavior.
Other modifier combinations remain browser-owned. The shortcut BoolRef stays
untouched; returning true consumes a dispatched native command. The existing
focus-guarded top-menu click path is unchanged and remains separately unaccepted.
No clipboard reads, scripts, provider tasks, credentials or dependencies are added.

Source evidence:

- [JCEF public keyboard handler](https://github.com/JetBrains/jcef/blob/master/java/org/cef/handler/CefKeyboardHandler.java)
  defines browser origin, UI-thread callbacks and consume-on-true semantics.
- [Chromium 124 Mac event builder](https://github.com/chromium/chromium/blob/124.0.6367.60/content/common/input/web_input_event_builders_mac.mm)
  and the [current builder](https://github.com/chromium/chromium/blob/main/components/input/web_input_event_builders_mac.mm)
  construct RAWKEYDOWN for native key-down events.
- [CEF conversion](https://github.com/chromiumembedded/cef/blob/master/libcef/browser/browser_event_util.cc)
  preserves this event type. Installed JCEF/KCEF 2024.04.20.4 bytecode confirms the
  public APIs and wrapper delegation; WebView 2.0.3 installs no keyboard handler.

Validation: JVM tests and JAR build pass (50 tests, zero failures/errors/skips).
Independent source review found no blockers. Regression cases cover all six
commands, wrapper/native identity, event sequences/repeats, unsupported modifiers,
other browsers/components, deactivation/reactivation, shutdown and invalid frames.
Existing top-menu focus-guard tests remain intact.

Actual Mac delivery and exactly-once editing remain unverified. Keep PR #4 draft.
When the coordinator approves a candidate retest, use `tools/native-edit-smoke.html`
with harmless sample text: shortcut paste inserts once; copy/select-all/cut/undo/
redo work; unrelated native controls and inactive windows cannot edit the browser;
repeat after reload. Test top-menu clicks separately. `GAMERO_EDIT_KEY` contains
only action/outcome enums; DISPATCHED proves a native call, not a changed field.
No app launch, merge or deployment is part of this source validation.
