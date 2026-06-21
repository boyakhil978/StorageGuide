# StorageGuide

**Find any item in your storage room in seconds.**

StorageGuide turns your storage wall into a shared, searchable map. Hold an item or type its name and the mod highlights exactly where it belongs—no more opening chest after chest or memorizing a maze of signs.

Operators configure the room through a simple in-game editor, and every player uses the same server-managed organization. StorageGuide also understands packed shulker boxes, works with shader packs such as Photon, and can catch items placed in the wrong chest.

## Features

- **Find items instantly:** Search by name or locate the item already in your hand.
- **Organize as a team:** One server-managed storage layout stays consistent for everyone.
- **Pack shulkers intelligently:** Route full shulker boxes by their contents, with support for every color.
- **Spot misplaced items:** An optional sloppiness detector warns when something enters the wrong chest.
- **Keep your shaders:** Clear in-world highlights work with Iris shaders, including Photon.
- **Configure everything in-game:** Create the grid and assign multiple item types to each chest without editing files.
- **Run one jar everywhere:** Use the same Fabric mod on dedicated servers, LAN hosts, and clients.
- **Upgrade more safely:** Version-aware networking can fall back to older supported packet formats.

## Requirements

- Minecraft `26.1.2`
- Fabric Loader `0.19.2` or newer
- Fabric API `0.152.1+26.1.2` or a compatible newer build
- Java `25` or newer

## Installation

### Recommended: Modrinth

