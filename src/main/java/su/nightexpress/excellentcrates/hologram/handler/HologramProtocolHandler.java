package su.nightexpress.excellentcrates.hologram.handler;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntity;
import su.nightexpress.nightcore.util.text.night.NightMessage;

import java.util.*;
import java.util.function.Consumer;

public class HologramProtocolHandler extends AbstractHologramHandler {

    private final ProtocolManager protocolManager;

    public HologramProtocolHandler() {
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    private void sendPacket(@NotNull Player player, @NotNull PacketContainer container) {
        this.protocolManager.sendServerPacket(player, container);
    }

    private void broadcastPacket(@NotNull PacketContainer packet) {
        this.protocolManager.broadcastServerPacket(packet);
    }

    @Override
    public void sendHologramPackets(@NotNull Player player, @NotNull FakeEntity entity, boolean needSpawn, @NotNull String textLine) {
        Object component = WrappedChatComponent.fromJson(NightMessage.asJson(textLine)).getHandle();

        PacketContainer dataPacket = this.createMetadataPacket(entity.getId(), metadata -> {
            metadata.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(15, WrappedDataWatcher.Registry.get(Byte.class)), this.billboard);
            metadata.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(23, WrappedDataWatcher.Registry.getChatComponentSerializer()), component);
            metadata.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(24, WrappedDataWatcher.Registry.get(Integer.class)), this.lineWidth);
            metadata.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(25, WrappedDataWatcher.Registry.get(Integer.class)), this.backgroundColor);
            metadata.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(26, WrappedDataWatcher.Registry.get(Byte.class)), this.textOpacity);
            metadata.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(27, WrappedDataWatcher.Registry.get(Byte.class)), this.textBitmask);
        });

        if (needSpawn) {
            this.sendPacket(player, this.createSpawnPacket(entity));
        }

        this.sendPacket(player, dataPacket);
    }

    @Override
    public void sendItemDisplayPackets(@NotNull Player player,
                                       @NotNull FakeEntity entity,
                                       boolean needSpawn,
                                       @NotNull ItemStack itemStack,
                                       float scale) {
        PacketContainer dataPacket = this.createMetadataPacket(entity.getId(), metadata -> {
            metadata.setObject(
                new WrappedDataWatcher.WrappedDataWatcherObject(12, WrappedDataWatcher.Registry.getVectorSerializer()),
                new org.joml.Vector3f(scale, scale, scale)
            );
            metadata.setObject(
                new WrappedDataWatcher.WrappedDataWatcherObject(23, WrappedDataWatcher.Registry.getItemStackSerializer(false)),
                MinecraftReflection.getMinecraftItemStack(itemStack)
            );
            metadata.setObject(
                new WrappedDataWatcher.WrappedDataWatcherObject(24, WrappedDataWatcher.Registry.get(Byte.class)),
                (byte) org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED.ordinal()
            );
        });

        if (needSpawn) {
            this.sendPacket(player, this.createSpawnPacket(entity, EntityType.ITEM_DISPLAY));
        }
        this.sendPacket(player, dataPacket);
    }

    @Override
    public void sendDestroyEntityPacket(@NotNull Player player, @NotNull Set<Integer> idList) {
        this.sendPacket(player, this.createDestroyPacket(idList));
    }

    @Override
    public void sendDestroyEntityPacket(@NotNull Set<Integer> idList) {
        this.broadcastPacket(this.createDestroyPacket(idList));
    }

    @NotNull
    private PacketContainer createDestroyPacket(@NotNull Set<Integer> list) {
        PacketContainer container = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);
        container.getIntLists().write(0, new ArrayList<>(list));

        return container;
    }

    @NotNull
    private PacketContainer createSpawnPacket(@NotNull FakeEntity entity) {
        return this.createSpawnPacket(entity, EntityType.TEXT_DISPLAY);
    }

    @NotNull
    private PacketContainer createSpawnPacket(@NotNull FakeEntity entity, @NotNull EntityType entityType) {
        Location location = entity.getLocation();

        PacketContainer container = new PacketContainer(PacketType.Play.Server.SPAWN_ENTITY);
        container.getIntegers().write(0, entity.getId());
        container.getUUIDs().write(0, UUID.randomUUID());
        container.getEntityTypeModifier().write(0, entityType);
        container.getDoubles().write(0, location.getX());
        container.getDoubles().write(1, location.getY());
        container.getDoubles().write(2, location.getZ());

        if (container.getBytes().size() >= 2) {
            container.getBytes().write(0, angle(location.getPitch()));
            container.getBytes().write(1, angle(location.getYaw()));
            if (container.getBytes().size() >= 3) container.getBytes().write(2, angle(location.getYaw()));
        }

        return container;
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256F / 360F);
    }

    @NotNull
    private PacketContainer createMetadataPacket(int entityID, @NotNull Consumer<WrappedDataWatcher> consumer) {
        PacketContainer dataPacket = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
        WrappedDataWatcher metadata = new WrappedDataWatcher();

        consumer.accept(metadata);

        List<WrappedDataValue> wrappedDataValueList = new ArrayList<>();
        metadata.getWatchableObjects().stream().filter(Objects::nonNull).forEach(entry -> {
            WrappedDataWatcher.WrappedDataWatcherObject dataWatcherObject = entry.getWatcherObject();
            wrappedDataValueList.add(new WrappedDataValue(dataWatcherObject.getIndex(), dataWatcherObject.getSerializer(), entry.getRawValue()));
        });

        dataPacket.getDataValueCollectionModifier().write(0, wrappedDataValueList);
        dataPacket.getIntegers().write(0, entityID);

        return dataPacket;
    }
}
