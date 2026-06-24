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
import su.nightexpress.excellentcrates.hologram.HologramHandler;
import su.nightexpress.excellentcrates.hologram.HologramManager;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntity;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntityGroup;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Maintains per-player packet displays (preferred), safe Bukkit fallback displays and Bedrock block views. */
public final class CrateDisplayManager implements Listener {

    private static final int MAX_BLOCK_UPDATES_PER_SYNC = 2048;
    private static final int MAX_JAVA_DISPLAYS_PER_PLAYER = 2048;
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

    private HologramHandler packetHandler;
    private boolean packetMode;
    private boolean packetFallbackPending;
    private BukkitTask javaResyncTask;
    private BukkitTask bedrockResyncTask;

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

        this.packetHandler = plugin.getHologramManager().map(HologramManager::getPacketHandler).orElse(null);
        this.packetMode = Config.CRATE_PACKET_BASED_MODE.get() && this.packetHandler != null;
    }

    public void setup() {
        this.plugin.getPluginManager().registerEvents(this, this.plugin);
        this.cleanupStaleBukkitDisplays();
        this.crateManager.getCrates().forEach(this::spawnLoadedDisplays);

        if (Config.CRATE_PACKET_BASED_MODE.get() && !this.packetMode) {
            this.plugin.warn("Crate.Packet-Based_Mode is enabled, but no packet backend is available. Using safe Bukkit ItemDisplay fallback.");
        }

        if (this.packetMode) {
            this.javaResyncTask = this.plugin.getServer().getScheduler().runTaskTimer(
                this.plugin,
                () -> this.plugin.getServer().getOnlinePlayers().forEach(this::syncJavaModels),
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

        // Covers plugin reloads with players already online and normal startup race windows.
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () ->
            this.plugin.getServer().getOnlinePlayers().forEach(this::syncPlayer), 20L);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        if (this.javaResyncTask != null) this.javaResyncTask.cancel();
        if (this.bedrockResyncTask != null) this.bedrockResyncTask.cancel();

        this.plugin.getServer().getOnlinePlayers().forEach(this::restoreRealBlocks);
        new ArrayList<>(this.packetDisplays.keySet()).forEach(this::removeDisplay);
        new ArrayList<>(this.bukkitDisplays.keySet()).forEach(this::removeDisplay);
        this.displayOwners.clear();
        this.displaysByChunk.clear();
        this.packetViews.clear();
        this.playerChunks.clear();
        this.packetHandler = null;
    }

    public void refresh(@NotNull Crate crate) {
        this.removeOwnedDisplays(crate.getId());
        this.spawnLoadedDisplays(crate);
        this.plugin.getServer().getOnlinePlayers().forEach(this::syncPlayer);
    }

    public void remove(@NotNull Crate crate) {
        Set<WorldPos> positions = crate.getBlockPositions();
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (!this.isBedrock(player)) continue;
            for (WorldPos pos : positions) {
                Block block = pos.toBlock();
                if (block != null && block.getWorld() == player.getWorld() && pos.isChunkLoaded()) {
                    player.sendBlockChange(block.getLocation(), block.getBlockData());
                }
            }
        }
        this.removeOwnedDisplays(crate.getId());
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
        if (!crate.isDisplayEnabled() || !crate.isJavaDisplayEnabled()) return;
        crate.getBlockPositions().stream().filter(WorldPos::isChunkLoaded).forEach(pos -> this.createDisplay(crate, pos));
    }

    private void createDisplay(@NotNull Crate crate, @NotNull WorldPos pos) {
        if (this.bukkitDisplays.containsKey(pos) || this.packetDisplays.containsKey(pos)) return;

        Location location = this.getDisplayLocation(crate, pos);
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
    private Location getDisplayLocation(@NotNull Crate crate, @NotNull WorldPos pos) {
        Location base = pos.toLocation();
        if (base == null) return null;
        Location location = base.add(0.5D, crate.getJavaDisplayYOffset(), 0.5D);
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
        for (Map.Entry<WorldPos, UUID> entry : this.bukkitDisplays.entrySet()) {
            Entity entity = this.plugin.getServer().getEntity(entry.getValue());
            if (!(entity instanceof ItemDisplay display)) continue;
            Crate crate = this.getOwner(entry.getKey());
            if (crate == null) continue;

            if (this.canViewJavaModel(player, crate, display.getLocation())) player.showEntity(this.plugin, display);
            else player.hideEntity(this.plugin, display);
        }
    }

    private void syncJavaModels(@NotNull Player player) {
        if (!this.packetMode || this.packetFallbackPending || this.packetHandler == null || !player.isOnline()) return;

        Set<WorldPos> desired = this.collectNearbyDisplays(player);
        Set<WorldPos> current = this.packetViews.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>());

        for (WorldPos pos : new HashSet<>(current)) {
            FakeEntityGroup group = this.packetDisplays.get(pos);
            Crate crate = this.getOwner(pos);
            FakeEntity entity = group == null || group.getEntities().isEmpty() ? null : group.getEntities().getFirst();
            if (desired.contains(pos) && crate != null && entity != null && this.canViewJavaModel(player, crate, entity.getLocation())) continue;

            if (group != null) this.removePacketViewer(player, pos, group);
            else current.remove(pos);
        }

        for (WorldPos pos : desired) {
            FakeEntityGroup group = this.packetDisplays.get(pos);
            Crate crate = this.getOwner(pos);
            FakeEntity entity = group == null || group.getEntities().isEmpty() ? null : group.getEntities().getFirst();
            if (crate == null || entity == null || !this.canViewJavaModel(player, crate, entity.getLocation())) continue;

            boolean needSpawn = !group.isViewer(player);
            if (!needSpawn) {
                current.add(pos);
                continue;
            }
            try {
                this.packetHandler.sendItemDisplayPackets(player, entity, true, crate.getJavaDisplayItem(), (float) crate.getJavaDisplayScale());
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
                    if (this.packetDisplays.containsKey(pos)) result.add(pos);
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
        this.plugin.getServer().getOnlinePlayers().forEach(this::updateBukkitVisibility);
    }

    @Nullable
    private Crate getOwner(@NotNull WorldPos pos) {
        String owner = this.displayOwners.get(pos);
        return owner == null ? null : this.crateManager.getCrateById(owner);
    }

    private void syncPlayer(@NotNull Player player) {
        if (!player.isOnline()) return;
        if (this.packetMode) this.syncJavaModels(player);
        else this.updateBukkitVisibility(player);
        this.syncBedrockBlocks(player);
    }

    public void syncBedrockBlocks(@NotNull Player player) {
        if (!this.isBedrock(player)) return;

        int radius = Math.max(32, (this.plugin.getServer().getViewDistance() + 2) * 16);
        double maximumDistance = (double) radius * radius;
        int updates = 0;

        for (Crate crate : this.crateManager.getCrates()) {
            if (!crate.isDisplayEnabled() || !crate.isBedrockDisplayEnabled()) continue;

            for (WorldPos pos : crate.getBlockPositions()) {
                if (updates >= MAX_BLOCK_UPDATES_PER_SYNC) return;
                Location location = pos.toLocation();
                if (location == null || location.getWorld() != player.getWorld() || !pos.isChunkLoaded()) continue;
                if (location.distanceSquared(player.getLocation()) > maximumDistance) continue;

                player.sendBlockChange(location, this.createBedrockData(crate, pos));
                updates++;
            }
        }
    }

    private void restoreRealBlocks(@NotNull Player player) {
        if (!this.isBedrock(player)) return;
        for (Crate crate : this.crateManager.getCrates()) {
            for (WorldPos pos : crate.getBlockPositions()) {
                Block block = pos.toBlock();
                if (block != null && block.getWorld() == player.getWorld() && pos.isChunkLoaded()) {
                    player.sendBlockChange(block.getLocation(), block.getBlockData());
                }
            }
        }
    }

    private boolean isBedrock(@NotNull Player player) {
        return this.bedrockManager != null && this.bedrockManager.isBedrockPlayer(player);
    }

    @NotNull
    private BlockData createBedrockData(@NotNull Crate crate, @NotNull WorldPos pos) {
        BlockData data;
        try {
            Material material = Material.matchMaterial(crate.getBedrockBlock());
            data = material != null && material.isBlock() && !material.isAir()
                ? material.createBlockData()
                : Bukkit.createBlockData(crate.getBedrockBlock());
            if (data.getMaterial().isAir()) throw new IllegalArgumentException("air is not a display block");
        }
        catch (IllegalArgumentException exception) {
            data = Material.CHEST.createBlockData();
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
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.syncPlayer(player), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        this.playerChunks.remove(uuid);
        this.plugin.setResourcePackLoaded(uuid, false);
        this.packetViews.remove(uuid);
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
            if (!crate.isDisplayEnabled() || !crate.isJavaDisplayEnabled()) continue;
            List<WorldPos> positions = crate.getBlockPositions().stream().filter(pos -> isInChunk(pos, chunk)).toList();
            if (!positions.isEmpty()) hasDisplay = true;
            positions.forEach(pos -> this.createDisplay(crate, pos));
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
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (this.crateManager.getCrateByBlock(event.getBlock()) != null) event.setCancelled(true);
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
}
