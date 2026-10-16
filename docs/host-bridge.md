# Host bridge

This is the developer guide for `host-bridge.v2`, the one bridge between the web
page and native code on desktop, Android and iOS. It says how a page finds out
what a host can do, and how to add a capability. The design and its reasons are
in [the host bridge spec](../.10x/specs/2026-10-14-host-bridge-design.md) and
[ADR-001](../.10x/adrs/001-one-host-bridge-protocol.md). The Mac companion's
trust rules are in [hovel-companion.md](hovel-companion.md).

All Kotlin paths below are under `composeApp/src/` and the package
`org.formulagen.wrapper.bridge`, unless stated.

## The parts

| Part | Where | Job |
| --- | --- | --- |
| Contract | server-platform `event-contracts/src/host-bridge/` | Zod schemas for every method and event, the `HOST_BRIDGE_PARITY` table, and the shared fixtures. The source of truth. |
| Router | `commonMain/.../BridgeRouter.kt`, `BridgeCheck.kt` | Every protocol rule: frame and origin, size, envelope, context, method, arguments, error codes. No platform imports. |
| Method types | `commonMain/.../BridgeMethods.kt` | `BridgeMethod`, `BridgeEvent`, and the router's own `host.*` methods. |
| Platform set | `commonMain/.../HostCapabilities.kt` | One constructor parameter per platform capability: `Capability.Supported(impl)` or `Capability.Unsupported`. |
| Capabilities | `commonMain/.../LinkOpener.kt`, `Haptics.kt`, `Lifecycle.kt`, `CompanionCapability.kt` | One file each: the interface, its argument and result types, its methods and its `CapabilityBinder`. |
| Platform factories | `jvmMain/.../DesktopHostCapabilities.kt`, `androidMain/.../AndroidHostCapabilities.kt`, `iosMain/.../IosHostCapabilities.kt` | Each platform's choice for every capability. |
| Shared transport half | `commonMain/.../HostBridge.kt` | What every transport shares: the router, the trusted origin, the document counter, the event channel and the failure log. A platform transport calls its `receive` and `documentChanged`. |
| Transport helpers | `commonMain/.../MessagePortTransport.kt` (Android), `iosMain/.../WebKitHostTransport.kt` (iOS) | The mobile transport rules with no WebView types, so tests can drive them: port frames on Android, document-start messages and events on iOS. |
| Platform transports | `jvmMain/.../JcefHostBridge.kt`, `androidMain/.../AndroidHostBridge.kt`, `iosMain/.../IosHostBridge.kt` | Move strings between the WebView and the shared half, and report frame facts read from the platform. Nothing else. |
| Desktop receiver | `jvmMain/.../companion/NativeBridge.kt` | The JCEF message router. It receives each query first, drops queries from another browser or an invalid frame, and hands version 2 requests to `JcefHostBridge`. See [hovel-companion.md](hovel-companion.md). |
| Web client | game-platform `packages/host-bridge` (`@formulagen/host-bridge`) | `capabilities()`, `call(method, args)`, `on(event, handler)`. |

## Ask before you call: `host.info` and `host.capabilities`

The router answers these two methods itself, on every platform, so a page can
learn what the host is and what it supports before it calls anything else.

| Method | Arguments | Result |
| --- | --- | --- |
| `host.info` | `{}` | `{platform, appVersion, bridgeVersion}`: `platform` is `desktop`, `android` or `ios`; `bridgeVersion` is `2`. |
| `host.capabilities` | `{}` | `{methods, events}`: the method and event names this host supports, in declaration order. |

- `host.capabilities` lists only what the host supports. A method of a
  `Capability.Unsupported` capability is left out, and calling it answers
  `unsupported` with a message that names the product, the platform and the
  capability. A method that no capability on this host declares answers
  `unknown-method`.
- Each platform factory passes its own `platform` and `appVersion`. The
  desktop factory (`DesktopHostCapabilities.kt`) reads the
  `jpackage.app-version` system property itself. Android and iOS read the
  version in their start code and pass it to the factory: Android reads the
  package `versionName` in `androidMain/.../hovel/HovelAndroid.kt`, and iOS
  reads `CFBundleShortVersionString` in `iosMain/.../MainViewController.kt`.
  Each falls back to `dev` in a development build.
- On the web, `bridge.capabilities()` calls `host.capabilities`. A call through
  the client never throws: it resolves to `ok`, `unsupported` or `error`, so a
  page can also just call and handle `unsupported`.

