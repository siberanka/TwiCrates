package su.nightexpress.excellentcrates.display;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;

public enum CrateModelProvider {

    ITEM_MODEL("item_model"),
    BETTERMODEL("bettermodel"),
    MODELENGINE("modelengine"),
    MYTHICMOBS("mythicmobs");

    private final String id;

    CrateModelProvider(@NotNull String id) {
        this.id = id;
    }

    @NotNull
    public String getId() {
        return this.id;
    }

    public boolean isExternal() {
        return this != ITEM_MODEL;
    }

    @NotNull
    public static CrateModelProvider getById(@Nullable String id) {
        if (id == null || id.isBlank()) return ITEM_MODEL;

        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(provider -> provider.id.equals(normalized) || provider.name().equalsIgnoreCase(normalized))
            .findFirst()
            .orElse(ITEM_MODEL);
    }
}
