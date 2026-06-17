# StorageGuide

StorageGuide is a Fabric client/server mod for Minecraft `26.1.2+` that maps one vertical storage-room wall into one-block double-chest cells, then helps players find where an item belongs.

The same jar is used on the server and on clients. The server owns exactly one StorageGuide configuration file at `config/storageguide.json`; clients request selection, editing, and lookup actions through Fabric networking.

## Requirements

- Minecraft `26.1.2+`
- Fabric Loader `0.19.2+`
- Fabric API `0.152.1+26.1.2`
- Java `25+`

## Installation

Install `storageguide-1.0.0.jar` on both the server and every client that wants to use StorageGuide.

The server stores the shared storage wall configuration in:

`config/storageguide.json`

## Controls

- `[` selects/edits the storage grid. Operators use this to create the grid or edit a highlighted cell.
- `O` opens the item finder menu.
- `P` locates the held item.

All keybinds are configurable from Minecraft's keybinds screen.

## Operator Flow

If no server configuration exists, an operator presses the select/edit key while looking at one corner, then presses it again while looking at the opposite vertical corner. The selected corners must be on a vertical plane: either the same X coordinate or the same Z coordinate. Each block in that selected rectangle becomes one double-chest slot in the grid.

If a configuration already exists, pressing the select/edit key highlights the grid. Looking at a highlighted block and pressing the key again opens a graphical editor for that cell. Escape, the pause menu, or rejoining exits edit mode without saving.

The editor lists game items by readable names such as `emerald`; the server still saves valid item ids internally.

## Player Flow

Any player can press the locate key while holding an item. The server finds the configured cell and sends a highlight back to that player.

Any player can also open the item finder menu, type or search for an item, and request a highlight.

## Release Channels

StorageGuide uses two release channels:

- Stable: production-ready versions, tagged as `vX.Y.Z` and published to Modrinth as Release.
- Beta: test builds, tagged as `vX.Y.Z-beta.N` and published to Modrinth as Beta.

Current stable release: `v1.0.0`.

## Building

```bash
./gradlew build
```

The release jar is generated at:

`build/libs/storageguide-1.0.0.jar`