Code: `BridgeMethods.HOST_INFO` and `BridgeMethods.HOST_CAPABILITIES` in
`BridgeMethods.kt`, answered in `BridgeRouter.buildHandlers`. Tests:
`hostInfoReportsThePlatform` and
`capabilitiesListExactlyTheSupportedColumnOfTheParityTable` in
`commonTest/.../BridgeRouterTest.kt`, plus the `host-info*.json` and
`host-capabilities.json` fixtures.

## Recipe: add a capability

Follow these steps in order. You do not edit the router, the transports or any
other capability. `Haptics.kt` is the smallest complete example of a method
capability; `Lifecycle.kt` is the example of an event capability.

1. **Contract (server-platform).** In `event-contracts/src/host-bridge/capabilities.ts`,
   add each method to `HOST_BRIDGE_METHODS` (zod `args` and `result`, with
   `z.strictObject`, and a `capability` name) or each event to
   `HOST_BRIDGE_EVENTS` (`capability` and `data`). Every existing entry has a
   `capability` key, but the type does not require one, so check it yourself.
   Use the same `capability` value as the `CapabilityBinder` name in step 2.
   Two existing capabilities do not match yet and are a known gap: the
   contract says `haptic` and `link`, the Kotlin binders say `haptics` and
   `links`. Add a row for each
   one to every platform column of `HOST_BRIDGE_PARITY`, and copy the table to
   `fixtures/parity.json`. Add router fixtures under `fixtures/router/`: at
   least one valid call, one call with bad arguments, and one call on a
   platform that marks it `unsupported`. Run `pnpm --filter
   @formulagen/event-contracts test`. This is its own PR, and it merges first.

2. **Interface (gamero `commonMain/.../bridge/`).** Add one file named after
   the capability. It holds:
   - a `fun interface` (or `interface`) whose bridge methods are `suspend`.
     Event-only capabilities instead have a non-suspending `observe` that
     returns an `AutoCloseable`, as `Lifecycle` does;
   - `@Serializable` argument and result types whose field names match the zod
     schema. Use `NoArgs` and `NoResult` for `{}`;
   - an `object` with one `BridgeMethod` (or `BridgeEvent`) per wire name, and
     an `internal val binder = CapabilityBinder(...)` named with the contract's
     `capability` value. The name is what an `unsupported` message shows. A
     method capability binds each method with
     `METHOD.handle { capability: YourInterface, args -> ... }`. An event
     capability passes `handlers = emptyList()`, `events = listOf(...)` with
     every event it emits, and
     `observe = { impl, sink -> impl.observe(...) }`, which emits through
     `sink.emit(EVENT, data)` and returns the `AutoCloseable`. The router calls
     `observe` once, when it is built, not once per context. An event the
     binder did not list in `events` is dropped and reported to the router's
     `onFailure`; the page never gets it.

   Put any rule a serializer cannot express in the method's `accepts`, as
   `LinkMethods.OPEN` does for HTTPS. The router checks it before your code
   runs, so a capability never sees invalid input. Give a method a `timeout`
   when 30 seconds is wrong for it.

3. **Register it.**
   - A **platform capability** (every host decides on it): add a
     `Capability<YourInterface>` parameter, with no default, to
     `HostCapabilities`, and add `YourMethods.binder.bind(yours)` to its `bound`
     list. The compiler then names every place that builds a
     `HostCapabilities`: the three platform factories, and the test
     constructors in `BridgeRouterTest` (its `capabilities` helper),
     `CapabilityBinderTest`, `TransportContractTest` and
     `JcefTransportContractTest`. Steps 4 and 5 say what to pass in each. The
     compiler does not catch a binder missing from `bound`; the parity tests
     do.
   - A **product capability** (only one product needs it, such as Hovel's
     companion): add `fun pack(capability: Capability<YourInterface>) =
     binder.pack(capability)` to the object, as `CompanionMethods.pack` does.
     Then add the pack to each platform's Hovel pack list, `Unsupported` where
     the platform has none. There are three lists, with different names:
     - desktop: `hovelBridgePacks(companion)` in `jvmMain/.../hovel/HovelCompanion.kt`;
     - Android: `hovelBridgePacks()` in `androidMain/.../hovel/HovelAndroid.kt`;
     - iOS: `iosHovelBridgePacks()` in `iosMain/.../IosHostCapabilities.kt`.

     The compiler does not check that the three lists agree; only the parity
     tests do, and two of them run only on Android and iOS.

4. **Platforms.** In each platform factory, pass `Capability.Supported(impl)`
   where the platform can do it and `Capability.Unsupported` where it cannot.
   Write each implementation in that platform's source set. Follow the
   consistency rules below.

