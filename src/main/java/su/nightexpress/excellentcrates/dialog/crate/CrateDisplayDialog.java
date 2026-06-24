package su.nightexpress.excellentcrates.dialog.crate;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.dialog.Dialog;
import su.nightexpress.excellentcrates.display.JavaCrateModel;
import su.nightexpress.nightcore.bridge.dialog.wrap.WrappedDialog;
import su.nightexpress.nightcore.bridge.common.NightNbtHolder;
import su.nightexpress.nightcore.bridge.dialog.wrap.input.WrappedDialogInput;
import su.nightexpress.nightcore.locale.LangEntry;
import su.nightexpress.nightcore.locale.entry.DialogElementLocale;
import su.nightexpress.nightcore.locale.entry.TextLocale;
import su.nightexpress.nightcore.ui.dialog.Dialogs;
import su.nightexpress.nightcore.ui.dialog.build.DialogActions;
import su.nightexpress.nightcore.ui.dialog.build.DialogBases;
import su.nightexpress.nightcore.ui.dialog.build.DialogBodies;
import su.nightexpress.nightcore.ui.dialog.build.DialogButtons;
import su.nightexpress.nightcore.ui.dialog.build.DialogInputs;
import su.nightexpress.nightcore.ui.dialog.build.DialogTypes;

import static su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers.GRAY;
import static su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers.SOFT_YELLOW;

/** Editor for the complete Java/Bedrock display definition without hard-coded inventory prompts. */
public final class CrateDisplayDialog extends Dialog<Crate> {

    private static final TextLocale TITLE = LangEntry.builder("Dialog.Crate.Display.Title").text(title("Crate", "Platform Display"));
    private static final DialogElementLocale BODY = LangEntry.builder("Dialog.Crate.Display.Body").dialogElement(500,
        "Configure the per-player Java resource-pack models and Bedrock client-side blocks.",
        SOFT_YELLOW.wrap("Idle") + " is the normal state. Disabled/empty opening and closing states automatically use idle.",
        "Item Model accepts a namespaced key such as " + SOFT_YELLOW.wrap("twicrates:vote_opening") + ".",
        "Bedrock block fields accept a Bukkit material or full block-data string."
    );

    private static final TextLocale INPUT_DISPLAY_ENABLED = text("DisplayEnabled", "Display System Enabled");
    private static final TextLocale INPUT_JAVA_ENABLED = text("JavaEnabled", "Java Models Enabled");
    private static final TextLocale INPUT_IDLE_MATERIAL = text("Idle.Material", "Idle Material");
    private static final TextLocale INPUT_IDLE_CMD = text("Idle.CustomModelData", "Idle Custom Model Data");
    private static final TextLocale INPUT_IDLE_MODEL = text("Idle.ItemModel", "Idle Item Model");
    private static final TextLocale INPUT_IDLE_PROVIDER = text("Idle.Provider", "Idle Provider");
    private static final TextLocale INPUT_IDLE_PROVIDER_MODEL = text("Idle.ProviderModel", "Idle Provider Model ID");
    private static final TextLocale INPUT_OPENING_ENABLED = text("Opening.Enabled", "Opening Model Enabled");
    private static final TextLocale INPUT_OPENING_MATERIAL = text("Opening.Material", "Opening Material");
    private static final TextLocale INPUT_OPENING_CMD = text("Opening.CustomModelData", "Opening Custom Model Data");
    private static final TextLocale INPUT_OPENING_MODEL = text("Opening.ItemModel", "Opening Item Model");
    private static final TextLocale INPUT_OPENING_PROVIDER = text("Opening.Provider", "Opening Provider");
    private static final TextLocale INPUT_OPENING_PROVIDER_MODEL = text("Opening.ProviderModel", "Opening Provider Model ID");
    private static final TextLocale INPUT_CLOSING_ENABLED = text("Closing.Enabled", "Closing Model Enabled");
    private static final TextLocale INPUT_CLOSING_MATERIAL = text("Closing.Material", "Closing Material");
    private static final TextLocale INPUT_CLOSING_CMD = text("Closing.CustomModelData", "Closing Custom Model Data");
    private static final TextLocale INPUT_CLOSING_MODEL = text("Closing.ItemModel", "Closing Item Model");
    private static final TextLocale INPUT_CLOSING_PROVIDER = text("Closing.Provider", "Closing Provider");
    private static final TextLocale INPUT_CLOSING_PROVIDER_MODEL = text("Closing.ProviderModel", "Closing Provider Model ID");
    private static final TextLocale INPUT_SCALE = text("Scale", "Model Scale " + GRAY.wrap("(0.05..4.0)"));
    private static final TextLocale INPUT_Y_OFFSET = text("YOffset", "Y Offset " + GRAY.wrap("(-4.0..4.0)"));
    private static final TextLocale INPUT_YAW_OFFSET = text("YawOffset", "Yaw Offset " + GRAY.wrap("(degrees)"));
    private static final TextLocale INPUT_REQUIRE_PACK = text("RequirePack", "Require Loaded Resource Pack");
    private static final TextLocale INPUT_BEDROCK_ENABLED = text("Bedrock.Enabled", "Bedrock Blocks Enabled");
    private static final TextLocale INPUT_BEDROCK_IDLE = text("Bedrock.Idle", "Bedrock Idle Block");
    private static final TextLocale INPUT_BEDROCK_OPENING = text("Bedrock.Opening", "Bedrock Opening Block " + GRAY.wrap("(empty = idle)"));
    private static final TextLocale INPUT_BEDROCK_CLOSING = text("Bedrock.Closing", "Bedrock Closing Block " + GRAY.wrap("(empty = idle)"));
    private static final TextLocale INPUT_BEDROCK_FORMS = text("Bedrock.Forms", "Bedrock Forms Enabled");

