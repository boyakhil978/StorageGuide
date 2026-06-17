# StorageGuide Plan

## Overview

StorageGuide is a Fabric client/server mod. The server owns one authoritative configuration file per server and clients use keybinds/screens to request selection, editing, and item lookup actions. Clients render the grid and target highlights locally from server-sent state.

The project is being prepared for Modrinth distribution with two channels: stable releases for normal use and beta releases for testing.

## Storage Model

- Operators select two corners while looking at blocks: a top-left corner and a bottom-right corner.
- The selected rectangle is treated as one storage-room side, so depth is intentionally ignored.
- The selected corners must share either the same X coordinate or the same Z coordinate, producing a vertical wall plane.
- Each block in the selected rectangle is one grid cell. Each grid cell represents one double-chest slot.
- A blank entry is created for every one-block cell in the server's `config/storageguide.json`.
- Each entry stores the cell origin, plane axis, one-block dimensions, and an optional assigned item id.

## User Flow

1. If no server grid exists, an operator presses the select/edit key on one corner, then presses it again on the opposite vertical corner.
2. The server validates operator permission, creates the grid, and writes `config/storageguide.json`.
3. If a grid already exists, an operator presses the select/edit key to highlight the grid, then looks at a cell and presses the key again.
4. The client opens a graphical cell editor; Escape aborts, Save/Clear sends the edit to the server, and the server validates operator permission before writing.
5. Any player can hold an item and press locate, or open the find menu, to request a server-backed highlight for that item.

## Implementation Notes

- The mod targets Minecraft 26.1.2+ using the new unobfuscated Fabric toolchain.
- The build uses Mojang official names, `net.fabricmc.fabric-loom`, Java 25+, and no Yarn mappings line.
- The same jar works on clients and servers with `environment: "*"`.
- The common entrypoint registers payload types, server receivers, and server lifecycle loading.
- The client entrypoint owns keybinds, screens, and local highlight rendering.
- Keybinds are registered through Fabric API's 26.1 `KeyMappingHelper` API.
- Configuration is JSON under the server's Minecraft config directory.
- Server-side edits are permission-gated with operator/singleplayer-owner checks.
- Rendering is done with Fabric's 26.1 level render extraction/drawing events and a small custom render pipeline.
- Networking uses Fabric custom payloads so the server stays authoritative.

## Release Plan

- Stable builds use semantic tags like `v1.0.0`.
- Beta builds use prerelease tags like `v1.1.0-beta.1`.
- Stable Modrinth uploads should use the Release channel.
- Beta Modrinth uploads should use the Beta channel.
- Because StorageGuide has shared networking and server-owned configuration, most feature updates should be installed on both client and server unless the changelog explicitly says client-only.
- Build release jars with `./gradlew build`; upload `build/libs/storageguide-<version>.jar`.
- Initial stable release target: `v1.0.0`.

## Current Limitations / Future Ideas

- The grid assumes one selected block equals one double-chest slot.
- The "top-left" label is user-oriented; internally the mod normalizes the selected rectangle and supports vertical XY and YZ planes.
- Assigning an already-assigned item moves that item to the newly selected cell.
- The current highlight is a translucent block-sized overlay, not a thin wire-only outline, because 26.1 rendering is moving toward explicit render pipelines and render states.
- Future polish could add richer item search/autocomplete, preview rendering while selecting, and a more detailed grid-management screen.
