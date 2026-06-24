package su.nightexpress.excellentcrates.hooks;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.display.CrateModelProvider;
import su.nightexpress.nightcore.bridge.item.AdaptedItem;
import su.nightexpress.nightcore.bridge.item.ItemAdapter;
import su.nightexpress.nightcore.integration.item.ItemBridge;
import su.nightexpress.nightcore.integration.item.adapter.IdentifiableItemAdapter;
import su.nightexpress.nightcore.integration.item.data.ItemIdData;
import su.nightexpress.nightcore.integration.item.impl.AdaptedCustomStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ExternalProviderBridge {

    private static final int MAX_IDS = 512;
    private static final String CRAFT_ENGINE_ADAPTER = "CraftEngine";

    private ExternalProviderBridge() {

    }

    @NotNull
    public static List<String> listModelIds(@NotNull CrateModelProvider provider) {
        if (!provider.isExternal()) return List.of();

        try {
            return switch (provider) {
                case BETTERMODEL -> listBetterModelIds();
                case MODELENGINE -> listModelEngineIds();
                case MYTHICMOBS -> listMythicMobIds();
                default -> List.of();
            };
        }
        catch (Throwable ignored) {
            return List.of();
        }
    }

    @NotNull
    public static List<String> listCraftEngineItemIds() {
        if (!isPluginEnabled("CraftEngine")) return List.of();

        List<String> ids = new ArrayList<>();
        try {
            Class<?> itemsClass = classOrNull("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            if (itemsClass != null) {
                Object loaded = invokeStatic(itemsClass, "loadedItems");
                if (loaded instanceof Map<?, ?> map) {
                    map.keySet().forEach(key -> addId(ids, String.valueOf(key)));
                }
            }

            if (ids.isEmpty()) {
                Class<?> engineClass = classOrNull("net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine");
                Object engine = engineClass == null ? null : invokeStatic(engineClass, "instance");
                Object itemManager = engine == null ? null : invoke(engine, "itemManager");
                Object items = itemManager == null ? null : invoke(itemManager, "items");
                if (items instanceof Collection<?> collection) {
                    collection.forEach(key -> addId(ids, String.valueOf(key)));
                }
            }
        }
        catch (Throwable ignored) {
            return List.of();
        }

        return normalized(ids);
    }

    @NotNull
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Optional<AdaptedItem> createCraftEngineItem(@NotNull String itemId, int amount) {
        if (!isSafeId(itemId, 128) || amount <= 0) return Optional.empty();

        ItemAdapter<?> adapter = ItemBridge.getAdapter(CRAFT_ENGINE_ADAPTER);
        if (adapter instanceof IdentifiableItemAdapter identifiable) {
            try {
                ItemStack probe = identifiable.createItem(itemId);
                if (probe == null || probe.getType().isAir()) return Optional.empty();

                return Optional.of(new AdaptedCustomStack((ItemAdapter) identifiable, new ItemIdData(itemId, Math.min(amount, 64))));
            }
            catch (Throwable ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public static boolean isProviderAvailable(@NotNull CrateModelProvider provider) {
        return switch (provider) {
            case ITEM_MODEL -> true;
            case BETTERMODEL -> isPluginEnabled("BetterModel") && classOrNull("kr.toxicity.model.api.BetterModel") != null;
            case MODELENGINE -> isPluginEnabled("ModelEngine") && classOrNull("com.ticxo.modelengine.api.ModelEngineAPI") != null;
            case MYTHICMOBS -> isPluginEnabled("MythicMobs") && classOrNull("io.lumine.mythic.bukkit.MythicBukkit") != null;
        };
    }

    public static boolean isSafeId(@Nullable String id, int maxLength) {
        return id != null && !id.isBlank() && id.length() <= maxLength && id.chars().noneMatch(ch -> ch < 32 || ch == 127);
    }

    @NotNull
    private static List<String> listBetterModelIds() throws ReflectiveOperationException {
        if (!isPluginEnabled("BetterModel")) return List.of();

        Class<?> clazz = Class.forName("kr.toxicity.model.api.BetterModel");
        Object keys = invokeStatic(clazz, "modelKeys");
        if (keys instanceof Collection<?> collection) {
            return normalized(collection.stream().map(String::valueOf).toList());
        }
        return List.of();
    }

    @NotNull
    private static List<String> listModelEngineIds() throws ReflectiveOperationException {
        if (!isPluginEnabled("ModelEngine")) return List.of();

        Class<?> clazz = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
        Object api = invokeStatic(clazz, "getAPI");
        Object registry = api == null ? null : invoke(api, "getModelRegistry");
        Object ids = registry == null ? null : invoke(registry, "getOrderedId");
        if (ids instanceof Collection<?> collection) {
            return normalized(collection.stream().map(String::valueOf).toList());
        }
        return List.of();
    }

    @NotNull
    private static List<String> listMythicMobIds() throws ReflectiveOperationException {
        if (!isPluginEnabled("MythicMobs")) return List.of();

        Class<?> clazz = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
        Object mythic = invokeStatic(clazz, "inst");
        Object manager = mythic == null ? null : invoke(mythic, "getMobManager");
        Object ids = manager == null ? null : invoke(manager, "getMobNames");
        if (ids instanceof Collection<?> collection) {
            return normalized(collection.stream().map(String::valueOf).toList());
        }
        return List.of();
    }

    private static boolean isPluginEnabled(@NotNull String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    @Nullable
    private static Class<?> classOrNull(@NotNull String className) {
        try {
            return Class.forName(className);
        }
        catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object invokeStatic(@NotNull Class<?> clazz, @NotNull String name) throws ReflectiveOperationException {
        Method method = clazz.getMethod(name);
        return method.invoke(null);
    }

    @Nullable
    private static Object invoke(@NotNull Object object, @NotNull String name) throws ReflectiveOperationException {
        Method method = object.getClass().getMethod(name);
        return method.invoke(object);
    }

    private static void addId(@NotNull List<String> ids, @NotNull String id) {
        if (isSafeId(id, 128)) ids.add(id);
    }

    @NotNull
    private static List<String> normalized(@NotNull Collection<String> input) {
        Set<String> unique = new LinkedHashSet<>();
        input.stream()
            .filter(id -> isSafeId(id, 128))
            .sorted(Comparator.naturalOrder())
            .limit(MAX_IDS)
            .forEach(unique::add);
        return new ArrayList<>(unique);
    }
}
