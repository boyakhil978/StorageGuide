# StorageGuide Handoff

Last updated: June 18, 2026

## Current State

- Branch: `main`
- Latest pushed commit: `06477ba` (`Add configurable sloppiness detector`)
- Remote: `https://github.com/boyakhil978/StorageGuide.git`
- Working version: `1.0.0`
- Latest changes are committed and pushed to GitHub.
- No tag or GitHub release was created for the latest commit.
- `./gradlew build` passes.

## Project Summary

StorageGuide is a Fabric client/server mod for Minecraft `26.1.2+`. A server operator defines a storage-wall grid and assigns one or more item types to each chest cell. Players can then locate assigned items using a held-item shortcut or finder menu.

The server owns the grid and assignments in `config/storageguide.json`. Clients render highlights and provide the editing and lookup interfaces.

## Latest Work

The latest commit added:

- An optional server-side sloppiness detector.
- `/storageguide sloppiness_detector` operator commands:
  - No argument reports the current state.
  - `on` enables it.
  - `off` disables it.
- Detection of items placed in an assigned chest that do not match that chest's configured items.
- A server-wide announcement when mismatched storage is detected.
- Per-player and per-chest 30-second announcement cooldowns.
- Support for inspecting both halves of a double chest through mixin accessors.
- A client config at `config/storageguide-client.json`.
- A `debugMode` client setting, defaulting to `false`, which suppresses routine server/debug messages unless enabled.

The sloppiness detector defaults to `false` and is persisted in `config/storageguide.json`.

## Important Files

- `src/main/java/com/storageguide/StorageGuideServer.java`
  - Server state, storage assignments, lookup handling, and sloppiness detection.
- `src/main/java/com/storageguide/StorageGuideConfig.java`
  - Persistent server configuration and storage-cell model.
- `src/main/java/com/storageguide/StorageGuideCommands.java`
  - Operator command registration.
- `src/main/java/com/storageguide/StorageGuideClient.java`
  - Client controls, networking, highlights, and screens.
- `src/main/java/com/storageguide/StorageGuideClientConfig.java`
  - Client-only debug configuration.
- `src/main/java/com/storageguide/mixin/AbstractContainerMenuMixin.java`
  - Runs the detector after a container click.
- `src/main/java/com/storageguide/mixin/CompoundContainerAccessor.java`
  - Exposes both containers in a double chest.
- `src/main/resources/storageguide.mixins.json`
  - Mixin registration.
- `src/main/resources/fabric.mod.json`
  - Mod metadata, entry points, dependencies, and mixin declaration.

## Build and Verification

Requirements:

- Java 25+
- Minecraft `26.1.2+`
- Fabric Loader `0.19.2+`
- Fabric API `0.152.1+26.1.2`

Build with:

```bash
./gradlew build
```

The jar is generated under `build/libs/`.

Recommended in-game checks:

1. Start a server and client with the mod installed.
2. Confirm existing grid creation, editing, finder, and held-item lookup still work.
3. As an operator, run `/storageguide sloppiness_detector on`.
4. Put an unassigned item into an assigned chest.
5. Confirm the server broadcasts the sloppiness message.
6. Confirm repeated clicks do not spam announcements during the 30-second cooldown.
7. Test both single and double chests.
8. Run `/storageguide sloppiness_detector off` and confirm announcements stop.
9. Set `debugMode` to `true` in `config/storageguide-client.json` and confirm routine client messages become visible.

## Notes for the Next Maintainer

- The README still describes stable release `v1.0.0`; the latest feature commit has not been released.
- `mod_version` remains `1.0.0` in `gradle.properties`.
- Do not create a release or version tag unless explicitly requested.
- Before a future release, choose the next version, update release-facing documentation, rebuild, and perform the in-game checks above.
