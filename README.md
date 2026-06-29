# StorageGuide

**Stop searching chests. Start finding items.**

StorageGuide turns your Minecraft storage room into a clean, searchable, server-backed inventory map. Press a key, type an item name, or hold the item you want to store, and StorageGuide highlights the exact chest it belongs in.

It is built for real multiplayer bases: one operator defines the storage wall once, and every player gets the same guided organization without memorizing signs, opening random chests, or asking “where do we keep the quartz?” for the 900th time.

StorageGuide also handles packed shulker boxes, stays visible with shader packs such as Photon, and can even catch misplaced items with Big Brother, an optional server-side monitoring system for keeping shared storage tidy.

## Why StorageGuide?

- **No more chest roulette:** Search once and go straight to the right chest.
- **Perfect for shared bases:** Everyone follows the same server-managed storage layout.
- **Actually pleasant to configure:** In-game menus, item icons, hover help, color settings, and keybind controls keep setup approachable.
- **Shulker-aware:** Packed shulkers can be routed by their contents, not just by box color.
- **Tidy by default:** Big Brother can warn when items are placed where they do not belong.
- **Shader-friendly:** Dedicated highlight rendering keeps chest markers visible with common Iris shader pipelines.

## Features

- **Instant item lookup:** Search by name, select a result, and get a local chest highlight.
- **Held-item locating:** Hold an item and press `~` to locate its configured destination immediately.
- **Shared storage maps:** One server-managed layout keeps every client in sync.
- **Smart shulker handling:** Route full shulker boxes by compatible contents, with support for every color.
- **Big Brother monitoring:** Optionally detect misplaced items and keep a persistent, player-grouped history.
- **Custom announcements:** Operators can configure one or more Big Brother message templates with `{playername}` placeholders.
- **Shader-compatible highlights:** Clear in-world markers work with Iris shaders, including Photon.
- **Polished menus:** Scrollbars, item icons, tooltips, color pickers, and keybind controls are available in-game.
- **Safe multiplayer compatibility:** Version-aware networking avoids blindly sending unsupported packet formats and recommends upgrades for older clients.
- **One jar for every side:** Use the same Fabric mod on dedicated servers, LAN hosts, and clients.

## Requirements

