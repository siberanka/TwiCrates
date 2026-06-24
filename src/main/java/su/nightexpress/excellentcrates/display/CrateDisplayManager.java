package su.nightexpress.excellentcrates.display;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.bedrock.BedrockManager;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.CrateManager;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.hologram.HologramHandler;
import su.nightexpress.excellentcrates.hologram.HologramManager;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntity;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntityGroup;
import su.nightexpress.excellentcrates.hooks.ExternalProviderBridge;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Maintains per-player packet displays (preferred), safe Bukkit fallback displays and Bedrock block views. */
public final class CrateDisplayManager implements Listener {

    private static final int MAX_BLOCK_UPDATES_PER_SYNC = 2048;
    private static final int MAX_JAVA_DISPLAYS_PER_PLAYER = 2048;
    private static final int MAX_DISPLAY_STATES_PER_PLAYER = 64;
    private static final int MAX_EXTERNAL_MODELS_PER_PLAYER = 128;
    private static final long JAVA_RESYNC_TICKS = 40L;
    private static final long BEDROCK_RESYNC_TICKS = 100L;

    private final CratesPlugin plugin;
    private final CrateManager crateManager;
    private final BedrockManager bedrockManager;
    private final NamespacedKey displayKey;
    private final Map<WorldPos, UUID> bukkitDisplays;
    private final Map<WorldPos, FakeEntityGroup> packetDisplays;
    private final Map<WorldPos, String> displayOwners;
    private final Map<DisplayChunk, Set<WorldPos>> displaysByChunk;
    private final Map<UUID, Set<WorldPos>> packetViews;
    private final Map<UUID, Long> playerChunks;
    private final Map<UUID, Map<WorldPos, DisplayState>> playerDisplayStates;
    private final Map<UUID, Map<WorldPos, String>> virtualBlockViews;
    private final Map<UUID, Map<WorldPos, ExternalModelView>> externalModelViews;
    private final Set<String> warnedExternalModelFailures;

    private HologramHandler packetHandler;
    private boolean packetMode;
    private boolean packetFallbackPending;
    private BukkitTask javaResyncTask;
    private BukkitTask bedrockResyncTask;
    private BukkitTask stateTickerTask;
    private boolean shutdown;

    public CrateDisplayManager(@NotNull CratesPlugin plugin,
                               @NotNull CrateManager crateManager,
                               @Nullable BedrockManager bedrockManager) {
        this.plugin = plugin;
        this.crateManager = crateManager;
        this.bedrockManager = bedrockManager;
        this.displayKey = new NamespacedKey(plugin, "crate_display");
        this.bukkitDisplays = new HashMap<>();
        this.packetDisplays = new HashMap<>();
        this.displayOwners = new HashMap<>();
        this.displaysByChunk = new HashMap<>();
        this.packetViews = new HashMap<>();
        this.playerChunks = new HashMap<>();
        this.playerDisplayStates = new HashMap<>();
        this.virtualBlockViews = new HashMap<>();
        this.externalModelViews = new HashMap<>();
        this.warnedExternalModelFailures = new HashSet<>();

        this.packetHandler = plugin.getHologramManager().map(HologramManager::getPacketHandler).orElse(null);
        this.packetMode = Config.CRATE_PACKET_BASED_MODE.get() && this.packetHandler != null;
    }

