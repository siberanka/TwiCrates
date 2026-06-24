# TwiCrates

TwiCrates is a security-hardened ExcellentCrates fork focused on first-class Java and Bedrock presentation. It keeps the proven reward, cost, limit and opening pipeline intact while adding resource-pack crate models, Geyser/Floodgate-aware block views and native Bedrock forms.

Original project by NightExpress. TwiCrates fork development by siberanka.

## Highlights

- Packet-based, per-player Java resource-pack models using `ItemDisplay`, modern `Item_Model`, legacy `Custom_Model_Data`, scale, offset and yaw controls.
- Optional external model selection from BetterModel, ModelEngine and MythicMobs IDs through commands and paginated editor menus.
- Per-crate Bedrock/Geyser blocks. A Bedrock player can see a chest, barrel or another safe vanilla block where a Java player sees the resource-pack model.
- Placement-specific cardinal facing. The Java model and directional Bedrock block use the same stored direction.
- Native Bedrock crate overview, paginated reward browser, reward details and cost-selection forms through Geyser/Floodgate Cumulus.
- Resource-pack status gating: models can be hidden until a Java client reports that the pack loaded successfully.
- Automatic Bedrock re-synchronization after join, teleport, respawn, world/chunk movement and periodic server block refreshes.
- Existing crate rewards, costs, limits, cooldowns, openings, keys, holograms and particles remain on the ExcellentCrates pipeline.
- Backward-compatible Java packages/API and command aliases. `plugin.yml` also provides the `ExcellentCrates` capability for integrations.

## Safe display design

TwiCrates does not replace or break the real linked server block. The real block remains the authoritative interaction and collision point. Java players receive a lightweight, non-persistent model display; Bedrock players receive a client-side vanilla block view at the same coordinates.

Use `BARRIER` as the real linked block when possible. It is solid and visually empty on Java, so the model can occupy the space without spawning a removable armor stand or turning a display entity into the authoritative crate.

This design avoids item drops, model-entity pickup, piston desynchronization, ghost blocks and chunk-save duplication. Linked blocks are protected against breaking, explosions, piston movement and entity block conversion. Inventory-holder blocks are rejected by `/twicrate set` so linking a crate cannot trap or expose container contents.

## Requirements

- Java 21 or newer
- Spigot/Paper 1.21.8 or newer (compiled against 1.21.10 API)
- NightCore `2.16.1-fork`
- Optional: Geyser-Spigot and/or Floodgate for Bedrock detection and native forms
- Optional: PacketEvents (preferred) or ProtocolLib for packet-based Java models and holograms
- Optional: BetterModel, ModelEngine or MythicMobs for provider-backed model ID selection
- Optional: CraftEngine for custom item rewards and crate base items through NightCore's item bridge

TwiCrates currently follows the upstream plugin's non-Folia scheduler model.

## Quick setup

1. Install TwiCrates and its matching NightCore build.
2. Configure the crate's `Block.Display` section in `plugins/TwiCrates/crates/<crate>.yml`.
3. Place a barrier or another non-container solid block.
4. Look directly at the block and run `/twicrate set vote`.
5. Reload TwiCrates after manual YAML changes.

The placement faces toward the player who runs the command. Fine-tune model orientation with `Yaw_Offset`. TwiCrates writes detailed English comments into every saved crate YAML.

Packet rendering is enabled by default in `config.yml`:

```yaml
Crate:
  Packet-Based_Mode: true
```

When enabled, both Java crate models and holograms use the detected packet backend; PacketEvents is preferred when both integrations are installed. Crate models safely fall back to managed Bukkit `ItemDisplay` entities if neither backend is available or a packet backend fails at runtime. Setting it to `false` uses Bukkit entities for crate models, while holograms keep their existing packet implementation.

## Crate display example

```yaml
Block:
  Display:
    Enabled: true
    Default_Facing: SOUTH
    # Managed by TwiCrates. Each entry is x,y,z,world,DIRECTION.
    Facings: []
    Java:
      Enabled: true
      Models:
        Idle:
          Provider: item_model
          Model_Id:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_idle'
          Custom_Model_Data: 10001
        Opening:
          Enabled: true
          Provider: item_model
          Model_Id:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_opening'
          Custom_Model_Data: 10002
        Closing:
          Enabled: true
          Provider: item_model
          Model_Id:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_closing'
          Custom_Model_Data: 10003
      Scale: 1.0
      Y_Offset: 0.5
      Yaw_Offset: 0.0
      Require_Accepted_Resource_Pack: false
    Bedrock:
      Enabled: true
      Blocks:
        Idle: CHEST
        Opening: TRIAL_SPAWNER
        Closing: VAULT
      Forms:
        Enabled: true

Animation:
  # Earliest reward delivery time. Longer opening providers are not cut short.
  Reward_Delivery_Delay_Ticks: 60
  Closing_Model_Duration_Ticks: 20
```

