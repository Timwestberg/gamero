# Pet launch architecture

## Data and interfaces
The host-bridge v2 `pet` capability (event-contracts `host-bridge/pet.ts`, Kotlin `PetCapability.kt`) has `pet.status`, `pet.show` and `pet.hide` with empty arguments, and `pet.configure` with `{companion: {id, name, appearance}}`. The router rejects any other field as `invalid` before native code runs. Configure validates the profile (UUID, a 1–40 character name without control characters or surrounding whitespace, four `#rrggbb` colors) and writes it to the native-owned `companion-appearance.json` beside the pet log; it never starts or stops the pet. Replies are `{state: 'running' | 'stopped' | 'unavailable' | 'error', companionId?, name?, message?}`, with `message` at most 300 characters. Native configuration alone supplies `HOVEL_PET_PROJECT` and `HOVEL_PET_GODOT`; the pet reads the appearance file through `HOVEL_COMPANION_APPEARANCE`.

## Ownership
- DesktopPetLauncher owns one child process, validates the configured installation and engine version, waits for the pet's readiness marker, and cleans up on failure or host shutdown.
- Pet, game, chat and provider calls share the page's one v2 document context. A cancelled request, such as an abandoned status poll, cancels only itself; only the end of the document context closes a game or chat view.
- PetLaunchControl renders an injected pet service in the Hovel toolbar, welcome page and catalog using existing control styling. It refreshes status while mounted to observe the pet's own Quit command.
- Page context invalidation cancels requests but does not stop a ready pet. The app owns the pet lifetime; the page owns each request.

## Tradeoffs
The pet stays an independent transparent overlay, using its existing rendering and input implementation. Embedding it inside the game surface would remove the desktop behavior. The pet runs on the shared v2 host bridge; the v1 pet route is removed. Existing colocated Kotlin and TypeScript test conventions are retained.
