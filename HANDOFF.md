# StorageGuide Handoff

Last updated: June 25, 2026

## Current State

- Branch: `main`
- Remote: `https://github.com/boyakhil978/StorageGuide.git`
- Project version: `2.4.0`
- Latest stable release: `2.3.0`
- Stable release tag: `v2.3.0`
- `./gradlew clean build` passes.
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
- Optional configurable Big Brother misplaced-item monitoring with double-chest support and cooldowns.
- Correct author and icon metadata in the compiled jar.

Big Brother defaults to `false` and is persisted in `config/storageguide.json` as the legacy `sloppinessDetector` field for config compatibility.

Networking now exchanges the client/server mod version and protocol version when both peers support the handshake. New packet shapes must use new payload IDs; do not change the codec of an existing payload ID. The original string-based `locate_held` packet remains registered for older peers, while newer clients use `locate_held_v2` when the server advertises it. Server-to-client packets are only sent to clients that advertise support for that payload.

Grid-cell editing uses direct ray intersections against configured cell bounds. This deliberately ignores foreground signs, item frames, and similar decorations so the nearest grid cell under the crosshair remains editable.

## v2.0.1 Work

- The held-item locator now defaults to `~` instead of `P`.
- Finder and cell-editor item choices are sourced from ordinary creative tabs, excluding operator-only technical entries.
- Routine synchronization and debug chat messages have been removed.
- Sloppiness detection compares container contents before and after each click, so removals and pre-existing misplaced items do not produce false positives.
- Sloppiness validation now resolves item variants through their configured destination and correctly handles both halves of double chests.
- The README now provides a marketable overview, Modrinth-first installation, detailed usage guidance, and a preference for safe in-game configuration.

## v2.2.0 Work

- Added optional Mod Menu integration through the `modmenu` entrypoint and a compile-only dependency.
- Mod Menu `18.0.0-beta.1+` is suggested, not required. It belongs on clients that want the configuration button and is not required on dedicated servers.
- Added persistent client settings in `config/storageguide-client.json` for the located-chest highlight color, found-item hotbar color, missing-item hotbar color, and hotbar-status visibility.
- Added an RGB slider color picker with live preview and default-color restoration.
- Added a temporary selected-hotbar-slot status tint. Pressing `~` recolors the original vanilla selected-slot texture for 2.5 seconds, blends it back to white during the final 600 ms, and then leaves vanilla rendering unchanged.
- Added an operator settings screen, accessible from the client settings screen. As of v2.4.0, `/storageguide settings` opens client settings and `/storageguide operator_settings` opens operator settings directly for ops.
- Added capability-checked request, update, and open-screen payloads for operator settings. The menu currently manages the server's sloppiness-detector toggle.

Default client colors:

- Located chest: `#FFD11A`
- Item has a destination: `#55FF55`
- Item has no destination: `#FF5555`

The hotbar status is only activated by held-item lookup, not continuously. Switching slots cancels it. It is disabled for an empty selected slot, hidden HUD, or spectator mode. For a non-empty shulker, it shows the success color only when every contained item resolves to the same configured cell. An empty shulker is checked by its exact shulker item/color assignment. `GuiMixin` redirects the vanilla selector draw through the API's tint overload, preserving the original texture shape.

The operator settings screen is intentionally server-authoritative. All new payloads are guarded with `canSend` checks. Non-operators can open the menu in read-only mode and receive the permission explanation inside the screen rather than in chat. Save confirmations and rejected updates also return as menu state.

## v2.3.0 Work

- Added operator controls for force-client enforcement and sloppiness announcement cooldown.
- Force-client enforcement waits five seconds for the existing client hello packet before disconnecting a client without StorageGuide.
- Added persistent per-cell sloppiness exclusions to the chest editor.
- Added persistent sloppiness records before cooldown filtering, ensuring history contains every detected instance.
- Added the first public history screen sorted by player and then newest-first. This was later replaced by the v2.4.0 scrollable player-grouped Big Brother history UI.
- Added `/storageguide history` for all players and a history button in client settings.
- Migrated `config/storageguide.json` to schema version 4 with safe defaults for old files.
- Added V2 payloads for richer operator settings and editor state without changing existing packet codecs.