5. **Router tests.** `BridgeRouterTest` builds its own set from the parity
   table, with one fake per capability. Add a fake to its `Fakes` class and
   pass `supported(platform, "<area.verb>", fake)`: in `capabilities` for a
   platform capability, or in `hovelPacks` for a product capability. The
   router fixtures and `capabilitiesListExactlyTheSupportedColumnOfTheParityTable`
   then follow the table. Passing `Capability.Unsupported` there instead makes
   the valid-call fixture answer `unsupported` and fails the capabilities test
   on every platform the table marks `supported`. In the other test
   constructors the compiler flags (`CapabilityBinderTest`,
   `TransportContractTest`, `JcefTransportContractTest`), pass
   `Capability.Unsupported`.

6. **Fixtures.** Run `tools/sync-host-bridge-fixtures.py ../server-platform`
   to regenerate `commonTest/.../HostBridgeFixtures.kt` from the merged
   contract, then the router tests. `--check` fails when the copy is stale.

7. **Parity tests.** `DesktopParityTest`, `AndroidParityTest` and
   `IosParityTest` check each factory against its column of the parity table.
   A row the table marks `supported` that a platform has not shipped yet goes
   in that test's `pending` set. Remove it from `pending` in the PR that ships
   it; the test fails if you forget.

8. **Web.** Callers use `bridge.call('area.verb', args)` from
   `@formulagen/host-bridge` and handle `unsupported`. The client takes its
   method and event types from the contract, so its source needs no change.
   The app still has to build against the merged contract: game-platform
   reads `@formulagen/event-contracts` from the sibling `../event-contracts`
   checkout through a `file:` dependency. Update that checkout, build it,
   reinstall (pnpm copies the package at install time), and rebuild the app.
   Until then the new method does not typecheck and its result is not
   validated.

Run the tests on every target with `tools/test-all-targets.sh`. A JVM run alone
does not run the Android or iOS parity tests.

## Consistency rules

The router enforces the first three. A capability or a transport must obey the
rest. The full table is in the spec under "Consistency rules".

- **Shape.** One `area.verb` name. Every reply is `{ok:true, result}` or
  `{ok:false, error:{code, message}}`. Code reads `code`; the message is for
  people.
- **Arguments.** Decoded strictly (an unknown key fails) and checked before the
  capability runs.
- **Errors.** Throw `BridgeBusyException` for `busy` and
  `BridgeDeclinedException` for `declined` (the person said no). Any other
  exception, or running past the method's timeout, becomes `failed`; the host
  logs it and the page never sees a stack trace.
- **Threading.** The router calls a capability from a background coroutine,
  never the UI thread. A capability that needs the UI thread switches to it
  itself, with `withContext(Dispatchers.Main)`.
- **Lifecycle.** A context belongs to one document. A navigation or reload
  cancels running calls, so a capability must stop when its coroutine is
  cancelled. Events go only to an open context.
- **Not supported.** Pass `Capability.Unsupported`. Never throw "not
  supported" at call time.
- **One call at a time.** A capability that cannot run two calls at once throws
  `BridgeBusyException`. It does not queue.

## Recipe: add a platform target

1. Pick the carrier: the page global the host installs and how envelopes ride
   on it. Each existing carrier is defined in the contract's
   `event-contracts/src/host-bridge/transports.ts`: desktop uses
   `gameroQuery`, Android a `gameroHost` object with a message port, iOS the
   `gameroHost` message handler plus `gameroHostEvent`. The web client picks
   its transport by checking which of these globals the page has
   (`detectHostTransport` in game-platform
   `packages/host-bridge/src/transports.ts`, and the README's "Transports"
   table). If the new platform reuses an existing carrier with the same
   framing, nothing changes on the web side. If it needs a new carrier, add
   it to the contract's `transports.ts` and add a transport to
   `@formulagen/host-bridge`. Without that, `createHostBridge()` finds no
   host and every call answers `unsupported`.
2. Write one transport on `HostBridge` that reads frame facts from the platform
   API, calls `receive` for each message, sends replies and events as JSON
   values (never as script), and calls `documentChanged` when the main frame
   gets a new document. Reuse `MessagePortTransport` or `WebKitHostTransport`
   if the WebView works the same way. `TransportContractTest` checks an
   adapter against these rules.
3. Write one `HostCapabilities` factory for the platform, and a parity test.
4. Add the platform to `HostPlatform` here and to `HOST_PLATFORMS` and
   `HOST_BRIDGE_PARITY` in the contract.
5. Smoke-test it. `tools/native-bridge-smoke.html` is desktop only: it calls
   `window.gameroQuery`, which only JCEF provides. No bridge smoke page exists
   for Android or iOS yet (`tools/ios-smoke.sh` only checks that the web app
   loads), so on a mobile platform use a page built on
   `@formulagen/host-bridge`.