    private final CratesPlugin plugin;

    public CrateDisplayDialog(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    @NotNull
    public WrappedDialog create(@NotNull Player player, @NotNull Crate crate) {
        JavaCrateModel idle = crate.getJavaIdleModel();
        JavaCrateModel opening = crate.getJavaOpeningModel();
        JavaCrateModel closing = crate.getJavaClosingModel();

        return Dialogs.create(builder -> {
            builder.base(DialogBases.builder(TITLE)
                .body(DialogBodies.plainMessage(BODY))
                .inputs(
                    DialogInputs.bool("display_enabled", INPUT_DISPLAY_ENABLED).initial(crate.isDisplayEnabled()).build(),
                    DialogInputs.bool("java_enabled", INPUT_JAVA_ENABLED).initial(crate.isJavaDisplayEnabled()).build(),
                    textInput("idle_material", INPUT_IDLE_MATERIAL, idle.getMaterial().name(), 64),
                    textInput("idle_cmd", INPUT_IDLE_CMD, String.valueOf(idle.getCustomModelData()), 10),
                    textInput("idle_model", INPUT_IDLE_MODEL, idle.getItemModel(), 128),
                    textInput("idle_provider", INPUT_IDLE_PROVIDER, idle.getProvider().getId(), 32),
                    textInput("idle_provider_model", INPUT_IDLE_PROVIDER_MODEL, idle.getProviderModelId(), 128),
                    DialogInputs.bool("opening_enabled", INPUT_OPENING_ENABLED).initial(opening.isEnabled()).build(),
                    textInput("opening_material", INPUT_OPENING_MATERIAL, opening.getMaterial().name(), 64),
                    textInput("opening_cmd", INPUT_OPENING_CMD, String.valueOf(opening.getCustomModelData()), 10),
                    textInput("opening_model", INPUT_OPENING_MODEL, opening.getItemModel(), 128),
                    textInput("opening_provider", INPUT_OPENING_PROVIDER, opening.getProvider().getId(), 32),
                    textInput("opening_provider_model", INPUT_OPENING_PROVIDER_MODEL, opening.getProviderModelId(), 128),
                    DialogInputs.bool("closing_enabled", INPUT_CLOSING_ENABLED).initial(closing.isEnabled()).build(),
                    textInput("closing_material", INPUT_CLOSING_MATERIAL, closing.getMaterial().name(), 64),
                    textInput("closing_cmd", INPUT_CLOSING_CMD, String.valueOf(closing.getCustomModelData()), 10),
                    textInput("closing_model", INPUT_CLOSING_MODEL, closing.getItemModel(), 128),
                    textInput("closing_provider", INPUT_CLOSING_PROVIDER, closing.getProvider().getId(), 32),
                    textInput("closing_provider_model", INPUT_CLOSING_PROVIDER_MODEL, closing.getProviderModelId(), 128),
                    textInput("scale", INPUT_SCALE, String.valueOf(crate.getJavaDisplayScale()), 12),
                    textInput("y_offset", INPUT_Y_OFFSET, String.valueOf(crate.getJavaDisplayYOffset()), 12),
                    textInput("yaw_offset", INPUT_YAW_OFFSET, String.valueOf(crate.getJavaDisplayYawOffset()), 12),
                    DialogInputs.bool("require_pack", INPUT_REQUIRE_PACK).initial(crate.isJavaDisplayRequirePack()).build(),
                    DialogInputs.bool("bedrock_enabled", INPUT_BEDROCK_ENABLED).initial(crate.isBedrockDisplayEnabled()).build(),
                    textInput("bedrock_idle", INPUT_BEDROCK_IDLE, crate.getBedrockIdleBlock(), 256),
                    textInput("bedrock_opening", INPUT_BEDROCK_OPENING, crate.getBedrockOpeningBlock(), 256),
                    textInput("bedrock_closing", INPUT_BEDROCK_CLOSING, crate.getBedrockClosingBlock(), 256),
                    DialogInputs.bool("bedrock_forms", INPUT_BEDROCK_FORMS).initial(crate.isBedrockFormsEnabled()).build()
                )
                .build());

            builder.type(DialogTypes.multiAction(DialogButtons.ok()).exitAction(DialogButtons.back()).build());
            builder.handleResponse(DialogActions.OK, (viewer, identifier, nbt) -> {
                if (nbt == null) return;

                crate.setDisplayEnabled(nbt.getBoolean("display_enabled", crate.isDisplayEnabled()));
                crate.setJavaDisplayEnabled(nbt.getBoolean("java_enabled", crate.isJavaDisplayEnabled()));
                applyModel(nbt, "idle", idle, true);
                applyModel(nbt, "opening", opening, nbt.getBoolean("opening_enabled", opening.isEnabled()));
                applyModel(nbt, "closing", closing, nbt.getBoolean("closing_enabled", closing.isEnabled()));
                crate.setJavaDisplayScale(number(nbt.getText("scale", ""), crate.getJavaDisplayScale()));
                crate.setJavaDisplayYOffset(number(nbt.getText("y_offset", ""), crate.getJavaDisplayYOffset()));
                crate.setJavaDisplayYawOffset(number(nbt.getText("yaw_offset", ""), crate.getJavaDisplayYawOffset()));
                crate.setJavaDisplayRequirePack(nbt.getBoolean("require_pack", crate.isJavaDisplayRequirePack()));
                crate.setBedrockDisplayEnabled(nbt.getBoolean("bedrock_enabled", crate.isBedrockDisplayEnabled()));
                crate.setBedrockIdleBlock(nbt.getText("bedrock_idle", crate.getBedrockIdleBlock()));
                crate.setBedrockOpeningBlock(nbt.getText("bedrock_opening", crate.getBedrockOpeningBlock()));
                crate.setBedrockClosingBlock(nbt.getText("bedrock_closing", crate.getBedrockClosingBlock()));
                crate.setBedrockFormsEnabled(nbt.getBoolean("bedrock_forms", crate.isBedrockFormsEnabled()));
                crate.markDirty();
                this.plugin.getDisplayManager().ifPresent(manager -> manager.refresh(crate));
                viewer.callback();
            });
        });
    }

    private static void applyModel(@NotNull NightNbtHolder nbt,
                                   @NotNull String prefix,
                                   @NotNull JavaCrateModel model,
                                   boolean enabled) {
        model.setEnabled(enabled);
        Material material = Material.matchMaterial(nbt.getText(prefix + "_material", model.getMaterial().name()));
        if (material != null) model.setMaterial(material);
        model.setCustomModelData(nbt.getInt(prefix + "_cmd", model.getCustomModelData()));
        model.setItemModel(nbt.getText(prefix + "_model", model.getItemModel()));
        model.setProvider(nbt.getText(prefix + "_provider", model.getProvider().getId()));
        model.setProviderModelId(nbt.getText(prefix + "_provider_model", model.getProviderModelId()));
    }

    private static double number(@NotNull String text, double fallback) {
        try {
            return Double.parseDouble(text);
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static TextLocale text(@NotNull String path, @NotNull String fallback) {
        return LangEntry.builder("Dialog.Crate.Display.Input." + path).text(fallback);
    }

    private static WrappedDialogInput textInput(@NotNull String key,
                                                @NotNull TextLocale label,
                                                @NotNull String initial,
                                                int maxLength) {
        return DialogInputs.text(key, label).initial(initial).maxLength(maxLength).build();
    }
}
