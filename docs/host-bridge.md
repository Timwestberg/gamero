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
| Transports | `jvmMain/.../JcefHostBridge.kt`, `androidMain/.../AndroidHostBridge.kt`, `iosMain/.../IosHostBridge.kt` | Move strings and report frame facts read from the platform. Nothing else. |
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
- Each platform factory passes its own `platform` and `appVersion`. Desktop
  reads the `jpackage.app-version` system property, Android the package
  `versionName`, and iOS `CFBundleShortVersionString`. Each falls back to `dev`
  in a development build.
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
   `z.strictObject`) or each event to `HOST_BRIDGE_EVENTS`. Add a row for each
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
     an `internal val binder = CapabilityBinder(...)` that binds each method
     with `METHOD.handle { capability: YourInterface, args -> ... }`.

   Put any rule a serializer cannot express in the method's `accepts`, as
   `LinkMethods.OPEN` does for HTTPS. The router checks it before your code
   runs, so a capability never sees invalid input. Give a method a `timeout`
   when 30 seconds is wrong for it.

3. **Register it.**
   - A **platform capability** (every host decides on it): add a
     `Capability<YourInterface>` parameter, with no default, to
     `HostCapabilities`, and add `YourMethods.binder.bind(yours)` to its `bound`
     list. The compiler then names every platform factory that must choose.
     The compiler does not catch a binder missing from `bound`; the parity
     tests do.
   - A **product capability** (only one product needs it, such as Hovel's
     companion): add `fun pack(capability: Capability<YourInterface>) =
     binder.pack(capability)` to the object, and pass the pack from that
     product's start code on every platform, `Unsupported` where it has none.
     See `CompanionMethods.pack` and `hovelBridgePacks`.

4. **Platforms.** In each platform factory, pass `Capability.Supported(impl)`
   where the platform can do it and `Capability.Unsupported` where it cannot.
   Write each implementation in that platform's source set. Follow the
   consistency rules below.

5. **Fixtures.** Run `tools/sync-host-bridge-fixtures.py ../server-platform`
   to regenerate `commonTest/.../HostBridgeFixtures.kt` from the merged
   contract, then the router tests. `--check` fails when the copy is stale.

6. **Parity tests.** `DesktopParityTest`, `AndroidParityTest` and
   `IosParityTest` check each factory against its column of the parity table.
   A row the table marks `supported` that a platform has not shipped yet goes
   in that test's `pending` set. Remove it from `pending` in the PR that ships
   it; the test fails if you forget.

7. **Web.** Callers use `bridge.call('area.verb', args)` from
   `@formulagen/host-bridge` and handle `unsupported`. The client takes its
   method and event types from the contract, so it needs no change of its own.

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

1. Write one transport on `HostBridge` that reads frame facts from the platform
   API, calls `receive` for each message, sends replies and events as JSON
   values (never as script), and calls `documentChanged` when the main frame
   gets a new document. `TransportContractTest` checks an adapter against these
   rules.
2. Write one `HostCapabilities` factory for the platform, and a parity test.
3. Add the platform to `HostPlatform` here and to `HOST_PLATFORMS` and
   `HOST_BRIDGE_PARITY` in the contract.
4. Run the smoke page (`tools/native-bridge-smoke.html`) on the platform.
