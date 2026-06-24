package su.nightexpress.excellentcrates.command;

import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.display.CrateModelProvider;
import su.nightexpress.excellentcrates.key.CrateKey;
import su.nightexpress.nightcore.commands.Commands;
import su.nightexpress.nightcore.commands.builder.ArgumentNodeBuilder;
import su.nightexpress.nightcore.commands.exceptions.CommandSyntaxException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class CommandArguments {

    public static final String PLAYER = "player";
    public static final String CRATE  = "crate";
    public static final String KEY    = "key";
    public static final String PHASE  = "phase";
    public static final String PROVIDER = "provider";
    public static final String MODEL  = "model";
    public static final String REWARD = "reward";
    public static final String ITEM   = "item";
    public static final String AMOUNT = "amount";
    public static final String X      = "x";
    public static final String Y      = "y";
    public static final String Z      = "z";
    public static final String WORLD  = "world";

    @NotNull
    public static ArgumentNodeBuilder<Crate> forCrate(@NotNull CratesPlugin plugin) {
        return Commands.argument(CRATE, (context, string) -> Optional.ofNullable(plugin.getCrateManager().getCrateById(string)).orElseThrow(() -> CommandSyntaxException.custom(Lang.ERROR_COMMAND_INVALID_CRATE_ARGUMENT)))
            .localized(Lang.COMMAND_ARGUMENT_NAME_CRATE)
            .suggestions((reader, context) -> plugin.getCrateManager().getCrateIds());
    }

    @NotNull
    public static ArgumentNodeBuilder<CrateKey> forKey(@NotNull CratesPlugin plugin) {
        return Commands.argument(KEY, (context, string) -> Optional.ofNullable(plugin.getKeyManager().getKeyById(string)).orElseThrow(() -> CommandSyntaxException.custom(Lang.ERROR_COMMAND_INVALID_KEY_ARGUMENT)))
            .localized(Lang.COMMAND_ARGUMENT_NAME_KEY)
            .suggestions((reader, context) -> plugin.getKeyManager().getKeyIds());
    }

    @NotNull
    public static ArgumentNodeBuilder<String> forModelPhase() {
        return Commands.argument(PHASE, (context, string) -> {
                String phase = string.toLowerCase();
                if (!List.of("idle", "opening", "closing").contains(phase)) {
                    throw CommandSyntaxException.custom(Lang.ERROR_COMMAND_INVALID_MODEL_PHASE_ARGUMENT);
                }
                return phase;
            })
            .localized(Lang.COMMAND_ARGUMENT_NAME_PHASE)
            .suggestions((reader, context) -> List.of("idle", "opening", "closing"));
    }

    @NotNull
    public static ArgumentNodeBuilder<CrateModelProvider> forModelProvider() {
        return Commands.argument(PROVIDER, (context, string) -> {
                CrateModelProvider provider = CrateModelProvider.getById(string);
                if (!provider.getId().equalsIgnoreCase(string) && !provider.name().equalsIgnoreCase(string)) {
                    throw CommandSyntaxException.custom(Lang.ERROR_COMMAND_INVALID_MODEL_PROVIDER_ARGUMENT);
                }
                return provider;
            })
            .localized(Lang.COMMAND_ARGUMENT_NAME_PROVIDER)
            .suggestions((reader, context) -> Arrays.stream(CrateModelProvider.values()).map(CrateModelProvider::getId).toList());
    }

    @NotNull
    public static ArgumentNodeBuilder<String> string(@NotNull String name, @NotNull su.nightexpress.nightcore.locale.entry.TextLocale localized, @NotNull java.util.function.Supplier<List<String>> suggestions) {
        return Commands.argument(name, (context, string) -> string)
            .localized(localized)
            .suggestions((reader, context) -> suggestions.get());
    }
}
