# Show Ember from Hovel

Run `Play From Catalog.command`, then choose **Show Ember** in the catalog or Hovel. **Hide Ember** closes the desktop overlay. You can also quit through the pet's own menu; Hovel notices within two seconds.

## Prepare a new development checkout

```sh
cd ../game-platform-companions/ember-desktop-pet
./gradlew installAddon build
/Applications/Godot.app/Contents/MacOS/Godot --headless --path . --import
```

Use JDK 21 and the official Godot 4.7.2 executable. Then launch Gamero with the existing [catalog instructions](catalog-launch.README.md). The script supplies these defaults:

```sh
export HOVEL_PET_PROJECT="/absolute/path/to/game-platform-companions/ember-desktop-pet"
export HOVEL_PET_GODOT="/Applications/Godot.app/Contents/MacOS/Godot"
```

Both variables can be overridden before launch. The pet uses its existing transparent overlay. The host logs startup at `~/Library/Application Support/Gamero/ember-pet.log` and waits for `EMBER_PET_READY` before reporting success.

## Extend or reuse

- UI: render `PetLaunchControl`; inject its `pet` service for isolated UI tests.
- Transport: `createNativePet(host)` validates replies and shares `requestDesktopAction` with game launches. Do not open another v1 document watch for each feature.
- Native: `DesktopPetLauncher` owns process lifecycle. Only empty arguments are accepted; project and executable stay in native configuration.
- Hiding stops the process. Showing starts the existing pet project again; ephemeral pet menu choices follow that project's existing persistence behavior.
- Navigation leaves a ready pet alive. Closing the host stops its owned pet. Independently launched pets are outside this host's ownership.

This is local desktop launch support. It does not connect the pet to an account, start a task, grant capture access, or install it for production.