## v2.4.0 Work

- Renamed the user-facing misplaced-item feature to Big Brother while preserving the legacy config fields and `/storageguide sloppiness_detector` command as compatibility aliases.
- Added `/storageguide big_brother`, `/storageguide big_brother on`, and `/storageguide big_brother off`.
- Changed the default broadcast to `Big Brother caught {playername} slacking off.`
- Added operator-editable Big Brother announcement templates with `{playername}` and `{player}` placeholders.
- Supports multiple templates separated by `|` in the operator settings menu; the server chooses one at random per announcement.
- Migrated `config/storageguide.json` to schema version 5 with default Big Brother messages.
- Added V3 operator settings payloads for the Big Brother message template list without changing existing packet codecs.
- Bumped the StorageGuide protocol version to `4`.
- Reworked Big Brother history into a player-grouped menu: first screen shows one button per player and instance count, then clicking a player opens newest-first details.
- Added mouse-wheel scrolling to the Big Brother history, player detail, item finder, and cell editor lists.
- Added native in-game item icons to the finder, cell editor, and Big Brother player detail menu.
- Refreshed the client settings, operator settings, finder, cell editor, and history screens with darker panels and cleaner labels.
- Removed remaining page-style Up/Down list controls from finder and cell editor; overflowing lists now rely on mouse-wheel scrolling with inline scroll hints.
- Added root `/storageguide` command support so players can open the same server-backed settings/configuration flow without remembering the `settings` subcommand.
- Added visible scrollbars to scrollable StorageGuide menus.
- Added stronger selected-item feedback in the cell editor with a highlighted row, green accent bar, and cleaner title instead of raw `rN_cN` cell IDs.
- Changed the cell editor back to an explicit Save flow: selections remain as a draft, Save applies the update, and the editor reports assigned/removed item counts compared with the original cell state.
- Added a StorageGuide button to Minecraft's Escape/Pause menu through a client-only mixin.
- Added keybinding controls to the StorageGuide client settings screen; changes call Minecraft's normal keymapping APIs and save to the standard options file.
- Added hover tooltips to StorageGuide client settings, keybinds, color picker controls, and operator settings.
- Added older-client detection: when a joining client reports a StorageGuide version lower than the server, the server recommends upgrading. Clients also warn if a newer server version is advertised in `server_hello`.

## Important Files

- `src/main/java/com/storageguide/StorageGuideServer.java`
  - Server state, storage assignments, lookup handling, and Big Brother detection.
- `src/main/java/com/storageguide/StorageGuideConfig.java`
  - Persistent server configuration and storage-cell model.
- `src/main/java/com/storageguide/StorageGuideCommands.java`
  - Command registration, including client settings via `/storageguide` and `/storageguide settings`, plus op-only `/storageguide operator_settings`.
- `src/main/java/com/storageguide/StorageGuideClient.java`
  - Client controls, networking, highlights, HUD status border, and screens.
- `src/main/java/com/storageguide/StorageGuideClientConfig.java`
  - Persistent client colors and HUD preferences.
- `src/main/java/com/storageguide/StorageGuideModMenu.java`
  - Optional Mod Menu configuration-screen entrypoint.
- `src/main/java/com/storageguide/mixin/AbstractContainerMenuMixin.java`
  - Captures chest contents before a click and runs Big Brother afterward.
- `src/main/java/com/storageguide/mixin/CompoundContainerAccessor.java`
  - Exposes both containers in a double chest.
- `src/main/java/com/storageguide/mixin/GuiMixin.java`
  - Tints the original vanilla selected-slot sprite while the temporary lookup status is active.
- `src/main/resources/storageguide.mixins.json`
  - Mixin registration.
- `src/main/resources/fabric.mod.json`
  - Mod metadata, entry points, dependencies, optional Mod Menu suggestion, and mixin declaration.
- `build.gradle`
  - Includes the TerraformersMC Maven repository and compile-only Mod Menu API.

## Build and Verification

Requirements:

