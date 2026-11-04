# Run the Mac desktop foundation

Use this guide to build and open Gamero's Compose/JVM WebView host. Registering
the Mac and running conversations are in [hovel-companion.md](hovel-companion.md).
The legacy mobile bridge is disabled on desktop.

## Prerequisites

- macOS 13 or later, Apple Silicon (the current Compose supported Mac target).
- JDK 21 or 22 and network access for Gradle/Maven dependencies.
- A trusted web application URL; HTTP is allowed only on loopback for development.

From the repository root:

```sh
./gradlew :composeApp:jvmTest :composeApp:jvmJar --console=plain
```

### Run Hovel

Set `GAMERO_PRODUCT=hovel`. Without it the app runs as the games
product: the companion pack and the conversation runner are off, and Connect
computer stays disabled. The full Hovel launch:

```sh
# game-platform: serve the app over loopback HTTP on 8133 (GAMERO_CATALOG_PORT changes it).
# JCEF rejects the dev server's basic-ssl certificate, so use this launcher, not the HTTPS dev server.
(cd <game-platform>/apps/game-bridge-host && node --import tsx tools/companionLauncher.ts)

# gamero, from the repository root (Gradle on JDK 21):
GAMERO_PRODUCT=hovel \
GAMERO_WEB_URL=http://127.0.0.1:8133/hovel \
GAMERO_BROWSER_JAVA_HOME="<JBR with JCEF>/Contents/Home" \
HOVEL_GAMES_ROOT=<game-platform>/games \
HOVEL_EMBED_LIBRARY="$PWD/build/embedded-godot/libhovel_embed.dylib" \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :composeApp:run
```