- Minecraft `26.1.2`
- Fabric Loader `0.19.2` or newer
- Fabric API `0.152.1+26.1.2` or a compatible newer build
- Java `25` or newer
- [Mod Menu](https://modrinth.com/mod/modmenu) `18.0.0-beta.1` or newer (optional, recommended for client settings)

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
| `~` | Locate the held item |

Every keybind can be changed under Minecraft's Controls menu or directly from StorageGuide's client settings screen.

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
4. Press Save to apply the draft. The editor reports how many items were assigned to or removed from the chest.

The editor selects the first applicable grid block along your line of sight. Signs and item frames mounted in front of a chest do not prevent selecting it.

The item list is built from normal creative inventory tabs and excludes operator-only technical entries. Selected items are highlighted with row color feedback, and long lists use mouse-wheel scrolling with a scrollbar. A chest may accept several item types, but assigning an item to a new cell removes that item from its previous cell so searches always have one destination.

Leaving the world, opening the pause menu, or pressing Escape cancels the active editing flow without saving an unfinished change.

## Finding an Item

Press `O` to open a blank search field. Results appear as you type and use clean item names without the `minecraft:` namespace. Select a result to close the menu and highlight its assigned chest. The finder uses native in-game item icons, mouse-wheel scrolling, and a scrollbar when results overflow.

To skip the menu, hold an item in either hand and press the `~` key. The main hand takes priority when both hands contain an item.

If no chest is assigned to the item, StorageGuide clears any previous result and reports that no matching cell exists.

Pressing `~` briefly changes the selected hotbar frame to show the lookup status:

- Green means the selected item has a configured storage destination.
- Red means the selected item is not assigned to a chest.
- Non-empty shulker boxes are green only when all their contents belong to one configured chest.

The status briefly recolors the original Minecraft selected-slot texture, blends it back to normal after a short delay, and is cancelled immediately if you switch slots. Both status colors, the located-chest highlight color, and the indicator itself can be changed from StorageGuide's Mod Menu configuration screen.

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

## Big Brother

Optional Big Brother monitoring announces when a player adds an incompatible item to a configured chest. It is disabled by default and requires operator permissions to configure.

```text
/storageguide big_brother
/storageguide big_brother on
/storageguide big_brother off
/storageguide sloppiness_detector
/storageguide sloppiness_detector on
/storageguide sloppiness_detector off
```

The `sloppiness_detector` command remains as a compatibility alias. The command without `on` or `off` reports the current state. The setting is saved in `config/storageguide.json`.

Big Brother:

- checks newly added items rather than warning merely because an old misplaced item is already present;
- does not warn when items are removed;
- understands either half of a double chest;
- accepts a non-empty shulker when all its contents belong to that chest;
- treats an empty colored shulker according to the assignment for that exact shulker item; and
- records every detected instance in persistent history, even when no announcement is sent;
- allows operators to exclude individual configured chests from detection; and
- applies a configurable announcement cooldown per player and per chest to prevent chat spam.

By default, warnings are broadcast as `Big Brother caught <player> slacking off.` Operators can change the message in Operator Settings. Multiple templates can be separated with `|`; one is chosen each time Big Brother announces an event.

Anyone can open the history through StorageGuide's client settings or by running:

```text
/storageguide history
```

History opens as a player list, with a button for each player showing their number of caught instances. Selecting a player shows their details newest-first with item icons. The announcement cooldown does not remove or combine history entries.

## Multiplayer and Version Compatibility

The server owns the grid, assignments, and Big Brother setting. Clients request actions and render the results they receive.

On connection, compatible clients and servers exchange their mod and protocol versions. Newer builds advertise supported packet types and use legacy request formats when the other side does not support the newer form. Unsupported custom packets are not sent blindly, preventing a format change from disconnecting an older peer.

If a player joins with an older StorageGuide client than the server, StorageGuide recommends upgrading so they get the newest UI and compatibility fixes.

For complete functionality, keep the server and clients on the same StorageGuide release whenever possible.

## Configuration and Backups

Whenever possible, configure StorageGuide in-game:

- Open **Escape Menu → StorageGuide** or **Mod Menu → StorageGuide → Configure** to change the located-chest highlight, found-item hotbar color, missing-item hotbar color, keybinds, or hotbar indicator.
- Operators can select **Operator Settings** from that screen while connected to a server.
- Players can run `/storageguide` or `/storageguide settings` to open StorageGuide's client settings. Operators can run `/storageguide operator_settings` to open the server-owned operator settings directly.
- Operator Settings controls Big Brother, announcement cooldown, announcement templates, and whether joining clients must have StorageGuide installed.
- Use `[` to create the storage grid and assign items. Cell edits stay as a draft until Save is pressed, then the editor shows the assigned/removed item counts.
- A cell's edit screen can exclude that chest from Big Brother without removing its item assignments.
- The `/storageguide big_brother` commands are available for command-based management, and `/storageguide sloppiness_detector` remains as an alias.

The in-game tools validate changes and keep assignments consistent automatically.

Client colors are saved to:

```text
config/storageguide-client.json
```

The server stores this configuration at:

```text
config/storageguide.json
```

The file contains grid bounds, cell assignments and exclusions, operator settings, Big Brother message templates, and Big Brother history. Manual editing is intended for advanced recovery or migration only. If it is necessary, stop the server first and make a backup before changing or restoring the file.

## Troubleshooting

- **The controls do nothing:** Confirm StorageGuide and Fabric API are installed on both the client and server. Also ensure no other control is bound to the same key in the game settings.
- **StorageGuide has no Configure button:** Install Mod Menu on the client. It is optional and is not required on the server.
- **A player cannot edit the grid:** Grid creation and assignment editing require operator permissions.
- **Operator Settings is missing:** Join the server with operator permissions first, then open it from StorageGuide client settings or run `/storageguide operator_settings`.
- **Operator Settings says permission is required:** The menu remains viewable, but server settings can only be changed by an operator.
- **A client is disconnected when joining:** The server may require all joining players to install a compatible StorageGuide client.
- **StorageGuide recommends upgrading:** Your client is older than the server. Install the current StorageGuide version to avoid missing newer UI or packet support.
- **A chest never triggers Big Brother:** Check whether that cell is marked as excluded in its edit screen.
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

- Current development version: `v2.4.0`
- Latest stable release: `v2.3.0`
- Download: https://modrinth.com/mod/storageguide
- Stable tags: `vX.Y.Z`
- Beta tags: `vX.Y.Z-beta.N`
- Source: https://github.com/boyakhil978/StorageGuide
- License: MIT
- Author: Akhil Boyapati