- Java 25+
- Minecraft `26.1.2+`
- Fabric Loader `0.19.2+`
- Fabric API `0.152.1+26.1.2`
- Mod Menu `18.0.0-beta.1+` for optional client configuration integration

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
3. As an operator, run `/storageguide big_brother on`.
4. Put an unassigned item into an assigned chest.
5. Confirm the server broadcasts the default Big Brother message.
6. Confirm repeated clicks do not spam announcements during the 30-second cooldown.
7. Test both single and double chests.
8. Run `/storageguide big_brother off` and confirm announcements stop.
9. Test finder live search and readable item names.
10. Confirm operator-only technical entries do not appear in finder or editor lists.
11. Test empty and non-empty shulker lookup for multiple shulker colors.
12. Test correctly and incorrectly packed shulkers with Big Brother.
13. Confirm removing items and clicking around a chest with an old misplaced item do not trigger a new warning.
14. Test grid-cell editing with signs and item frames in front of chests.
15. Test highlights with Iris/Photon enabled.
16. Test a current client/server pair and legacy packet fallback with an older peer.
17. Open StorageGuide through Mod Menu and test all RGB sliders, defaults, save, cancel, and config persistence.
18. Press `~` and confirm the selected frame temporarily becomes green for configured items and red for unconfigured items, then fades back to the vanilla texture.
19. Confirm switching hotbar slots immediately cancels the status; test empty, compatible, and incompatible shulker boxes.
20. Open operator settings as an operator and verify the Big Brother toggle saves. Open it as a non-operator and confirm the read-only permission message appears inside the menu with no StorageGuide chat warning.
21. Change the cooldown and message templates, then verify announcements use the new duration and template while every instance still appears in history.
22. Exclude a cell, place an incompatible item there, and confirm no history or announcement is created.
23. Enable required clients and confirm a client without StorageGuide is disconnected after the handshake grace period.
24. Run `/storageguide` as an operator and as a non-operator; verify it opens the settings flow and preserves read-only behavior for non-operators.
25. Open `/storageguide history` as a non-operator and verify entries are grouped as player buttons with newest-first detail rows.
26. Verify the finder, cell editor, and Big Brother detail menu show item icons and respond to mouse-wheel scrolling.
27. Verify scrollable StorageGuide menus show a scrollbar when content overflows.
28. Verify the cell editor keeps toggled items, Clear, and Big Brother exclusion as a draft until Save is pressed, then shows assigned/removed item counts in the editor.
29. Open the Escape/Pause menu and confirm the StorageGuide button opens the client settings screen.
30. Change StorageGuide keybinds from the client settings screen and confirm they match Minecraft's Controls/options state.
31. Join with an older StorageGuide client and verify the player is told to upgrade.

## Remaining TODO Direction

`TODO.md` was intentionally not edited during implementation, so it still lists work that is already present. Use the implementation and this handoff as the source of truth.

Completed locally:

- Mod Menu client settings screen.
- Configurable highlight color with RGB picker.
- Configurable found/missing hotbar colors and selected-slot status.
- Operator settings menu with Big Brother controls.
- Force-client-mod enforcement and configurable announcement cooldown.
- Individual cell exclusions from sloppiness detection.
- Persistent public Big Brother history grouped by player.
- Big Brother naming, command alias, custom announcement templates, grouped detail history, scrollable item/history lists, and native item icons.
- Root `/storageguide` opens the configuration/settings flow.
- Scrollbars, explicit cell-editor Save with assigned/removed item-count feedback, stronger selected-item styling, Escape-menu entry point, keybinding controls, and hover tooltips.
- `/storageguide` and `/storageguide settings` open client settings; `/storageguide operator_settings` opens server/operator settings for ops.
- Older clients receive an upgrade recommendation when connecting to a newer StorageGuide server.

Still pending:

- Support multiple rectangular grids, separate creation/editing controls, and add confirmation/onboarding for new configurations.

## Notes for the Next Maintainer

- `mod_version` is `2.4.0` in `gradle.properties`.
- Do not move or overwrite existing stable tags.
- Never change the codec of an existing payload ID. Add a new payload ID and negotiate support.
- Before a future release, choose the next version, update release-facing documentation, rebuild, inspect the jar metadata/icon, and perform the in-game checks above.
