# Mac WebView editing — FOR-79

Actual Mac validation found that Command-V did not paste into a web text field.
The desktop window had no Edit menu. The host now supplies standard Mac menu
accelerators and invokes public JCEF focused-frame editing operations. This is
shared desktop behavior, independent of Hovel and the native capability bridge.

Commands require a live browser, current AWT focus inside its component, and a
valid focused frame. The browser reference is cleared during resource shutdown.
The host does not read or poll the clipboard, inject script, or add dependencies.

Independent source review found no blockers. Six regression tests cover all six
commands, descendant focus, unrelated/missing focus, lifecycle closure, invalid
frames and absence of a browser. JVM validation is recorded in the PR.

Actual Mac validation is still required. Serve `tools/native-edit-smoke.html`
through the existing local fixture server, then open it with the existing verified
JBR launch flow. With harmless sample text, check Command-V and Edit → Paste each
insert once, then copy/select-all/cut/undo/redo, focus in another app, and reload.
Repeat normal paste into the Hovel Task field without submitting another task.
Unit tests do not establish native accelerator consumption or Mac focus ownership.

No merge or deployment is authorized by this review.

## Mac retest and focus correction

The first menu implementation failed the actual Mac shortcut check: Command-V
and Command-C did not edit, while the top Edit menu reacted. A different paste
method was reported to work; top-menu Paste versus context-menu Paste was not
established. A successful fixture HTTP load does not prove editing success.

The revised guard permits AWT's permanent browser focus when current focus is
null or temporarily in a menu, and requires the browser's window to remain active.
It never falls back over another native control. The valid focused frame returned
by JCEF remains the editing target during temporary menu focus. Bounded
`GAMERO_EDIT_ACTION` diagnostics report only action/outcome enums, never clipboard,
field, URL or credential contents. If no line appears for a shortcut, menu callback
routing is still failing; `DISPATCHED` proves only the native command call, not an
inserted value. Other outcomes identify the rejecting guard.

This is a source-level correction of temporary-focus handling, not a confirmed
explanation of the observed Mac failure. Repeat shortcut and top-menu actions
separately, confirm exactly one edit, and capture only these bounded diagnostics.
