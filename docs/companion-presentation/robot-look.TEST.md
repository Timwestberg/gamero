# GM2 robot contract checks

Use Java 21 with cached dependencies:

```sh
./gradlew --offline :composeApp:jvmTest :voice:jvmTest
python3 tools/sync-server-fixtures.py ../server-platform-robot-contract4 --check
./gradlew --offline -Pgamero.mobileTargets=true :composeApp:iosSimulatorArm64Test
```

Local results, 2026-10-30: JVM 749 tests, 24 skipped, no failures; voice 30
passed; iOS 118 tests, one skipped, no failures. Fixture sync passes against SP2
commit `456de02562514709f1f3aedf22568d1017503809`. The optional local-stack
acceptance below adds one JVM test, skipped unless explicitly configured.

## Combined contract window

SP2's real service ran against a newly migrated disposable PostgreSQL database.
GP9's Chromium editor saved Plum and restored it after reload. GM2 then used
`HovelHttpTransport` and `HttpConversationApi` to claim that companion, asserting
its ID and exact four saved colors. All three checks passed. No provider ran,
and this does not establish JCEF, voice, phone or embedded-game acceptance.

To repeat the native part, create a disposable local server/account/host/project,
queue one message and save a look through GP9. Write a local JSON fixture with
`base` (http://127.0.0.1:<port>), `owner`, `hostId`, `hostToken`, `companionId`,
`conversationId` and `expectedLook`. Keep its test token local. Run:

```sh
HOVEL_ROBOT_CONTRACT_FIXTURE=/absolute/fixture.json ./gradlew --offline \
  :composeApp:jvmTest --tests '*RobotContractLocalStackTest'
```

Expected marker: `CONTRACT4_NATIVE_REAL_SERVICE_CLAIM_OK`. The test refuses a
non-loopback base and invokes no runtime. Queue another message before repeating.

## Launcher and artwork

DesktopGamesTest checks embedded-state isolation and relative-save rejection.
The robot still is rendered from the shared product room; provenance and a
reproduction script are checked in. The still was shown before merge. No Gamero
UI screenshots were taken, in accordance with the owner's instruction.
