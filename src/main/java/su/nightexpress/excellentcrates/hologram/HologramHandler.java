package su.nightexpress.excellentcrates.hologram;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntity;

import java.util.Set;

public interface HologramHandler {

    void sendHologramPackets(@NotNull Player player, @NotNull FakeEntity entity, boolean needSpawn, @NotNull String textLine);

    void sendItemDisplayPackets(@NotNull Player player, @NotNull FakeEntity entity, boolean needSpawn, @NotNull ItemStack itemStack, float scale);

    void sendDestroyEntityPacket(@NotNull Player player, @NotNull Set<Integer> idList);

    void sendDestroyEntityPacket(@NotNull Set<Integer> idList);
}