Opening and closing models are per-player in packet mode: the opener sees the active phase while other players keep the idle model. If the opening model/block is disabled or empty, idle remains visible. If closing is absent, the display returns directly to idle after reward delivery. The Bukkit fallback applies the safest aggregate phase globally because server entities cannot carry different item metadata per viewer.

For Java model phases, `Provider: item_model` keeps the resource-pack `ItemDisplay` path. `Provider: bettermodel`, `modelengine` or `mythicmobs` stores the selected provider model id in `Model_Id`; if that provider is absent, not loaded yet or cannot expose the id safely, TwiCrates keeps the item-model fallback instead of crashing.

The crate editor exposes **Java & Bedrock Display**, **External Model Browser** and **CraftEngine Base Item** actions. The external model browser is paginated and cycles idle/opening/closing plus item_model/BetterModel/ModelEngine/MythicMobs providers. Reward item content has a **CraftEngine Items** browser for adding CraftEngine custom items directly. The existing **Opening Animation** dialog also edits reward-delivery and closing-state durations. All labels and Bedrock form text use the normal TwiCrates language-entry system.

Useful Bedrock block examples include `CHEST`, `BARREL`, `ENDER_CHEST`, `TRIAL_SPAWNER` and a full value such as `minecraft:chest[type=single,waterlogged=false,facing=north]`. Invalid, air or non-block values safely fall back to `CHEST`.

## Commands and permissions

- `/twicrate model <crate> <idle|opening|closing> <item_model|bettermodel|modelengine|mythicmobs> <id>` - sets the Java display model source with tab-completed provider IDs when the provider API is present.
- `/twicrate craftengine base <crate> <item-id>` - sets the crate base item from a CraftEngine custom item.
- `/twicrate craftengine reward <crate> <reward-id> <item-id> [amount]` - adds a CraftEngine custom item to an item reward.

- `/twicrate set <crate>` — links the targeted non-container block and records its facing.
- `/twicrate reload` — reloads the plugin and recreates displays.
- All original ExcellentCrates aliases remain available, including `/crates` and `/excellentcrates`.

The new placement/model permissions are `twicrates.command.set`, `twicrates.command.model` and `twicrates.command.craftengine`. Existing ExcellentCrates permissions remain unchanged for compatibility.

## Bedrock behavior

When a local Geyser or Floodgate API is available, Bedrock players receive:

- a configured vanilla block instead of the Java item model;
- the same north/east/south/west orientation as the Java model;
- native forms for crate details, paginated rewards, reward descriptions and cost selection;
- core restriction, permission, cooldown, inventory-space, affordability and opening-availability checks immediately before an opening;
- the normal Geyser-translated opening animation, hologram and particle pipeline after the form selection.

If the platform API is unavailable or forms are disabled for a crate, TwiCrates falls back to the upstream inventory interaction. No reward is issued from a form callback; forms only select an action, then the existing authoritative opening pipeline performs every validation and reward operation.

## Reliability and security controls

- All Bukkit world, entity, inventory and opening actions run on the server thread.
- Form callbacks revalidate player state, crate ownership and source location.
- Reward delay is enforced at the single authoritative opening completion point; costs are not consumed again and rewards are not duplicated by display transitions.
- Display phase state is scoped by player and physical crate location, bounded by active openings, and cleared on quit, chunk unload, reload and shutdown.
- A per-player action debounce prevents repeated Bedrock form responses from starting duplicate openings.
- Display scale and offsets are bounded; form text, reward pages and block updates are capped.
- Packet mode creates no server-side Java model entities. Per-player viewer sets are distance/chunk bounded and cleared on quit, chunk unload, reload and shutdown.
- The Bukkit fallback uses non-persistent, invulnerable, gravity-free displays tracked by UUID and removed on chunk unload/plugin shutdown.
- Online players are resynchronized after startup/reload and again after join, teleport, respawn, world change and chunk movement; a bounded periodic reconciliation repairs missed client packets.
- Player/resource-pack/form tracking is cleared on quit and shutdown.
- Invalid model materials, item-model keys and Bedrock block data fail closed to safe defaults.
- No shell, expression-language, reflection-selected command or user-controlled class loading is exposed by the new features.
- TwiCrates does not add Log4j and does not log raw form payloads.

These controls reduce the attack surface for memory leaks, dupes, command injection, crashes and lag. Production operators should still test their exact Geyser version, Bedrock resource pack, custom model geometry and opening providers on a staging server before rollout.

## Build

```powershell
$env:JAVA_HOME = 'F:\vds\Java\jdk-25.0.2+10'
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
mvn clean package
```

The plugin jar is produced under `target/TwiCrates-<version>.jar`.

## License

TwiCrates remains distributed under the repository's GPL-3.0 license. See `LICENSE`.
