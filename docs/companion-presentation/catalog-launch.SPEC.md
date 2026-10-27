# Catalog launch

## What and why
Use Gamero's existing glass web catalog to enter the companion prototype in the same Kotlin window. This is the user-approved next step toward testing the real app path.

## Behavior
- The Hovel Companions catalog card opens the native Foundry prototype.
- Kotlin presents upgrades and reviews above the game as before.
- Back to games restores the existing web catalog and stops the owned helper.
- Reopening uses the same local progression save. Closing the app closes its helper.
- Unknown game IDs and page-supplied paths are rejected. Plain browsers explain that the desktop app is required.
- Loading or engine failure remains visible and offers a way back.

## Scope
A local catalog fixture runs through the existing web app. Publishing to the live catalog, v2 bridge migration, mobile embedding, and production distribution are deferred.
