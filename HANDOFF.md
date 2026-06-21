# StorageGuide Handoff

Last updated: June 21, 2026

## Current State

- Branch: `main`
- Remote: `https://github.com/boyakhil978/StorageGuide.git`
- Release version: `2.0.0`
- Stable release tag: `v2.0.0`
- `./gradlew build` passes.
- Fabric metadata must identify the author as `Akhil Boyapati`.
- The in-game mod icon is `assets/storageguide/icon.png`, a 512×512 PNG packaged inside the jar.

## Project Summary

StorageGuide is a Fabric client/server mod for Minecraft `26.1.2+`. A server operator defines a storage-wall grid and assigns one or more item types to each chest cell. Players can then locate assigned items using a held-item shortcut or finder menu.

The server owns the grid and assignments in `config/storageguide.json`. Clients render highlights and provide the editing and lookup interfaces.

## v2.0.0 Work

- Live item finder with readable, clickable results.
- Shader-compatible native gizmo highlights.
- Content-aware lookup and sloppiness validation for all shulker-box colors.
- Direct grid-cell ray selection through signs and item frames.
- Client/server version and protocol exchange with legacy packet fallback.
- Optional configurable sloppiness detector with double-chest support and cooldowns.
- Correct author and icon metadata in the compiled jar.

The sloppiness detector defaults to `false` and is persisted in `config/storageguide.json`.

Networking now exchanges the client/server mod version and protocol version when both peers support the handshake. New packet shapes must use new payload IDs; do not change the codec of an existing payload ID. The original string-based `locate_held` packet remains registered for older peers, while newer clients use `locate_held_v2` when the server advertises it. Server-to-client packets are only sent to clients that advertise support for that payload.

Grid-cell editing uses direct ray intersections against configured cell bounds. This deliberately ignores foreground signs, item frames, and similar decorations so the nearest grid cell under the crosshair remains editable.

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

Every compiled jar must preserve the release-facing Fabric metadata:

- Author: `Akhil Boyapati`
- Icon path: `assets/storageguide/icon.png`

After building, verify both files are packaged correctly:

```bash
unzip -p build/libs/storageguide-<version>.jar fabric.mod.json
unzip -l build/libs/storageguide-<version>.jar | grep assets/storageguide/icon.png
```

The author and icon are displayed by Fabric's in-game mod interfaces, so do not replace the author with the project name or move the icon without updating `fabric.mod.json`.

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
10. Test finder live search and readable item names.
11. Test empty and non-empty shulker lookup for multiple shulker colors.
12. Test correctly and incorrectly packed shulkers with the sloppiness detector.
13. Test grid-cell editing with signs and item frames in front of chests.
14. Test highlights with Iris/Photon enabled.
15. Test a current client/server pair and legacy packet fallback with an older peer.

## Notes for the Next Maintainer

- `mod_version` is `2.0.0` in `gradle.properties`.
- Never change the codec of an existing payload ID. Add a new payload ID and negotiate support.
- Before a future release, choose the next version, update release-facing documentation, rebuild, inspect the jar metadata/icon, and perform the in-game checks above.
