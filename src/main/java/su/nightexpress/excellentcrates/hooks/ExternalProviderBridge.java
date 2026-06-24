package su.nightexpress.excellentcrates.hooks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
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

    public interface ModelHandle extends AutoCloseable {

        boolean isAlive();

        @Override
        void close();
    }

    public record ModelCreation(@NotNull Optional<ModelHandle> handle, @NotNull String failure) {

        @NotNull
        private static ModelCreation success(@NotNull ModelHandle handle) {
            return new ModelCreation(Optional.of(handle), "");
        }

        @NotNull
        private static ModelCreation failure(@NotNull String reason) {
            return new ModelCreation(Optional.empty(), reason);
        }
    }

    /**
     * Creates a viewer-scoped BetterModel tracker. Other providers keep the safe item-model fallback until they expose
     * an equivalent viewer-scoped API; no server entity is spawned by this bridge.
     */
    @NotNull
    public static ModelCreation createViewerModel(@NotNull CrateModelProvider provider,
                                                   @NotNull String modelId,
                                                   @Nullable String state,
                                                   @NotNull Player viewer,
                                                   @NotNull Location location,
                                                   float scale) {
        if (provider != CrateModelProvider.BETTERMODEL) return ModelCreation.failure("unsupported viewer-scoped provider");
        if (!isSafeId(modelId, 128)) return ModelCreation.failure("invalid model id");
        if (!isProviderAvailable(provider)) return ModelCreation.failure("provider is not installed, enabled, or API-compatible");

        try {
            Class<?> betterModel = Class.forName("kr.toxicity.model.api.BetterModel");
            Object renderer = invokeStatic(betterModel, "modelOrNull", new Class<?>[]{String.class}, modelId);
            if (renderer == null) return ModelCreation.failure("model id was not found by BetterModel");

            Class<?> adapter = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            Object platformLocation = invokeStatic(adapter, "adapt", new Class<?>[]{Location.class}, location);
            Object platformPlayer = invokeStatic(adapter, "adapt", new Class<?>[]{Player.class}, viewer);
            if (platformLocation == null || platformPlayer == null) return ModelCreation.failure("Bukkit platform adapter returned null");

            Object tracker = invokeCompatible(renderer, "create", platformLocation);
            if (tracker == null) return ModelCreation.failure("BetterModel did not create a dummy tracker");

            try {
                Class<?> scaler = Class.forName("kr.toxicity.model.api.tracker.ModelScaler");
                Object valueScaler = invokeStatic(scaler, "value", new Class<?>[]{float.class}, Math.max(0.05F, Math.min(4F, scale)));
                if (valueScaler != null) invokeCompatible(tracker, "scaler", valueScaler);
            }
            catch (ReflectiveOperationException ignored) {
                // Scale support differs between BetterModel releases; rendering is still safe at provider default.
            }

            invokeCompatible(tracker, "spawn", platformPlayer);
            if (isSafeId(state, 128)) invokeCompatible(tracker, "animate", state);

            return ModelCreation.success(new ModelHandle() {
                @Override
                public boolean isAlive() {
                    try {
                        Object closed = invokeCompatible(tracker, "isClosed");
                        return !(closed instanceof Boolean value) || !value;
                    }
                    catch (Throwable ignored) {
                        return false;
                    }
                }

                @Override
                public void close() {
                    try {
                        invokeCompatible(tracker, "close");
                    }
                    catch (Throwable ignored) {
                        // Provider may already have closed the tracker during its own reload/shutdown.
                    }
                }
            });
        }
        catch (Throwable throwable) {
            Throwable cause = throwable;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            String message = cause.getMessage();
            return ModelCreation.failure(cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message));
        }
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

    /** Returns bounded animation/state names for a concrete provider model. */
    @NotNull
    public static List<String> listModelStates(@NotNull CrateModelProvider provider, @Nullable String modelId) {
        if (!provider.isExternal() || !isSafeId(modelId, 128)) return List.of();

        try {
            return switch (provider) {
                case BETTERMODEL -> listBetterModelStates(modelId);
                case MODELENGINE -> listModelEngineStates(modelId);
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
    private static List<String> listBetterModelStates(@NotNull String modelId) throws ReflectiveOperationException {
        if (!isPluginEnabled("BetterModel")) return List.of();

        Class<?> clazz = Class.forName("kr.toxicity.model.api.BetterModel");
        Object optional = invokeStatic(clazz, "model", new Class<?>[]{String.class}, modelId);
        Object renderer = optional instanceof Optional<?> value ? value.orElse(null) : optional;
        Object animations = renderer == null ? null : invoke(renderer, "animations");
        return animations instanceof Map<?, ?> map ? normalized(map.keySet().stream().map(String::valueOf).toList()) : List.of();
    }

    @NotNull
    private static List<String> listModelEngineStates(@NotNull String modelId) throws ReflectiveOperationException {
        if (!isPluginEnabled("ModelEngine")) return List.of();

        Class<?> clazz = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
        Object blueprint = invokeStatic(clazz, "getBlueprint", new Class<?>[]{String.class}, modelId);
        Object animations = blueprint == null ? null : invoke(blueprint, "getAnimations");
        return animations instanceof Map<?, ?> map ? normalized(map.keySet().stream().map(String::valueOf).toList()) : List.of();
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
    private static Object invokeStatic(@NotNull Class<?> clazz,
                                       @NotNull String name,
                                       @NotNull Class<?>[] parameterTypes,
                                       Object... arguments) throws ReflectiveOperationException {
        Method method = clazz.getMethod(name, parameterTypes);
        return method.invoke(null, arguments);
    }

    @Nullable
    private static Object invoke(@NotNull Object object, @NotNull String name) throws ReflectiveOperationException {
        Method method = object.getClass().getMethod(name);
        return method.invoke(object);
    }

    @Nullable
    private static Object invokeCompatible(@NotNull Object object,
                                           @NotNull String name,
                                           Object... arguments) throws ReflectiveOperationException {
        for (Method method : object.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean compatible = true;
            for (int index = 0; index < types.length; index++) {
                Object argument = arguments[index];
                if (argument != null && !wrap(types[index]).isInstance(argument)) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return method.invoke(object, arguments);
        }
        throw new NoSuchMethodException(object.getClass().getName() + "#" + name);
    }

    @NotNull
    private static Class<?> wrap(@NotNull Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
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