`HOVEL_GAMES_ROOT` and `HOVEL_EMBED_LIBRARY` turn on the embedded games
(Hovel Companions, Ember Tower, Ember: Overclock); build the library with
`scripts/build-embedded-macos.sh`. Without them `games.launch` answers
`unsupported`. A launch with a companion writes its `EmberProfile` to
`~/Library/Application Support/Gamero/companions/profiles/<companion id>.json`
and passes the path in `HOVEL_EMBER_PROFILE`. Every setting is in
[Desktop companion settings](companion-presentation/README.md#desktop-companion-settings).

`GAMERO_CODEX_PATH` is optional; without Codex the Mac runs conversations on the
runtimes it has and takes no memory jobs.

For launch, set `GAMERO_BROWSER_JAVA_HOME` to an independently installed and
verified JetBrains Runtime with JCEF (`Contents/Home` on Mac). Keep Gradle's
`JAVA_HOME` on JDK21. The current official JBR/JCEF release inspected for validation
is `jbr-release-25.0.4.1b635.72`; the Mac validator must verify its provenance and
record the actual Chromium user agent. Gamero does not download native browser
binaries, remove quarantine, or bypass macOS security prompts.

The window first shows the destination and Continue. Continue initializes the
installed browser. Setup failures remain visible. Browser cache lives beneath
`~/Library/Application Support/Gamero/web-cache`. The wrapper resolves KCEF/JCEF
API `2024.04.20.4`; compatibility with the selected installed runtime remains a
Mac validation gate. Closing waits off the UI thread for up to 15 seconds, then
explicitly exits with failure if native initialization/shutdown stalled.

The app never signs in to Codex or any other runtime. Do not use native launch
alone as Hovel conversation or reconnect acceptance; follow
[hovel-native-validation.md](hovel-native-validation.md).

### Project workspace (development builds)

A development build (`-Dgamero.dev=true`: the local jpackage launchers, the plain
`JavaExec` entry points such as `runCompanions`, and Compose's `run` or a package only with
`-Pgamero.developmentPackage=true`; see below) can open a project workspace window: a
native terminal with a Shell tab in a folder (`jvmMain/.../workspace/`; spec and
architecture in game-platform `docs/launcher/project-workspace.*`). Release builds never
offer it, and do not ship its natives. A development package ships them signed, for
Apple Silicon only ([release.md](release.md#desktop)).

- **Develop → Open Workspace…** in the main window picks a folder.
  `HOVEL_WORKSPACE_FOLDER=<folder>` opens one at launch instead. The first open of each
  folder asks first, and **Develop → Forget Trusted Folders** makes every folder ask again.
  See [Folder trust](#folder-trust).
- **Shell → New Tab** (⌘T) and **Close Tab** (⌘W). The window's **Develop** menu, or
  **+**, opens Neovim, lazygit or yazi in a tab, started through the login shell.
- **Window**: ⌘1 to ⌘8 select a tab and ⌘9 the last one; **Show Next Tab** (⌘⇧], also
  ⌃Tab) and **Show Previous Tab** (⌘⇧[, also ⌃⇧Tab) wrap around. A tab's name follows the
  title its program sets, and falls back to its kind (Shell, Neovim). See
  [Shortcuts](#shortcuts).
- **View → Bigger** (⌘+ or ⌘=), **Smaller** (⌘−) and **Default Font Size** (⌘0) zoom
  every tab of that window, from 9 to 36 points. The font is SF Mono 13 pt when AWT can see
  it by name, else Menlo 13 pt. On macOS 26, neither JDK 21 nor the JBR 21 sees SF Mono, so
  it is Menlo; the probe log names the font in use.
- **As in Terminal**, Option types characters (⌥e then e is é; ⌥← and ⌥→ still move by
  word), and the cursor does not blink. See [The terminal](#the-terminal).
- **Nothing is lost without asking.** Closing a tab (⌘W or ×), closing the window, and
  quitting (⌘Q, or closing the main window) ask first, in a sheet on the workspace window,
  when a tab is busy ([Busy checks](#busy-checks)), and name what runs. With nothing busy
  they close, or quit, at once. Quitting asks once for every window, then runs the app's
  normal shutdown. See [Confirmation sheets](#confirmation-sheets) and [Quitting](#quitting).
- `HOVEL_WORKSPACE_BIN=<folder>` is the folder kept first in each tab's `PATH`
  (default `~/Library/Application Support/Gamero/workspace/bin`, where the tool
  manager will put its wrappers). A tool tab runs that folder's wrapper when it has
  one, otherwise the player's installed tool.
- `HOVEL_WORKSPACE_SHELL=<path>` tries another shell in place of the login shell.
- `HOVEL_WORKSPACE_PROBE=<steps file>`, with `HOVEL_WORKSPACE_FOLDER`, drives the window
  from a script and writes screen text and images beside the file, for checks with no
  one at the screen (`WorkspaceProbe` lists the steps). Start the file with `answer yes`
  to answer the folder's trust question.

Compose's `run` task is a development build only with `-Pgamero.developmentPackage=true`
(without it the app starts with no workspaces):

```sh
HOVEL_WORKSPACE_FOLDER=<folder> HOVEL_WORKSPACE_PROBE=<steps file> \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :composeApp:run -Pgamero.developmentPackage=true
```

Hovel's shell startup files go to `~/Library/Caches/org.formulagen.wrapper/workspace/shell/`.
The fish integration test runs only when `HOVEL_TEST_FISH` names a fish executable: fish's
own `fish-<version>.app.zip` release, unzipped anywhere, has one at
`fish-<version>.app/Contents/Resources/base/usr/local/bin/fish`, with nothing installed. The
Neovim tests use `HOVEL_TEST_NVIM`, `/opt/homebrew/bin/nvim` or `/usr/local/bin/nvim`, and skip
without one.

#### Folder trust

Opening a workspace starts the player's login shell in the folder, and with it their
startup files, prompt and git-aware tools, which can run settings stored in the folder, such
as a cloned repository's `.git/config` `core.fsmonitor`. That is what `cd` does in Terminal,
but here a click starts it, and later a page's `projectWorkspace.open`. So
`WorkspaceWindows.open`, which every entry point goes through, asks the first time each
folder opens, as VS Code's Workspace Trust does:

- The question names the folder and its full path, as plain text
  ([Confirmation sheets](#confirmation-sheets)), with Cancel the default. No workspace
  window exists yet, so it is application-modal.
- **Open Workspace** remembers the folder by its real path (`toRealPath()`), so a link to a
  trusted folder is trusted, then opens it. Cancel opens nothing. Bringing forward a window
  already open never asks, and a second open of a folder while its question is up is
  ignored.
- The answers are in `~/Library/Application Support/Gamero/workspace/folder-trust.json`,
  `{"version":1,"folders":[…]}` oldest first, written with `writeDurably` in a private
  folder. It is read afresh each time, since other copies of the app may share it. A file
  that is missing, damaged, past `TRUSTED_FOLDERS_BYTES` or at another version trusts no
  folder, is logged, and is replaced at the next Open Workspace. The oldest folders go when
  the file would pass that guard.
- The list is per Mac, not per account: it is about this Mac's folders and this user's
  shell. Tests use an in-memory `FolderTrust`, never the file.
- **Develop → Forget Trusted Folders** deletes the file. The profile's workspace settings
  will own this.
- **The probe** opens `HOVEL_WORKSPACE_FOLDER` at its first step other than `answer` and
  `wait`, so an `answer yes` before it answers the question; otherwise the sheet asks. A
  folder the probe opens is remembered like any other.

#### Busy checks

`Session.busy()` answers what closing a tab would end, as `Busy` data, or null. By tab kind:

- **Shell**: busy while the terminal's foreground process group (`tcgetpgrp` on the
  pseudo-terminal) is not the shell's own: a foreground job (`npm run dev`). Also busy while
  any descendant is stopped (`proc_pidinfo` with `PROC_PIDT_SHORTBSDINFO`, `pbsi_status` is
  `SSTOP`): a job suspended with ⌃Z (“vim notes.txt” is suspended). That flavor also reads
  another user's process, such as a backgrounded `sudo`. A descendant that is gone or a
  zombie (`ESRCH`, though the JDK still calls a zombie alive) is not stopped. Running
  background jobs and prompt helpers (powerlevel10k's `gitstatusd`, zsh-async workers) are
  never stopped and never count. A shell replaced by `exec` with a program that is not a
  shell is that program, by name (`/bin/sh` re-executes another shell on a Mac); one whose
  command line cannot be read, such as a setuid `exec sudo -s`, is "a program". When the
  terminal cannot be asked, any live descendant counts.
- **Neovim**: busy while a buffer is modified (as `:qa` would refuse) or its `:terminal` runs
  a job. Each Neovim tab listens on a socket in
  `~/Library/Caches/org.formulagen.wrapper/workspace/nvim/`, and Hovel asks with a fixed
  `nvim --clean --headless --server … --remote-expr …`, using the running Neovim's own
  executable (by real path) and an environment of only `HOME` and the system `PATH`
  (`/usr/bin:/bin:/usr/sbin:/sbin`), nothing of Hovel's own or the tab's. The tab's process
  is asked when it is Neovim; otherwise a Neovim descendant (a wrapper that does not `exec`).
  With no Neovim in the tab, a tab whose process is still a shell (by name) is not busy: the
  login shell's startup helpers (`path_helper`, `fnm env`, `rbenv init - zsh`) run before it
  `exec`s Neovim, and nothing can be unsaved yet. Any other process with no Neovim gets the
  tool rule. No answer within a second counts as busy. Neovim's LSP servers and its
  `nvim --embed` server are descendants, so the tool rule cannot be used for it.
- **Other tools** (lazygit, yazi): busy while a live descendant runs something other than
  the tool's own executable (by real path), such as `git push`.

Any check that fails, or cannot be read, counts as busy. A check takes about 3 seconds at
most, when Neovim does not answer. The tab bar marks a busy tab with ●: it asks each tab
every second, and a Neovim tab only after input or output, or every fifth second, since
each ask starts a Neovim client. Closing and quitting always ask afresh.

#### Shortcuts

A key press in a terminal that is a window shortcut goes to the window, not the terminal:
⌘T, ⌘W, ⌘1 to ⌘9, ⌘⇧] and ⌘⇧[ (also ⌃Tab and ⌃⇧Tab, as in Terminal), ⌘+ (⌘= too), ⌘− and
⌘0, and the `}`, `{` and `+` a ⌘ key types on other layouts. Only key presses count; their
typed events are dropped. The menu items carry the same shortcuts (`menuKey`). macOS also
hands a ⌘ key to the menu bar after the terminal has seen it, so a menu item that repeats
the action a key press performed within 500 ms is taken as that echo and ignored.

#### The terminal

`HovelTerminalPanel` is JediTerm's panel with these changes:

- **Off-screen painting.** JediTerm paints while holding its text buffer's lock, and a paint
  straight to the screen can wait on the Metal pipeline: the JDK presents each frame on the
  AppKit thread under the AWT lock, and waits up to a second for a drawable while the window
  is not on screen (the Mac is locked, say). The emulator, and through back-pressure the
  tab's own process, would wait with it. So JediTerm paints into an image in memory at the
  screen's scale, which never waits on the screen, and the image goes to the screen after the
  lock is released. A tab that is not showing drops its image.
- **Guards.** Its AWT-thread entry points (event handlers, paint) catch and log: an exception
  escaping to the event thread reaches the app's default handler, which ends Hovel.
  JediTerm's own repaint timer (`TerminalPanel.myRepaintTimer`: cursor, scrolling, repaint)
  catches only the repaint call, and no override reaches it, so its listeners are
  re-registered wrapped. The listener stays JediTerm's weak one, so the panel can still be
  collected. A failure on every tick is logged once in full, then as one count a minute. If a
  JediTerm update renames the field, the guard logs and leaves the timer as it is, and
  `TerminalTimerGuardTest` fails.
- **Shortcuts and a steady cursor.** Window shortcuts go to the window ([Shortcuts](#shortcuts)).
  The cursor's period stays JediTerm's 505 ms, and every shape is drawn steady
  (`steadyCursor`): JediTerm reads `CSI 0 q` as a blinking block, and a period of 0 would
  repaint a focused idle tab on every timer tick.
- **Safe pasting.** See [Pasting](#pasting).

#### Pasting

JediTerm 3.76's paste (`TerminalPanel.pasteFromClipboard`, private) takes the clipboard's
text from `TerminalCopyPasteHandler.getContents`, turns `\r\n` into `\n` and `\n` into `\r`,
and wraps it in bracketed-paste markers when the program turned that mode on. It strips
nothing. So pasted text holding `ESC[201~` ends bracketed paste early and the rest runs as if
typed ("pastejacking"), and without bracketed paste any multi-line paste runs line by line.
`HovelTerminalPanel` changes that:

- **Strip.** Pasted text loses ESC and every other C0 control except tab, newline and carriage
  return, plus DEL and the C1 controls (`pasteText`). `createCopyPasteHandler()` returns a
  `CleanPasteHandler` over the clipboard, so JediTerm's own reads are cleaned too. Copying is
  unchanged.
- **Ask.** The panel follows bracketed-paste mode (`setBracketedPasteMode`). When the cleaned
  text has a line break and the mode is off, it asks first, in a sheet on the workspace
  window: "Paste 2 lines into Shell?", naming the tab by its kind, never by the title its
  program set. Cancel, the default, pastes nothing. With the mode on, the paste is wrapped
  and sent at once, as the program asked.
- **Every path.** ⌘V and the context menu's Paste are one JediTerm action, which
  `getActions()` replaces with Hovel's paste. The question cannot live in `getContents`:
  JediTerm also reads the clipboard there to enable the action, on every ⌘V and whenever the
  menu opens. A middle click pastes only with `pasteOnMiddleMouseClick()`, which Hovel's
  settings keep false. The workspace window has no Edit menu, and the main window's Edit →
  Paste acts on the page, never on a tab. Committed input-method text (a dead key's é, dictation) is
  typing, not a paste, and is not asked about.

#### The changes view

Diff tabs are not built yet (`ShellLaunch.command` throws for `Launch.Diff`), but their
filter is. git passes a file's own bytes through untouched, so an agent-edited file can carry
escape sequences to the terminal. JediTerm honours SGR, including hidden text (`ESC[8m`) and a
foreground set to the background, and cursor moves (`ESC[1A`, `ESC[2K`) that overwrite lines.
Carriage return and backspace overwrite text too, and C1 controls (U+0080 to U+009F, where
U+009B is a one-character CSI) act as well. A reviewer could then miss lines in exactly the
view meant for reviewing agent edits. So a diff tab runs a fixed `/bin/sh` script, given git,
the `:(literal)` pathspec, the filter and the delta wrapper as arguments:

```text
git -c core.fsmonitor=false --no-optional-locks diff --no-color --no-ext-diff --no-textconv HEAD -- <file> | <filter> | <delta> --paging=always
```

History is `git log -p --follow --no-color …`, and an untracked or added file
`git diff --no-index --no-color /dev/null <file>`, through the same filter. It is
`--no-color`, not `-c color.ui=never`: a repository's `color.diff=always` overrides
`color.ui`. Delta does its own colouring. No byte of the file reaches delta or the terminal
but through the filter; if the filter cannot start, delta gets nothing, never the raw diff.

The filter is `visible-controls.pl` (resources `workspace/shell/`, installed beside the
shell startup files), run as `/usr/bin/perl -T -C0 <file>` (`ShellLaunch.controlFilter()`):

- Tab and newline pass. ESC shows as `^[`, CR as `^M`, BS as `^H`, and other C0 controls and
  DEL in caret form (`^@`, `^?`).
- C1 controls show as `<U+0080>` to `<U+009F>`.
- A byte that is not part of valid UTF-8 (a stray byte, a cut sequence, an overlong form, a
  surrogate, a code point past U+10FFFF) shows as `<XX>`, as `less` shows it.
- Valid UTF-8 passes unchanged, emoji and accents included.

Perl works on bytes, so it needs no decoding and invalid UTF-8 cannot confuse it. It streams
a line at a time, and macOS ships it (5.34 on macOS 26), so nothing new is downloaded or
signed. `cat -v` writes every non-ASCII byte in `M-` form, which breaks UTF-8 text, and `sed`
and `tr` cannot tell valid UTF-8 from invalid. `-T` makes perl ignore `PERL5OPT` and
`PERL5LIB`, and `-C0` with the script's raw layers ignores `PERL_UNICODE` and `PERLIO`, so
the player's environment cannot change the filter. If a later macOS drops `/usr/bin/perl`, the
diff tab must say so and not start, never fall back to an unfiltered diff.

#### Quitting

In a development build the app handles macOS's Quit itself (`Desktop.setQuitHandler`):
⌘Q, the Dock's Quit, and the quit a logout, restart or Software Update sends. The handler
keeps the system's `QuitResponse` open while the app decides. With nothing busy, or once the
person chooses Quit, the app's own shutdown ends the process, and a pending logout or restart
goes on. Cancel calls `cancelQuit()`, which cancels that logout or restart, as any Mac app
that asks does. A quit that arrives while the question is up joins it; one that arrives once
the exit has started is ignored, and the exit ends it. The exit calls `exitApplication()`
even when closing an open game fails, so a started exit always ends the app. The JDK lets
the response be answered later from any thread, and nothing waits on it at exit. A release
build installs no handler and keeps the JDK's quit.

#### Confirmation sheets

`Confirmations` words every question from `Busy` data, as a `NativeDialog<Boolean>`;
`SheetConfirmer` shows it. The sheet is owned by the workspace window, never by
`NativeDialogs`, which always raises the main window. Cancel is the default button, so a
stray Return keeps the work. macOS makes a sheet only of a document-modal dialog whose root
pane says so before it is first shown, so the dialog is built by hand rather than by
`JOptionPane.createDialog`, which makes an application-modal one; with no window to attach
to, it is application-modal.

Every line is its own `JLabel`, created empty, with `html.disable` set before its text. The
sheet never hands Swing a `String` message: JBR 21's `BasicOptionPaneUI` draws any string
that contains `<html>`…`</html>` as HTML, wherever it starts, and `html.disable` on the pane
does not reach that label. A command line or file name could then make the sheet load a
remote image. Names in a question, and the titles programs set for their tabs, are cleaned
first (`clip`): no control or format characters (bidi overrides, zero widths) and no lone
surrogates, cut without splitting a surrogate pair.

#### Probe measurements

The probe's `run`, `typing` and `cpu` steps measure responsiveness: the time to finish a
command with the terminal drawing its output, key-to-paint latency, and the app's CPU use and
AWT-thread lateness. Measure with the screen unlocked and the window in front: while the Mac
is locked, macOS presents no frames, and paint-bound numbers mean nothing. For example:

```text
run 180000 cat big.txt
run 180000 cat big.txt > /dev/null
typing 40
send yes\r
cpu 5000 yes flood
send \x03
```

## Build boundaries

Desktop is the default target. `-Pgamero.iosTargets=true` adds only the iOS
targets and needs no Android SDK; see [ios.md](ios.md). `-Pgamero.mobileTargets=true`
adds both Android and iOS; with it, the Android debug build
(`:composeApp:assembleDebug`) compiles inside the shared `composeApp` module.
There is no `iosX64` target: Compose 1.12 and androidx lifecycle 2.11 do not
publish it.
Mobile-only package versions are retained. Unsupported legacy mobile functions
fail explicitly on desktop and are not exposed through its WebView bridge.

The version catalog updates the desktop Kotlin/Compose/compiler, lifecycle,
coroutines, serialization, WebView, Koin, settings and hot-reload dependencies.
Material3 remains on its latest published stable 1.9.0; newer listed versions are
prereleases. Compose 1.12.1's Android artifacts require AGP 9.1+ and compileSdk 37,
so the build uses Gradle 9.3.1 (the minimum for AGP 9.1.1), AGP 9.1.1 and
compileSdk 37.

AGP 9 rejects `com.android.application` inside a Kotlin Multiplatform module.
`gradle.properties` sets `android.builtInKotlin=false` and `android.newDsl=false`
to opt out temporarily. Remove both once the Android app moves to its own module
that depends on `composeApp`.

### Terminal library checksums

`gradle/verification-metadata.xml` pins the SHA-256 of every pty4j, JNA and
JediTerm artifact the build resolves. pty4j and JNA ship natives that development
packages re-sign with our identity, and JediTerm runs in the app. That includes
JNA 5.6.0, which Gradle's own tools resolve. Every other group is trusted, as are
IDE source and javadoc downloads. If an artifact from those three groups is
changed or not listed, the build fails with "Dependency verification failed" and
names the artifact.

After bumping one of them, check the new release, then regenerate the entries:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --write-verification-metadata sha256 -Pgamero.mobileTargets=true :composeApp:jvmTest :composeApp:prepareAppResources :composeApp:testDebugUnitTest --continue
```

Review the diff: only the bumped components should change. Compare each new jar's
checksum with the `.sha256` or `.sha1` file next to it in its repository, and
delete entries for versions nothing resolves any more.

Sources: [Kotlin compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html),
[Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html),
[WebView desktop setup](https://github.com/KevinnZou/compose-webview-multiplatform/blob/main/README.desktop.md).

## Verification status

Verified on 9ad5c59 with Gradle 9.3.1/AGP 9.1.1: the desktop compile, all 63 JVM
tests and the Android debug build pass. Mac launch is assigned to the
coordinator's separate validation task. No native desktop success is claimed yet.

On a Pixel 8 Pro (API 34) emulator, launched with the Hovel debug extras:

- The launch animation plays and settles on the start screen.
- A tap on the button area 0.5s into the launch does nothing.
- Recreating the activity 1.2s into the launch shows the settled screen at once;
  the intro does not replay.
- Recreating it again after it settles does not crash.
- "Open the door" loads the configured URL in the web view.

`MainActivity` is portrait-only, so recreation was tested with a dark-mode toggle
rather than rotation.
