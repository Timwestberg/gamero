# Pet launch architecture

## Data and interfaces
The existing origin-checked v1 native bridge accepts `pet.status`, `pet.show` and `pet.hide` with empty arguments, and `pet.configure` with `{companion: {id, name, appearance}}`. Configure validates the profile (UUID, a 1–40 character name without control characters or surrounding whitespace, four `#rrggbb` colors) and writes it to the native-owned `companion-appearance.json` beside the pet log; it never starts or stops the pet. Replies are `{state: 'running' | 'stopped' | 'unavailable' | 'error', companionId?, name?, message?: string}`. Native configuration alone supplies `HOVEL_PET_PROJECT` and `HOVEL_PET_GODOT`; the pet reads the appearance file through `HOVEL_COMPANION_APPEARANCE`.

## Ownership
- DesktopPetLauncher owns one child process, validates the configured installation and engine version, waits for the pet's readiness marker, and cleans up on failure or host shutdown.
- The existing desktop bridge client is shared by game and pet calls. One document context prevents pet status requests from invalidating a running game's context.
- PetLaunchControl renders an injected pet service in the Hovel toolbar, welcome page and catalog using existing control styling. It refreshes status while mounted to observe the pet's own Quit command.
- Page context invalidation cancels requests but does not stop a ready pet. The app owns the pet lifetime; the page owns each request.

## Tradeoffs
The pet stays an independent transparent overlay, using its existing rendering and input implementation. Embedding it inside the game surface would remove the desktop behavior. The v1 adapter remains temporary until the native checkout adopts the shared v2 host bridge. Existing colocated Kotlin and TypeScript test conventions are retained.
