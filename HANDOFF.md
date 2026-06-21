# StorageGuide Handoff

Last updated: June 21, 2026

## Current State

- Branch: `main`
- Remote: `https://github.com/boyakhil978/StorageGuide.git`
- Release version: `2.0.1`
- Stable release tag: `v2.0.1`
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

## v2.0.1 Work

- The held-item locator now defaults to the grave-accent/tilde key instead of `P`.
- Finder and cell-editor item choices are sourced from ordinary creative tabs, excluding operator-only technical entries.
- Routine synchronization and debug chat messages have been removed.
- Sloppiness detection compares container contents before and after each click, so removals and pre-existing misplaced items do not produce false positives.
- Sloppiness validation now resolves item variants through their configured destination and correctly handles both halves of double chests.
- The README now provides a marketable overview, Modrinth-first installation, detailed usage guidance, and a preference for safe in-game configuration.

## Post-v2.0.1 Work

- Added optional Mod Menu integration through the `modmenu` entrypoint and a compile-only dependency.
- Added persistent client settings in `config/storageguide-client.json` for the located-chest highlight color, found-item hotbar color, missing-item hotbar color, and hotbar-status visibility.
- Added an RGB slider color picker with live preview and default-color restoration.
- Added a selected-hotbar-slot status border. It resolves normal items and shulker contents against the client's synchronized cell assignments.
- Added an operator settings screen, accessible from the client settings screen or `/storageguide settings`.
- Added capability-checked request, update, and open-screen payloads for operator settings. The menu currently manages the server's sloppiness-detector toggle.

## Important Files

- `src/main/java/com/storageguide/StorageGuideServer.java`
  - Server state, storage assignments, lookup handling, and sloppiness detection.
- `src/main/java/com/storageguide/StorageGuideConfig.java`
  - Persistent server configuration and storage-cell model.
- `src/main/java/com/storageguide/StorageGuideCommands.java`
  - Operator command registration.
- `src/main/java/com/storageguide/StorageGuideClient.java`
  - Client controls, networking, highlights, HUD status border, and screens.
- `src/main/java/com/storageguide/StorageGuideClientConfig.java`
  - Persistent client colors and HUD preferences.
- `src/main/java/com/storageguide/StorageGuideModMenu.java`
  - Optional Mod Menu configuration-screen entrypoint.
- `src/main/java/com/storageguide/mixin/AbstractContainerMenuMixin.java`
  - Captures chest contents before a click and runs the detector afterward.
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
9. Test finder live search and readable item names.
10. Confirm operator-only technical entries do not appear in finder or editor lists.
11. Test empty and non-empty shulker lookup for multiple shulker colors.
12. Test correctly and incorrectly packed shulkers with the sloppiness detector.
13. Confirm removing items and clicking around a chest with an old misplaced item do not trigger a new warning.
14. Test grid-cell editing with signs and item frames in front of chests.
15. Test highlights with Iris/Photon enabled.
16. Test a current client/server pair and legacy packet fallback with an older peer.
17. Open StorageGuide through Mod Menu and test all RGB sliders, defaults, save, cancel, and config persistence.
18. Confirm the selected hotbar border is green for configured items and red for unconfigured items.
19. Test hotbar status for empty, compatible, and incompatible shulker boxes.
20. Open `/storageguide settings` as an operator and verify the detector toggle saves; confirm non-operators cannot open or update it.

## Notes for the Next Maintainer

- `mod_version` is `2.0.1` in `gradle.properties`.
- Never change the codec of an existing payload ID. Add a new payload ID and negotiate support.
- Before a future release, choose the next version, update release-facing documentation, rebuild, inspect the jar metadata/icon, and perform the in-game checks above.
