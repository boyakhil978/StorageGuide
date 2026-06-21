# Changelog

## v2.0.0 - Stable

### Item Finder

- Replaced the raw item-ID field with a live searchable results list.
- Added clickable item results and Enter-key selection of the first match.
- Added paging controls for larger result sets.
- Displayed clean item names such as `Dark Oak Planks` instead of `minecraft:dark_oak_planks`.
- Kept namespace information readable for modded items where it is needed to distinguish identical names.
- Made item searches case-insensitive in both the finder and cell assignment editor.

### Shulker Box Routing

- Added content-aware held-item lookup for the uncolored shulker box and every dyed shulker-box color.
- A non-empty shulker now locates a chest only when every non-empty contained stack belongs to the same configured storage cell.
- Empty shulker boxes continue to locate by their own exact item ID, allowing different colors to be assigned to different chests.
- Added a clear incompatibility message when a shulker contains unassigned items or items split across multiple chest configurations.
- Prevented incompatible shulkers from highlighting an arbitrary chest.

### Sloppiness Detection

- Added the optional server-side sloppiness detector introduced after `v1.0.0`.
- Added `/storageguide sloppiness_detector`, with `on` and `off` operator controls and persistent configuration.
- Added server-wide misplaced-item announcements with per-player and per-chest 30-second cooldowns.
- Added correct inspection of both halves of double chests.
- Made the detector understand shulker contents:
  - Correctly packed non-empty shulkers are accepted in the chest matching their contents.
  - Empty colored shulkers follow their exact color assignment.
  - Mixed, unassigned, or incorrectly placed shulkers are treated as incompatible.

### Rendering and Shader Compatibility

- Replaced the manual framebuffer render pass with Minecraft's native per-frame gizmo pipeline.
- Preserved filled, outlined, always-visible storage highlights.
- Improved compatibility with Iris shader packs, including Photon.
- Kept lookup highlights visible through walls while allowing shader pipelines to composite them normally.

### Grid Editing

- Changed existing-grid cell selection to ray-intersect configured cell bounds directly.
- The nearest applicable storage cell under the crosshair is selected even when signs, item frames, or similar decoration are in front of it.
- Updated the edit-mode hover highlight to use the same selection logic as the edit action.

### Networking Compatibility

- Added client/server exchange of the StorageGuide mod version and protocol version.
- Added payload capability checks before sending optional packets.
- Restored the original string-based `locate_held` packet codec for compatibility with older peers.
- Added separately named v2 packets for new held-stack behavior instead of changing existing packet formats.
- Added automatic fallback to legacy lookup packets when a peer does not advertise newer capabilities.
- Prevented the server from sending StorageGuide payloads to clients that do not advertise support for them.

### Metadata and Packaging

- Set the in-game author metadata to `Akhil Boyapati`.
- Verified the 512×512 StorageGuide icon is packaged at `assets/storageguide/icon.png`.
- Added a client-only `debugMode` setting in `config/storageguide-client.json` for routine networking messages.

## v1.0.0 - Stable

- Added server-backed storage grid configuration.
- Added one-block-per-double-chest vertical wall grids.
- Added operator-only grid creation and cell editing.
- Added graphical item assignment UI.
- Added support for assigning multiple items to one chest cell.
- Preserved uniqueness: one item can only belong to one chest cell at a time.
- Added item lookup by held item or finder menu.
- Added client-side grid and target highlights from server-owned state.
- Added one shared jar for client and server.