    public void setup() {
        this.shutdown = false;
        this.plugin.getPluginManager().registerEvents(this, this.plugin);
        this.cleanupStaleBukkitDisplays();
        this.crateManager.getCrates().forEach(this::spawnLoadedDisplays);

        if (Config.CRATE_PACKET_BASED_MODE.get() && !this.packetMode) {
            this.plugin.warn("Crate.Packet-Based_Mode is enabled, but no packet backend is available. Using safe Bukkit ItemDisplay fallback.");
        }

        if (this.packetMode || Config.CRATE_PACKET_BASED_MODE.get()) {
            this.javaResyncTask = this.plugin.getServer().getScheduler().runTaskTimer(
                this.plugin,
                () -> this.plugin.getServer().getOnlinePlayers().stream()
                    .filter(player -> !this.isBedrock(player))
                    .forEach(player -> {
                        this.syncJavaModels(player);
                        this.syncVirtualBlocks(player);
                    }),
                20L,
                JAVA_RESYNC_TICKS
            );
        }
        if (this.bedrockManager != null) {
            this.bedrockResyncTask = this.plugin.getServer().getScheduler().runTaskTimer(
                this.plugin,
                () -> this.plugin.getServer().getOnlinePlayers().stream()
                    .filter(this.bedrockManager::isBedrockPlayer)
                    .forEach(this::syncBedrockBlocks),
                20L,
                BEDROCK_RESYNC_TICKS
            );
        }

        // Hide physical blocks promptly, then repeat after platform/resource-pack hooks finish their join work.
        this.plugin.getServer().getScheduler().runTask(this.plugin, () ->
            this.plugin.getServer().getOnlinePlayers().forEach(this::syncPlayer));
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () ->
            this.plugin.getServer().getOnlinePlayers().forEach(this::syncPlayer), 20L);
    }

    public void shutdown() {
        this.shutdown = true;
        HandlerList.unregisterAll(this);
        if (this.javaResyncTask != null) this.javaResyncTask.cancel();
        if (this.bedrockResyncTask != null) this.bedrockResyncTask.cancel();
        if (this.stateTickerTask != null) this.stateTickerTask.cancel();

        this.plugin.getServer().getOnlinePlayers().forEach(this::restoreRealBlocks);
        new ArrayList<>(this.packetDisplays.keySet()).forEach(this::removeDisplay);
        new ArrayList<>(this.bukkitDisplays.keySet()).forEach(this::removeDisplay);
        this.displayOwners.clear();
        this.displaysByChunk.clear();
        this.packetViews.clear();
        this.playerChunks.clear();
        this.playerDisplayStates.clear();
        this.virtualBlockViews.clear();
        this.closeAllExternalModels();
        this.warnedExternalModelFailures.clear();
        this.packetHandler = null;
    }

    /** Switches only the opening player to the configured opening model/block for the physical source. */
    public void beginOpening(@NotNull Player player, @NotNull CrateSource source) {
        this.setOpeningState(player, source, DisplayPhase.OPENING);
    }

    /** Shows the closing phase after authoritative reward delivery, then returns the player to idle. */
    public void completeOpening(@NotNull Player player, @NotNull CrateSource source) {
        if (this.shutdown) return;
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.completeOpening(player, source));
            return;
        }

        WorldPos pos = source.getBlockPos();
        Crate crate = source.getCrate();
        if (pos == null || !this.isOwnedDisplay(crate, pos)) return;

        boolean hasClosing = this.isBedrock(player)
            ? !crate.getBedrockClosingBlock().isBlank()
            : crate.getJavaClosingModel().isEnabled();
        if (!hasClosing || crate.getClosingModelDurationTicks() <= 0) {
            this.clearOpeningState(player, pos);
            return;
        }

        this.putDisplayState(player, pos, new DisplayState(crate.getId(), DisplayPhase.CLOSING, crate.getClosingModelDurationTicks()));
        this.updateStateDisplay(player, pos);
        this.ensureStateTicker();
    }

    /** Returns a cancelled/refunded opening to idle without displaying the successful closing phase. */
    public void cancelOpening(@NotNull Player player, @NotNull CrateSource source) {
        if (this.shutdown) return;
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.cancelOpening(player, source));
            return;
        }
        WorldPos pos = source.getBlockPos();
        if (pos != null) this.clearOpeningState(player, pos);
    }

    private void setOpeningState(@NotNull Player player, @NotNull CrateSource source, @NotNull DisplayPhase phase) {
        if (this.shutdown) return;
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.setOpeningState(player, source, phase));
            return;
        }

        WorldPos pos = source.getBlockPos();
        Crate crate = source.getCrate();
        if (pos == null || !this.isOwnedDisplay(crate, pos)) return;
        this.putDisplayState(player, pos, new DisplayState(crate.getId(), phase, -1));
        this.updateStateDisplay(player, pos);
    }

    private void putDisplayState(@NotNull Player player, @NotNull WorldPos pos, @NotNull DisplayState state) {
        Map<WorldPos, DisplayState> states = this.playerDisplayStates.computeIfAbsent(player.getUniqueId(), key -> new LinkedHashMap<>());
        if (!states.containsKey(pos) && states.size() >= MAX_DISPLAY_STATES_PER_PLAYER) {
            WorldPos oldest = states.keySet().iterator().next();
            states.remove(oldest);
            this.updateStateDisplay(player, oldest);
        }
        states.put(pos, state);
    }

    private boolean isOwnedDisplay(@NotNull Crate crate, @NotNull WorldPos pos) {
        return crate.isDisplayEnabled() && crate.getId().equals(this.displayOwners.get(pos));
    }

    private void clearOpeningState(@NotNull Player player, @NotNull WorldPos pos) {
        Map<WorldPos, DisplayState> states = this.playerDisplayStates.get(player.getUniqueId());
        if (states == null || states.remove(pos) == null) return;
        if (states.isEmpty()) this.playerDisplayStates.remove(player.getUniqueId());
        this.updateStateDisplay(player, pos);
    }

    private void ensureStateTicker() {
        if (this.stateTickerTask != null || this.shutdown) return;
        this.stateTickerTask = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, this::tickDisplayStates, 1L, 1L);
    }

    private void tickDisplayStates() {
        List<StateTarget> expired = new ArrayList<>();
        this.playerDisplayStates.forEach((playerId, states) -> states.replaceAll((pos, state) -> {
            if (state.phase() != DisplayPhase.CLOSING) return state;
            int remaining = state.remainingTicks() - 1;
            if (remaining <= 0) {
                expired.add(new StateTarget(playerId, pos));
                return null;
            }
            return new DisplayState(state.crateId(), state.phase(), remaining);
        }));
        this.playerDisplayStates.values().forEach(states -> states.values().removeIf(java.util.Objects::isNull));
        this.playerDisplayStates.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        expired.forEach(target -> {
            Player player = this.plugin.getServer().getPlayer(target.playerId());
            if (player != null) this.updateStateDisplay(player, target.pos());
        });

        boolean hasClosing = this.playerDisplayStates.values().stream()
            .flatMap(states -> states.values().stream())
            .anyMatch(state -> state.phase() == DisplayPhase.CLOSING);
        if (!hasClosing && this.stateTickerTask != null) {
            this.stateTickerTask.cancel();
            this.stateTickerTask = null;
        }
    }

    private void updateStateDisplay(@NotNull Player player, @NotNull WorldPos pos) {
        if (!player.isOnline()) return;
        if (this.isBedrock(player)) {
            this.syncBedrockBlock(player, pos);
            return;
        }
        Crate stateCrate = this.getOwner(pos);
        JavaCrateModel stateModel = stateCrate == null ? null : this.getJavaModel(player, stateCrate, pos);
        Map<WorldPos, ExternalModelView> external = this.externalModelViews.get(player.getUniqueId());
        if ((stateModel != null && stateModel.getProvider() == CrateModelProvider.BETTERMODEL)
            || (external != null && external.containsKey(pos))) {
            this.syncJavaModels(player);
            return;
        }
        if (!this.packetMode) {
            this.updateBukkitModel(pos);
            return;
        }
        if (this.packetHandler == null) return;

        FakeEntityGroup group = this.packetDisplays.get(pos);
        Crate crate = this.getOwner(pos);
        if (group == null || crate == null || group.getEntities().isEmpty()) return;
        if (!group.isViewer(player)) {
            this.syncJavaModels(player);
            return;
        }

        this.removePacketViewer(player, pos, group);
        this.syncJavaModels(player);
    }

    private void updateBukkitModel(@NotNull WorldPos pos) {
        UUID entityId = this.bukkitDisplays.get(pos);
        Crate crate = this.getOwner(pos);
        if (entityId == null || crate == null) return;
        Entity entity = this.plugin.getServer().getEntity(entityId);
        if (!(entity instanceof ItemDisplay display)) return;

        DisplayPhase phase = this.playerDisplayStates.values().stream()
            .map(states -> states.get(pos))
            .filter(java.util.Objects::nonNull)
            .filter(state -> state.crateId().equals(crate.getId()))
            .map(DisplayState::phase)
            .min(java.util.Comparator.comparingInt(value -> value == DisplayPhase.OPENING ? 0 : 1))
            .orElse(null);
        JavaCrateModel model = phase == DisplayPhase.OPENING
            ? crate.getJavaOpeningModel()
            : phase == DisplayPhase.CLOSING ? crate.getJavaClosingModel() : crate.getJavaIdleModel();
        if (!model.isEnabled()) model = crate.getJavaIdleModel();
        display.setItemStack(model.createItem());
        Location location = this.getDisplayLocation(crate, pos, model);
        if (location != null) display.teleport(location);
    }

    public void refresh(@NotNull Crate crate) {
        this.restoreVirtualOwner(crate.getId());
        this.removeOwnedDisplays(crate.getId());
        this.spawnLoadedDisplays(crate);
        this.plugin.getServer().getOnlinePlayers().forEach(this::syncPlayer);
    }

    public void remove(@NotNull Crate crate) {
        this.restoreVirtualOwner(crate.getId());
        this.removeOwnedDisplays(crate.getId());
    }

    private void restoreVirtualOwner(@NotNull String crateId) {
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            Map<WorldPos, String> views = this.virtualBlockViews.get(player.getUniqueId());
            if (views == null) continue;

            views.entrySet().removeIf(entry -> {
                if (!entry.getValue().equals(crateId)) return false;
                this.restoreRealBlock(player, entry.getKey());
                return true;
            });
            if (views.isEmpty()) this.virtualBlockViews.remove(player.getUniqueId());
        }
    }

    private void removeOwnedDisplays(@NotNull String crateId) {
        this.displayOwners.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(crateId)) return false;
            this.removeDisplay(entry.getKey());
            return true;
        });
    }

    private void cleanupStaleBukkitDisplays() {
        for (World world : this.plugin.getServer().getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getPersistentDataContainer().has(this.displayKey, PersistentDataType.STRING)) {
                    display.remove();
                }
            }
        }
    }

    private void spawnLoadedDisplays(@NotNull Crate crate) {
        if (!crate.isDisplayEnabled()) return;
        crate.getBlockPositions().stream().filter(WorldPos::isChunkLoaded).forEach(pos -> {
            this.ensurePhysicalBarrier(pos);
            if (crate.isJavaDisplayEnabled()) this.createDisplay(crate, pos);
        });
    }

    private void ensurePhysicalBarrier(@NotNull WorldPos pos) {
        Block block = pos.toBlock();
        if (block != null && block.getType() != Material.BARRIER) {
            block.setType(Material.BARRIER, false);
        }
    }

    private void createDisplay(@NotNull Crate crate, @NotNull WorldPos pos) {
        if (this.bukkitDisplays.containsKey(pos) || this.packetDisplays.containsKey(pos)) return;

        Location location = this.getDisplayLocation(crate, pos, crate.getJavaIdleModel());
        if (location == null || !location.getChunk().isLoaded()) return;

        this.displayOwners.put(pos, crate.getId());
        this.displaysByChunk.computeIfAbsent(DisplayChunk.from(pos), key -> new HashSet<>()).add(pos);
        if (this.packetMode) {
            FakeEntityGroup group = new FakeEntityGroup(pos);
            group.addEntity(FakeEntity.create(location));
            this.packetDisplays.put(pos, group);
            return;
        }

        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, entity -> {
            entity.setItemStack(crate.getJavaDisplayItem());
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setInterpolationDuration(0);
            float scale = (float) crate.getJavaDisplayScale();
            entity.setTransformation(new Transformation(
                new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()
            ));
            entity.getPersistentDataContainer().set(this.displayKey, PersistentDataType.STRING, crate.getId());
        });

        this.bukkitDisplays.put(pos, display.getUniqueId());
        this.updateBukkitVisibility(crate, display);
    }

    @Nullable
    private Location getDisplayLocation(@NotNull Crate crate, @NotNull WorldPos pos, @NotNull JavaCrateModel model) {
        Location base = pos.toLocation();
        if (base == null) return null;
        Location location = base.add(0.5D, crate.getJavaDisplayYOffset() + model.getYOffset(), 0.5D);
        location.setYaw(yaw(crate.getDisplayFacing(pos)) + (float) crate.getJavaDisplayYawOffset());
        return location;
    }

    private void updateBukkitVisibility(@NotNull Crate crate, @NotNull ItemDisplay display) {
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (this.canViewJavaModel(player, crate, display.getLocation())) player.showEntity(this.plugin, display);
            else player.hideEntity(this.plugin, display);
        }
    }

    private void updateBukkitVisibility(@NotNull Player player) {
        Map<WorldPos, ExternalModelView> external = this.externalModelViews.get(player.getUniqueId());
        for (Map.Entry<WorldPos, UUID> entry : this.bukkitDisplays.entrySet()) {
            Entity entity = this.plugin.getServer().getEntity(entry.getValue());
            if (!(entity instanceof ItemDisplay display)) continue;
            Crate crate = this.getOwner(entry.getKey());
            if (crate == null) continue;

            if (external != null && external.containsKey(entry.getKey())) player.hideEntity(this.plugin, display);
            else if (this.canViewJavaModel(player, crate, display.getLocation())) player.showEntity(this.plugin, display);
            else player.hideEntity(this.plugin, display);
        }
    }

    private void syncJavaModels(@NotNull Player player) {
        if (!player.isOnline() || this.isBedrock(player)) return;

        Set<WorldPos> desired = this.collectNearbyDisplays(player);
        Set<WorldPos> external = this.syncExternalModels(player, desired);
        if (!this.packetMode || this.packetFallbackPending || this.packetHandler == null) {
            this.updateBukkitVisibility(player);
            return;
        }
        desired.removeAll(external);
        Set<WorldPos> current = this.packetViews.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>());

        for (WorldPos pos : new HashSet<>(current)) {
            FakeEntityGroup group = this.packetDisplays.get(pos);
            Crate crate = this.getOwner(pos);
            FakeEntity entity = group == null || group.getEntities().isEmpty() ? null : group.getEntities().getFirst();
            JavaCrateModel model = crate == null ? null : this.getJavaModel(player, crate, pos);
            Location location = crate == null || model == null ? null : this.getDisplayLocation(crate, pos, model);
            if (desired.contains(pos) && crate != null && entity != null && location != null && this.canViewJavaModel(player, crate, location)) continue;

            if (group != null) this.removePacketViewer(player, pos, group);
            else current.remove(pos);
        }

        for (WorldPos pos : desired) {
            FakeEntityGroup group = this.packetDisplays.get(pos);
            Crate crate = this.getOwner(pos);
            FakeEntity entity = group == null || group.getEntities().isEmpty() ? null : group.getEntities().getFirst();
            JavaCrateModel model = crate == null ? null : this.getJavaModel(player, crate, pos);
            Location location = crate == null || model == null ? null : this.getDisplayLocation(crate, pos, model);
            if (crate == null || entity == null || location == null || !this.canViewJavaModel(player, crate, location)) continue;

            boolean needSpawn = !group.isViewer(player);
            if (!needSpawn) {
                current.add(pos);
                continue;
            }
            try {
                this.packetHandler.sendItemDisplayPackets(player, new FakeEntity(entity.getId(), location), true,
                    this.getJavaDisplayItem(player, crate, pos), (float) crate.getJavaDisplayScale());
                group.addViewer(player);
                current.add(pos);
            }
            catch (RuntimeException | LinkageError exception) {
                this.requestPacketFallback(exception);
                return;
            }
        }

        if (current.isEmpty()) this.packetViews.remove(player.getUniqueId());
        else this.packetViews.put(player.getUniqueId(), current);
    }

    @NotNull
    private Set<WorldPos> syncExternalModels(@NotNull Player player, @NotNull Set<WorldPos> desired) {
        UUID playerId = player.getUniqueId();
        Map<WorldPos, ExternalModelView> views = this.externalModelViews.computeIfAbsent(playerId, key -> new LinkedHashMap<>());
        Map<WorldPos, ExternalModelSpec> specs = new LinkedHashMap<>();

        for (WorldPos pos : desired) {
            Crate crate = this.getOwner(pos);
            if (crate == null) continue;
            JavaCrateModel model = this.getJavaModel(player, crate, pos);
            Location location = this.getDisplayLocation(crate, pos, model);
            if (location == null || !this.canViewJavaModel(player, crate, location)) continue;

            if (model.getProvider() != CrateModelProvider.BETTERMODEL || model.getProviderModelId().isBlank()) continue;
            specs.put(pos, new ExternalModelSpec(crate.getId(), model.getProvider(), model.getProviderModelId(),
                model.getProviderState(), (float) crate.getJavaDisplayScale(), location));
            if (specs.size() >= MAX_EXTERNAL_MODELS_PER_PLAYER) break;
        }

        views.entrySet().removeIf(entry -> {
            ExternalModelSpec spec = specs.get(entry.getKey());
            if (spec != null && spec.signature().equals(entry.getValue().signature()) && entry.getValue().isAlive()) return false;
            entry.getValue().close();
            return true;
        });

        for (Map.Entry<WorldPos, ExternalModelSpec> entry : specs.entrySet()) {
            if (views.containsKey(entry.getKey())) continue;
            ExternalModelSpec spec = entry.getValue();
            ExternalProviderBridge.ModelCreation creation = ExternalProviderBridge.createViewerModel(
                spec.provider(), spec.modelId(), spec.state(), player, spec.location(), spec.scale());
            if (creation.handle().isPresent()) {
                views.put(entry.getKey(), new ExternalModelView(spec.signature(), creation.handle().get()));
                this.warnedExternalModelFailures.remove(spec.crateId() + '|' + spec.provider() + '|' + spec.modelId());
            }
            else {
                String warningKey = spec.crateId() + '|' + spec.provider() + '|' + spec.modelId();
                if (this.warnedExternalModelFailures.size() < 256 && this.warnedExternalModelFailures.add(warningKey)) {
                    this.plugin.warn("Could not render crate model '" + spec.modelId() + "' for crate '" + spec.crateId()
                        + "' with " + spec.provider().getId() + ": " + creation.failure());
                }
            }
        }

        if (views.isEmpty()) this.externalModelViews.remove(playerId);
        return new HashSet<>(views.keySet());
    }

    @NotNull
    private JavaCrateModel getJavaModel(@NotNull Player player, @NotNull Crate crate, @NotNull WorldPos pos) {
        DisplayState state = this.getDisplayState(player, crate, pos);
        if (state == null) return crate.getJavaIdleModel();
        return switch (state.phase()) {
            case OPENING -> crate.getJavaOpeningModel().isEnabled() ? crate.getJavaOpeningModel() : crate.getJavaIdleModel();
            case CLOSING -> crate.getJavaClosingModel().isEnabled() ? crate.getJavaClosingModel() : crate.getJavaIdleModel();
        };
    }

    @NotNull
    private Set<WorldPos> collectNearbyDisplays(@NotNull Player player) {
        Set<WorldPos> result = new HashSet<>();
        int chunkRadius = Math.max(2, this.plugin.getServer().getViewDistance() + 2);
        int centerX = player.getLocation().getBlockX() >> 4;
        int centerZ = player.getLocation().getBlockZ() >> 4;
        String worldName = player.getWorld().getName();

        for (int x = centerX - chunkRadius; x <= centerX + chunkRadius; x++) {
            for (int z = centerZ - chunkRadius; z <= centerZ + chunkRadius; z++) {
                Set<WorldPos> positions = this.displaysByChunk.get(new DisplayChunk(worldName, x, z));
                if (positions == null) continue;
                for (WorldPos pos : positions) {
                    if (this.displayOwners.containsKey(pos)) result.add(pos);
                    if (result.size() >= MAX_JAVA_DISPLAYS_PER_PLAYER) return result;
                }
            }
        }
        return result;
    }

    private boolean canViewJavaModel(@NotNull Player player, @NotNull Crate crate, @NotNull Location location) {
        if (this.isBedrock(player) || player.getWorld() != location.getWorld()) return false;
        if (crate.isJavaDisplayRequirePack() && !this.plugin.hasLoadedResourcePack(player.getUniqueId())) return false;

        int radius = Math.max(32, (this.plugin.getServer().getViewDistance() + 2) * 16);
        World world = location.getWorld();
        return world != null
            && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
            && location.distanceSquared(player.getLocation()) <= (double) radius * radius;
    }

    @NotNull
    private ItemStack getJavaDisplayItem(@NotNull Player player, @NotNull Crate crate, @NotNull WorldPos pos) {
        DisplayState state = this.getDisplayState(player, crate, pos);
        if (state == null) return crate.getJavaDisplayItem();
        return switch (state.phase()) {
            case OPENING -> crate.getJavaOpeningDisplayItem();
            case CLOSING -> crate.getJavaClosingDisplayItem();
        };
    }

    @Nullable
    private DisplayState getDisplayState(@NotNull Player player, @NotNull Crate crate, @NotNull WorldPos pos) {
        Map<WorldPos, DisplayState> states = this.playerDisplayStates.get(player.getUniqueId());
        if (states == null) return null;
        DisplayState state = states.get(pos);
        return state != null && state.crateId().equals(crate.getId()) ? state : null;
    }

    private void removePacketViewer(@NotNull Player player, @NotNull WorldPos pos, @NotNull FakeEntityGroup group) {
        if (!group.isViewer(player)) return;
        group.removeViewer(player);
        Set<WorldPos> views = this.packetViews.get(player.getUniqueId());
        if (views != null) {
            views.remove(pos);
            if (views.isEmpty()) this.packetViews.remove(player.getUniqueId());
        }
        try {
            if (this.packetHandler != null) this.packetHandler.sendDestroyEntityPacket(player, group.getEntityIDs());
        }
        catch (RuntimeException | LinkageError exception) {
            this.requestPacketFallback(exception);
        }
    }

    private void removeDisplay(@NotNull WorldPos pos) {
        this.playerDisplayStates.values().forEach(states -> states.remove(pos));
        this.playerDisplayStates.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        this.externalModelViews.values().forEach(views -> {
            ExternalModelView view = views.remove(pos);
            if (view != null) view.close();
        });
        this.externalModelViews.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        FakeEntityGroup packetGroup = this.packetDisplays.remove(pos);
        if (packetGroup != null) this.destroyPacketGroup(pos, packetGroup);

        UUID entityId = this.bukkitDisplays.remove(pos);
        if (entityId != null) {
            Entity entity = this.plugin.getServer().getEntity(entityId);
            if (entity != null) entity.remove();
        }

        DisplayChunk chunk = DisplayChunk.from(pos);
        Set<WorldPos> indexed = this.displaysByChunk.get(chunk);
        if (indexed != null) {
            indexed.remove(pos);
            if (indexed.isEmpty()) this.displaysByChunk.remove(chunk);
        }
    }

    private void destroyPacketGroup(@NotNull WorldPos pos, @NotNull FakeEntityGroup group) {
        if (this.packetHandler != null) {
            for (UUID viewerId : group.getViewerIds()) {
                Player viewer = this.plugin.getServer().getPlayer(viewerId);
                if (viewer == null) continue;
                try {
                    this.packetHandler.sendDestroyEntityPacket(viewer, group.getEntityIDs());
                }
                catch (RuntimeException | LinkageError ignored) {
                    // The backend is already failing; local viewer state is still cleared below.
                }
            }
        }
        this.packetViews.values().forEach(views -> views.remove(pos));
        this.packetViews.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        group.clearViewers();
    }

    private void requestPacketFallback(@NotNull Throwable throwable) {
        if (this.packetFallbackPending || !this.packetMode) return;
        this.packetFallbackPending = true;
        this.plugin.error("Packet crate rendering failed; switching to Bukkit ItemDisplay fallback: " + throwable.getClass().getSimpleName());
        this.plugin.getServer().getScheduler().runTask(this.plugin, this::activateBukkitFallback);
    }

    private void closeExternalModels(@NotNull UUID playerId) {
        Map<WorldPos, ExternalModelView> views = this.externalModelViews.remove(playerId);
        if (views != null) views.values().forEach(ExternalModelView::close);
    }

    private void closeAllExternalModels() {
        this.externalModelViews.values().forEach(views -> views.values().forEach(ExternalModelView::close));
        this.externalModelViews.clear();
    }

    private void activateBukkitFallback() {
        if (!this.packetMode) return;
        this.packetMode = false;
        if (this.javaResyncTask != null) this.javaResyncTask.cancel();

        new ArrayList<>(this.packetDisplays.entrySet()).forEach(entry -> this.destroyPacketGroup(entry.getKey(), entry.getValue()));
        this.packetDisplays.clear();
        this.displayOwners.clear();
        this.displaysByChunk.clear();
        this.packetViews.clear();
        this.crateManager.getCrates().forEach(this::spawnLoadedDisplays);
        this.playerDisplayStates.values().stream().flatMap(states -> states.keySet().stream()).distinct().forEach(this::updateBukkitModel);
        this.plugin.getServer().getOnlinePlayers().forEach(this::updateBukkitVisibility);
    }

    @Nullable
    private Crate getOwner(@NotNull WorldPos pos) {
        String owner = this.displayOwners.get(pos);
        return owner == null ? null : this.crateManager.getCrateById(owner);
    }

    private void syncPlayer(@NotNull Player player) {
        if (!player.isOnline()) return;
        this.syncJavaModels(player);
        this.syncVirtualBlocks(player);
    }

    public void syncBedrockBlocks(@NotNull Player player) {
        if (!this.isBedrock(player)) return;

        this.syncVirtualBlocks(player);
    }

    private void syncVirtualBlocks(@NotNull Player player) {
        if (!player.isOnline()) return;

        boolean bedrock = this.isBedrock(player);
        boolean virtualizeJava = !bedrock && Config.CRATE_PACKET_BASED_MODE.get();
        Map<WorldPos, Crate> desired = new LinkedHashMap<>();

        int radius = Math.max(32, (this.plugin.getServer().getViewDistance() + 2) * 16);
        double maximumDistance = (double) radius * radius;

        for (Crate crate : this.crateManager.getCrates()) {
            if (!crate.isDisplayEnabled()) continue;
            if (bedrock && !crate.isBedrockDisplayEnabled()) continue;
            if (virtualizeJava && !crate.isJavaDisplayEnabled()) continue;
            if (!bedrock && !virtualizeJava) continue;

            for (WorldPos pos : crate.getBlockPositions()) {
                if (desired.size() >= MAX_BLOCK_UPDATES_PER_SYNC) break;
                Location location = pos.toLocation();
                if (location == null || location.getWorld() != player.getWorld() || !pos.isChunkLoaded()) continue;
                if (location.distanceSquared(player.getLocation()) > maximumDistance) continue;
                desired.put(pos, crate);
            }
        }

        UUID playerId = player.getUniqueId();
        Map<WorldPos, String> current = this.virtualBlockViews.computeIfAbsent(playerId, key -> new LinkedHashMap<>());
        current.entrySet().removeIf(entry -> {
            Crate crate = desired.get(entry.getKey());
            if (crate != null && crate.getId().equals(entry.getValue())) return false;
            this.restoreRealBlock(player, entry.getKey());
            return true;
        });

        BlockData javaBarrier = Material.BARRIER.createBlockData();
        desired.forEach((pos, crate) -> {
            Location location = pos.toLocation();
            if (location == null) return;
            BlockData data = bedrock ? this.createBedrockData(player, crate, pos) : javaBarrier;
            player.sendBlockChange(location, data);
            current.put(pos, crate.getId());
        });

        if (current.isEmpty()) this.virtualBlockViews.remove(playerId);
    }

    private void syncBedrockBlock(@NotNull Player player, @NotNull WorldPos pos) {
        Crate crate = this.getOwner(pos);
        Location location = pos.toLocation();
        if (crate == null) {
            Block block = pos.toBlock();
            if (block != null) crate = this.crateManager.getCrateByBlock(block);
        }
        if (crate == null || location == null || location.getWorld() != player.getWorld() || !pos.isChunkLoaded()) return;
        player.sendBlockChange(location, this.createBedrockData(player, crate, pos));
        String crateId = crate.getId();
        this.virtualBlockViews.computeIfAbsent(player.getUniqueId(), key -> new LinkedHashMap<>()).put(pos, crateId);
    }

    private void restoreRealBlocks(@NotNull Player player) {
        Map<WorldPos, String> views = this.virtualBlockViews.remove(player.getUniqueId());
        if (views == null) return;
        views.keySet().forEach(pos -> this.restoreRealBlock(player, pos));
    }

    private void restoreRealBlock(@NotNull Player player, @NotNull WorldPos pos) {
        Block block = pos.toBlock();
        if (block != null && block.getWorld() == player.getWorld() && pos.isChunkLoaded()) {
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
    }

    private boolean isBedrock(@NotNull Player player) {
        return this.bedrockManager != null && this.bedrockManager.isBedrockPlayer(player);
    }

    @NotNull
    private BlockData createBedrockData(@NotNull Player player, @NotNull Crate crate, @NotNull WorldPos pos) {
        String idleBlock = crate.getBedrockIdleBlock();
        String configuredBlock = idleBlock;
        DisplayState state = this.getDisplayState(player, crate, pos);
        if (state != null) {
            String phaseBlock = state.phase() == DisplayPhase.OPENING
                ? crate.getBedrockOpeningBlock()
                : crate.getBedrockClosingBlock();
            if (!phaseBlock.isBlank()) configuredBlock = phaseBlock;
        }

        BlockData data;
        try {
            Material material = Material.matchMaterial(configuredBlock);
            data = material != null && material.isBlock() && !material.isAir()
                ? material.createBlockData()
                : Bukkit.createBlockData(configuredBlock);
            if (data.getMaterial().isAir()) throw new IllegalArgumentException("air is not a display block");
        }
        catch (IllegalArgumentException exception) {
            try {
                data = Bukkit.createBlockData(idleBlock);
                if (data.getMaterial().isAir()) data = Material.CHEST.createBlockData();
            }
            catch (IllegalArgumentException ignored) {
                data = Material.CHEST.createBlockData();
            }
        }

        BlockFace facing = crate.getDisplayFacing(pos);
        if (data instanceof Directional directional && directional.getFaces().contains(facing)) {
            directional.setFacing(facing);
        }
        else if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(facing);
        }
        return data;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.syncPlayer(player));
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.syncPlayer(player), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        this.playerChunks.remove(uuid);
        this.plugin.setResourcePackLoaded(uuid, false);
        this.packetViews.remove(uuid);
        this.virtualBlockViews.remove(uuid);
        this.closeExternalModels(uuid);
        Map<WorldPos, DisplayState> removedStates = this.playerDisplayStates.remove(uuid);
        if (!this.packetMode && removedStates != null) removedStates.keySet().forEach(this::updateBukkitModel);
        this.packetDisplays.values().forEach(group -> group.removeViewer(player));
        if (this.bedrockManager != null) this.bedrockManager.forgetPlayer(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResourcePack(PlayerResourcePackStatusEvent event) {
        String status = event.getStatus().name();
        if (status.equals("SUCCESSFULLY_LOADED")) {
            this.plugin.setResourcePackLoaded(event.getPlayer().getUniqueId(), true);
        }
        else if (status.equals("DECLINED") || status.startsWith("FAILED_") || status.equals("INVALID_URL") || status.equals("DISCARDED")) {
            this.plugin.setResourcePackLoaded(event.getPlayer().getUniqueId(), false);
        }
        else {
            // ACCEPTED/DOWNLOADED are intermediate states. Do not hide an already loaded model while another pack is negotiated.
            return;
        }
        this.syncPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        long chunk = chunkKey(to.getBlockX() >> 4, to.getBlockZ() >> 4);
        Long previous = this.playerChunks.put(event.getPlayer().getUniqueId(), chunk);
        if (previous == null || previous != chunk) this.syncPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        this.scheduleSync(event.getPlayer(), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        this.scheduleSync(event.getPlayer(), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        this.scheduleSync(event.getPlayer(), 2L);
    }

    private void scheduleSync(@NotNull Player player, long delay) {
        this.playerChunks.remove(player.getUniqueId());
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.syncPlayer(player), delay);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        boolean hasDisplay = false;
        for (Crate crate : this.crateManager.getCrates()) {
            if (!crate.isDisplayEnabled()) continue;
            List<WorldPos> positions = crate.getBlockPositions().stream().filter(pos -> isInChunk(pos, chunk)).toList();
            positions.forEach(this::ensurePhysicalBarrier);
            if (!positions.isEmpty()) hasDisplay = true;
            if (crate.isJavaDisplayEnabled()) positions.forEach(pos -> this.createDisplay(crate, pos));
        }
        if (!hasDisplay) return;
        this.plugin.getServer().getOnlinePlayers().stream()
            .filter(player -> player.getWorld() == chunk.getWorld())
            .forEach(this::syncPlayer);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Set<WorldPos> positions = new java.util.HashSet<>();
        positions.addAll(this.packetDisplays.keySet());
        positions.addAll(this.bukkitDisplays.keySet());
        positions.stream().filter(pos -> isInChunk(pos, event.getChunk())).toList().forEach(pos -> {
            this.removeDisplay(pos);
            this.displayOwners.remove(pos);
            this.virtualBlockViews.values().forEach(views -> views.remove(pos));
        });
        this.virtualBlockViews.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (this.crateManager.getCrateByBlock(event.getBlock()) == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.syncVirtualBlocks(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (this.crateManager.getCrateByBlock(event.getBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> this.crateManager.getCrateByBlock(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> this.crateManager.getCrateByBlock(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (this.movesCrate(event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (this.movesCrate(event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    private boolean movesCrate(@NotNull List<Block> blocks, @NotNull BlockFace direction) {
        return blocks.stream().anyMatch(block -> this.crateManager.getCrateByBlock(block) != null
            || this.crateManager.getCrateByBlock(block.getRelative(direction)) != null);
    }

    private static boolean isInChunk(@NotNull WorldPos pos, @NotNull Chunk chunk) {
        World world = pos.getWorld();
        return world == chunk.getWorld() && (pos.getX() >> 4) == chunk.getX() && (pos.getZ() >> 4) == chunk.getZ();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static float yaw(@NotNull BlockFace face) {
        return switch (face) {
            case WEST -> 90F;
            case NORTH -> 180F;
            case EAST -> -90F;
            default -> 0F;
        };
    }

    private record DisplayChunk(String worldName, int x, int z) {

        @NotNull
        private static DisplayChunk from(@NotNull WorldPos pos) {
            return new DisplayChunk(pos.getWorldName(), pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    private enum DisplayPhase {
        OPENING,
        CLOSING
    }

    private record DisplayState(@NotNull String crateId, @NotNull DisplayPhase phase, int remainingTicks) {

    }

    private record StateTarget(@NotNull UUID playerId, @NotNull WorldPos pos) {

    }

    private record ExternalModelSignature(@NotNull String crateId,
                                          @NotNull CrateModelProvider provider,
                                          @NotNull String modelId,
                                          @NotNull String state,
                                          float scale,
                                          double y) {

    }

    private record ExternalModelSpec(@NotNull String crateId,
                                     @NotNull CrateModelProvider provider,
                                     @NotNull String modelId,
                                     @NotNull String state,
                                     float scale,
                                     @NotNull Location location) {

        @NotNull
        private ExternalModelSignature signature() {
            return new ExternalModelSignature(this.crateId, this.provider, this.modelId, this.state, this.scale, this.location.getY());
        }
    }

    private record ExternalModelView(@NotNull ExternalModelSignature signature,
                                     @NotNull ExternalProviderBridge.ModelHandle handle) {

        private void close() {
            this.handle.close();
        }

        private boolean isAlive() {
            return this.handle.isAlive();
        }
    }
}
