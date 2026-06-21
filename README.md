# StorageGuide

Stop hunting through an entire storage room for one missing item. StorageGuide lets a server define one shared storage wall, assign items to chest slots, and highlight exactly where players should go.

StorageGuide is a Fabric client/server mod for Minecraft `26.1.2+`. Operators map a vertical storage-room wall into one-block double-chest cells, then assign items with an in-game editor. Players can hold an item or use the finder menu to highlight the matching chest slot.

## Features

- Server-backed storage guide shared by everyone on the server.
- One jar for both client and server.
- Operator-only grid creation and multi-item assignment.
- Shader-compatible in-world highlights for storage cells and lookup results.
- Live item finder with readable, clickable search results.
- Quick locate key with content-aware shulker-box routing.
- Optional server-side sloppiness detection for misplaced items.
- Version-aware networking with legacy packet fallbacks.
- One authoritative config file: `config/storageguide.json`.

## How It Works

StorageGuide treats one vertical side of your storage room as a grid. Each selected block in that wall represents one double chest slot. A chest slot can have multiple assigned items, but each item can belong to only one chest slot. The server stores the grid and item assignments; clients only request actions and render highlights locally.

## Requirements

- Minecraft `26.1.2+`
- Fabric Loader `0.19.2+`
- Fabric API `0.152.1+26.1.2`
- Java `25+`

## Installation

Install `storageguide-2.0.0.jar` on both the server and every client that wants to use StorageGuide.

The server stores the shared storage wall configuration in:

`config/storageguide.json`

## Controls

- `[` selects or edits the storage grid. Operators use this to create the grid or edit a highlighted cell.
- `O` opens the item finder menu.
- `P` locates the held item.

All keybinds are configurable from Minecraft's keybinds screen.

## For Operators

Create the grid:

1. Look at one corner of the storage wall and press the select/edit key.
2. Look at the opposite corner on the same vertical plane and press the key again.
3. The server creates one storage cell for every block in the selected rectangle.

Edit assignments:

1. Press the select/edit key to highlight the existing grid.
2. Look toward a highlighted cell and press the key again. StorageGuide targets the nearest grid cell along your line of sight, even when a sign or item frame is in front of it.
3. Check one or more items in the editor and save.

When an item is assigned to a chest, StorageGuide removes that same item from any other chest so lookup results stay unambiguous.

Escape, the pause menu, or rejoining exits edit mode without saving.

## For Players

- Hold an item and press the locate key to highlight its assigned chest slot.
- Hold a non-empty shulker box and press locate to find the one chest compatible with all its contents.
- Empty shulker boxes locate by their own exact color assignment.
- Open the finder menu and type to select from live, readable item results.
- Highlights are local to you; the server remains the source of truth.

If a held shulker contains items assigned to different chests, or contains an unassigned item, StorageGuide reports that it is incompatible and does not highlight a chest.

## Sloppiness Detector

Operators can enable optional misplaced-item detection:

```text
/storageguide sloppiness_detector on
/storageguide sloppiness_detector off
```

When enabled, the server announces when a player places an incompatible item in an assigned chest. Correctly packed shulker boxes are accepted in the chest matching their contents, and empty colored shulkers follow their own item assignments.

## Release Channels

StorageGuide uses two release channels:

- Stable: production-ready versions, tagged as `vX.Y.Z` and published to Modrinth as Release.
- Beta: test builds, tagged as `vX.Y.Z-beta.N` and published to Modrinth as Beta.

Current stable release: `v2.0.0`.

## Source And License

- Source: https://github.com/boyakhil978/StorageGuide
- License: MIT

## Building

```bash
./gradlew build
```

The release jar is generated at:

`build/libs/storageguide-<version>.jar`
