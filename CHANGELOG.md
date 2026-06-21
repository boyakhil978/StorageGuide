# Changelog

## v2.2.0 - Stable

### Client Settings and Mod Menu

- Added an optional Mod Menu integration with a dedicated StorageGuide configuration screen.
- Kept Mod Menu optional: it is suggested for clients but is not required on dedicated servers.
- Added persistent client preferences in `config/storageguide-client.json`.
- Added a setting to enable or disable the held-item hotbar status.
- Added configurable colors for:
  - Located-chest world highlights.
  - Successful held-item lookups.
  - Missing or incompatible held-item lookups.
- Added RGB sliders, a live color preview, hexadecimal color labels, per-color defaults, and a full reset option.
- Client settings can be saved or cancelled without manually editing configuration files.

### Subtle Held-Item Status

- Added visual lookup feedback to the selected hotbar slot when the held-item locator is used.
- The status now appears only after pressing `~`; it is not displayed continuously during normal play.
- Preserved Minecraft's original selected-slot texture and recolored that texture instead of drawing a second border on top of it.
- Applied a deliberately subtle blend between the configured status color and the vanilla white texture.
- The status remains visible for 2.5 seconds and blends smoothly back to the vanilla texture during the final 600 milliseconds.
- Switching hotbar slots, hiding the HUD, entering spectator mode, disconnecting, or selecting an empty slot immediately cancels the status.
- A successful color indicates that the held item has one configured storage destination.
- A missing color indicates that the held item is unassigned or incompatible with the current storage configuration.
- Empty shulker boxes are evaluated using their exact shulker item and color assignment.
- Non-empty shulker boxes show success only when every non-empty contained stack resolves to the same configured storage cell.

### Operator Settings Menu

- Added an in-game operator settings screen for server-managed StorageGuide options.
- Added `/storageguide settings` as a direct way to open the menu.
- Added an Operator Settings button to the client configuration screen.
- Added GUI control for enabling or disabling the sloppiness detector.
- Kept the server authoritative: clients request changes, while the server validates permissions and persists accepted settings.
- Non-operators can open the menu in read-only mode to view the current setting.
- Permission explanations, save confirmations, and rejected updates are displayed inside the menu instead of producing StorageGuide chat messages.
- Disabled setting controls and the Save button when the player lacks operator permission.
- Added capability checks so operator-menu packets are only exchanged with clients and servers that support them.

### Networking and Compatibility

- Added separate payload IDs for requesting, opening, and updating operator settings.
- Added operator permission and status-message data to the operator settings response.
- Preserved the existing networking rule that packet codecs are never changed silently; new behavior uses new payload types.
- Continued checking payload support before sending optional client/server messages.

### Rendering Integration

- Added a client-side GUI mixin that intercepts only the vanilla selected-slot sprite draw.
- Used Minecraft's native sprite tint path so resource-pack texture shape and vanilla placement are retained.
- Left the original selected-slot rendering completely unchanged outside the brief status window.
- Kept configurable world-highlight colors on the existing shader-compatible gizmo renderer.

### Documentation and Maintenance

- Expanded the README with Mod Menu setup, client color settings, operator menu access, hotbar status behavior, and troubleshooting.
- Clarified that in-game configuration is preferred over manual JSON editing.
- Updated the project handoff with configuration defaults, packet responsibilities, verification steps, and remaining work.

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