Install StorageGuide from its [Modrinth page](https://modrinth.com/mod/storageguide) using the Modrinth App or your preferred compatible launcher. Select the version matching your Minecraft installation, then install it on both the server and each client.

Fabric Loader and Fabric API are required. A launcher that supports dependency installation may offer to install them automatically.

### Manual Installation

1. Install Fabric Loader and Fabric API for the supported Minecraft version.
2. Download StorageGuide from [Modrinth](https://modrinth.com/mod/storageguide).
3. Put the downloaded jar in the server's `mods` folder.
4. Put the same jar in each player's client `mods` folder.
5. Start the server and join normally.

Installing StorageGuide on both sides enables its menus, keybinds, highlights, shulker inspection, and multiplayer compatibility features.

## Quick Start

1. An operator creates a grid over one vertical side of the storage room.
2. The operator opens each grid cell and assigns the items belonging in that chest.
3. Players press `O` and type an item name, or hold an item and press `~`.
4. StorageGuide highlights the matching chest locally for that player.

## Controls

| Default key | Action |
| --- | --- |
| `[` | Select the storage grid or edit a grid cell |
| `O` | Open the item finder |
| `~` / grave accent | Locate the held item |

The locate key is the grave-accent key below Escape on most keyboards; holding Shift produces `~`. Every keybind can be changed under Minecraft's Controls menu.

## Creating the Storage Grid

Grid creation requires operator permissions.

1. Look at one corner of the storage wall and press `[`.
2. Look at the diagonally opposite corner and press `[` again.
3. StorageGuide creates one cell for every block in that rectangle.

Both corners must lie on the same vertical X/Y or Z/Y plane. Each one-block grid cell represents one storage position, normally the block occupied by a chest or one half of a double chest.

Creating a new grid replaces the currently configured grid, so assign the wall only after choosing its final bounds.

## Assigning Items to Chests

1. Press `[` once to reveal the configured grid.
2. Look toward a highlighted cell and press `[` again.
3. Select all item types that belong in that chest.
4. Save the cell.

The editor selects the first applicable grid block along your line of sight. Signs and item frames mounted in front of a chest do not prevent selecting it.

The item list is built from normal creative inventory tabs and excludes operator-only technical entries. A chest may accept several item types, but assigning an item to a new cell removes that item from its previous cell so searches always have one destination.

Leaving the world, opening the pause menu, or pressing Escape cancels the active editing flow without saving an unfinished change.

## Finding an Item

Press `O` to open a blank search field. Results appear as you type and use clean item names without the `minecraft:` namespace. Select a result to close the menu and highlight its assigned chest.

To skip the menu, hold an item in either hand and press the grave-accent/`~` key. The main hand takes priority when both hands contain an item.

If no chest is assigned to the item, StorageGuide clears any previous result and reports that no matching cell exists.

## Shulker Boxes

Held-item lookup understands all colored and uncolored shulker boxes:

- A non-empty shulker is routed by its contents. Every non-empty slot must contain an item assigned to the same storage cell.
- Empty slots are ignored.
- A correctly packed shulker highlights the chest that accepts its contents, even when the shulker's own color is assigned elsewhere.
- An empty shulker is treated like a normal item and locates the chest assigned to that exact shulker color.
- If any contained item is unassigned, or the contents belong to different cells, no chest is highlighted and the player sees: `The shulker box has items incompatible with any chest configuration`.

## Highlights and Shaders

StorageGuide renders its own in-world cell markers instead of relying on vanilla block outlines. This keeps lookup and editing highlights visible with common Iris shader pipelines, including Photon and BSL.

Highlights are client-side and visible only to the player who requested them. They do not alter blocks, containers, or server lighting.

## Sloppiness Detector

The optional sloppiness detector announces when a player adds an incompatible item to a configured chest. It is disabled by default and requires operator permissions to configure.

```text
/storageguide sloppiness_detector
/storageguide sloppiness_detector on
/storageguide sloppiness_detector off
```

The command without `on` or `off` reports the current state. The setting is saved in `config/storageguide.json`.

The detector:

- checks newly added items rather than warning merely because an old misplaced item is already present;
- does not warn when items are removed;
- understands either half of a double chest;
- accepts a non-empty shulker when all its contents belong to that chest;
- treats an empty colored shulker according to the assignment for that exact shulker item; and
- applies a 30-second announcement cooldown per player and per chest to prevent chat spam.

Warnings are broadcast as `Sloppiness detected <player>!`.

## Multiplayer and Version Compatibility

The server owns the grid, assignments, and detector setting. Clients request actions and render the results they receive.

On connection, compatible clients and servers exchange their mod and protocol versions. Newer builds advertise supported packet types and use legacy request formats when the other side does not support the newer form. Unsupported custom packets are not sent blindly, preventing a format change from disconnecting an older peer.

For complete functionality, keep the server and clients on the same StorageGuide release whenever possible.

## Configuration and Backups

Whenever possible, configure StorageGuide in-game. Use `[` to create the storage grid and assign items, and use the `/storageguide sloppiness_detector` commands to manage misplaced-item detection. The in-game tools validate changes and keep assignments consistent automatically.

The server stores this configuration at:

```text
config/storageguide.json
```

The file contains the grid bounds, cell assignments, and sloppiness-detector setting. Manual editing is intended for advanced recovery or migration only. If it is necessary, stop the server first and make a backup before changing or restoring the file.

## Troubleshooting

- **The controls do nothing:** Confirm StorageGuide and Fabric API are installed on both the client and server. Also ensure no other control is bound to the same key in the game settings.
- **A player cannot edit the grid:** Grid creation and assignment editing require operator permissions.
- **No search result appears:** Only assigned items can produce a chest highlight.
- **A shulker is rejected:** Its non-empty contents must all be assigned to one storage cell.
- **A highlight is hidden by a shader:** Verify the client is running the current release. Photon compatibility uses the mod's dedicated highlight renderer.

## Building from Source

Clone the repository, install Java 25 or newer, and run:

```bash
./gradlew clean build
```

The distributable jar is written to `build/libs/storageguide-<version>.jar`.

## Releases, Source, and License

- Current stable release: `v2.0.1`
- Download: https://modrinth.com/mod/storageguide
- Stable tags: `vX.Y.Z`
- Beta tags: `vX.Y.Z-beta.N`
- Source: https://github.com/boyakhil978/StorageGuide
- License: MIT
- Author: Akhil Boyapati
