package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.display.CrateModelProvider;
import su.nightexpress.excellentcrates.display.JavaCrateModel;
import su.nightexpress.excellentcrates.hooks.ExternalProviderBridge;
import su.nightexpress.nightcore.core.config.CoreLang;
import su.nightexpress.nightcore.locale.LangContainer;
import su.nightexpress.nightcore.locale.LangEntry;
import su.nightexpress.nightcore.locale.entry.IconLocale;
import su.nightexpress.nightcore.ui.menu.MenuViewer;
import su.nightexpress.nightcore.ui.menu.data.Filled;
import su.nightexpress.nightcore.ui.menu.data.MenuFiller;
import su.nightexpress.nightcore.ui.menu.item.MenuItem;
import su.nightexpress.nightcore.ui.menu.type.LinkedMenu;
import su.nightexpress.nightcore.util.Lists;
import su.nightexpress.nightcore.util.bukkit.NightItem;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static su.nightexpress.excellentcrates.Placeholders.GENERIC_STATE;
import static su.nightexpress.excellentcrates.Placeholders.GENERIC_TYPE;
import static su.nightexpress.excellentcrates.Placeholders.GENERIC_VALUE;
import static su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers.SOFT_YELLOW;

public class CrateModelBrowserMenu extends LinkedMenu<CratesPlugin, CrateModelBrowserMenu.Data> implements Filled<String>, LangContainer {

    private static final IconLocale LOCALE_MODEL = LangEntry.iconBuilder("Editor.Button.Crate.ModelBrowser.Model")
        .rawName(GENERIC_VALUE)
        .appendCurrent("Provider", GENERIC_TYPE).br()
        .appendClick("Click to select")
        .build();

    private static final IconLocale LOCALE_STATE = LangEntry.iconBuilder("Editor.Button.Crate.ModelBrowser.State")
        .rawName(GENERIC_VALUE)
        .appendCurrent("Model", GENERIC_TYPE).br()
        .appendClick("Click to select")
        .build();

    private static final IconLocale LOCALE_STATE_BROWSER = LangEntry.iconBuilder("Editor.Button.Crate.ModelBrowser.StateBrowser")
        .name("Model State")
        .appendCurrent("Model", GENERIC_TYPE)
        .appendCurrent("Current", GENERIC_STATE).br()
        .appendInfo("Browse BetterModel/ModelEngine", "animation states for this model.").br()
        .appendClick("Click to open")
        .build();

    private static final IconLocale LOCALE_PROVIDER = LangEntry.iconBuilder("Editor.Button.Crate.ModelBrowser.Provider")
        .name("Model Provider")
        .appendCurrent("Current", GENERIC_TYPE)
        .appendCurrent("Available", GENERIC_STATE).br()
        .appendInfo("Cycles item_model, BetterModel,", "ModelEngine and MythicMobs.").br()
        .appendClick("Click to change")
        .build();

    private static final IconLocale LOCALE_PHASE = LangEntry.iconBuilder("Editor.Button.Crate.ModelBrowser.Phase")
        .name("Model Phase")
        .appendCurrent("Current", GENERIC_TYPE).br()
        .appendInfo("Selects idle, opening or closing", "crate display state.").br()
        .appendClick("Click to change")
        .build();

    private enum BrowserMode {
        MODELS,
        STATES
    }

    public record Data(@NotNull Crate crate,
                       @NotNull String phase,
                       @NotNull CrateModelProvider provider,
                       @NotNull BrowserMode mode) {}

    public CrateModelBrowserMenu(@NotNull CratesPlugin plugin) {
        super(plugin, MenuType.GENERIC_9X5, Lang.EDITOR_TITLE_CRATE_SETTINGS.text());
        this.plugin.injectLang(this);

        this.addItem(MenuItem.background(Material.GRAY_STAINED_GLASS_PANE, IntStream.range(0, 36).toArray()));
        this.addItem(MenuItem.background(Material.BLACK_STAINED_GLASS_PANE, IntStream.range(36, 45).toArray()));
        this.addItem(MenuItem.buildPreviousPage(this, 39));
        this.addItem(MenuItem.buildReturn(this, 40, (viewer, event) -> {
            Data data = this.getLink(viewer);
            if (data.mode == BrowserMode.STATES) {
                this.runNextTick(() -> this.open(viewer.getPlayer(), new Data(data.crate, data.phase, data.provider, BrowserMode.MODELS)));
            }
            else {
                this.runNextTick(() -> this.plugin.getEditorManager().openOptionsMenu(viewer.getPlayer(), data.crate));
            }
        }));
        this.addItem(MenuItem.buildNextPage(this, 41));
    }

    public boolean open(@NotNull Player player, @NotNull Crate crate) {
        return this.open(player, new Data(crate, "idle", crate.getJavaIdleModel().getProvider(), BrowserMode.MODELS));
    }

    @Override
    @NotNull
    public MenuFiller<String> createFiller(@NotNull MenuViewer viewer) {
        Data data = this.getLink(viewer);
        var filler = MenuFiller.builder(this);
        filler.setSlots(IntStream.range(0, 36).toArray());
        filler.setItems(this.getItems(data));
        filler.setItemCreator(id -> NightItem.fromType(data.mode == BrowserMode.STATES ? Material.NAME_TAG : Material.ITEM_FRAME)
            .localized(data.mode == BrowserMode.STATES ? LOCALE_STATE : LOCALE_MODEL)
            .replacement(replacer -> replacer
                .replace(GENERIC_VALUE, id)
                .replace(GENERIC_TYPE, data.mode == BrowserMode.STATES
                    ? displayModelId(phaseModel(data.crate, data.phase))
                    : data.provider.getId())));
        filler.setItemClick(id -> (viewer1, event) -> {
            JavaCrateModel model = phaseModel(data.crate, data.phase);
            if (data.mode == BrowserMode.STATES) {
                model.setProviderState(id);
            }
            else {
                model.setProvider(data.provider);
                if (data.provider == CrateModelProvider.ITEM_MODEL) {
                    model.setItemModel(id);
                    model.setProviderModelId("");
                    model.setProviderState("");
                }
                else {
                    model.setProviderModelId(id);
                    List<String> availableStates = ExternalProviderBridge.listModelStates(data.provider, id);
                    if (!availableStates.contains(model.getProviderState())) model.setProviderState("");
                }
            }
            saveAndRefresh(data.crate);

            List<String> states = statesFor(data.provider, model.getProviderModelId());
            if (data.mode == BrowserMode.MODELS && states.size() > 1) {
                this.runNextTick(() -> this.open(viewer1.getPlayer(), new Data(data.crate, data.phase, data.provider, BrowserMode.STATES)));
            }
            else {
                this.runNextTick(() -> this.flush(viewer1));
            }
        });
        return filler.build();
    }

    @Override
    protected void onPrepare(@NotNull MenuViewer viewer, @NotNull InventoryView view) {
        Data data = this.getLink(viewer);

        viewer.addItem(NightItem.fromType(Material.COMPASS)
            .localized(LOCALE_PHASE)
            .replacement(replacer -> replacer.replace(GENERIC_TYPE, data.phase))
            .toMenuItem().setSlots(36).setHandler((viewer1, event) -> {
                String next = switch (data.phase) {
                    case "idle" -> "opening";
                    case "opening" -> "closing";
                    default -> "idle";
                };
                CrateModelProvider provider = phaseModel(data.crate, next).getProvider();
                this.runNextTick(() -> this.open(viewer.getPlayer(), new Data(data.crate, next, provider, BrowserMode.MODELS)));
            }).build()
        );

        JavaCrateModel selected = phaseModel(data.crate, data.phase);
        viewer.addItem(NightItem.fromType(Material.ARMOR_STAND)
            .localized(LOCALE_STATE_BROWSER)
            .replacement(replacer -> replacer
                .replace(GENERIC_TYPE, displayModelId(selected))
                .replace(GENERIC_STATE, selected.getProviderState().isBlank() ? "default" : selected.getProviderState()))
            .toMenuItem().setSlots(37).setHandler((viewer1, event) -> {
                if (statesFor(selected.getProvider(), selected.getProviderModelId()).size() <= 1) return;
                this.runNextTick(() -> this.open(viewer1.getPlayer(), new Data(data.crate, data.phase, selected.getProvider(), BrowserMode.STATES)));
            }).build()
        );

        viewer.addItem(NightItem.fromType(Material.KNOWLEDGE_BOOK)
            .localized(LOCALE_PROVIDER)
            .replacement(replacer -> replacer
                .replace(GENERIC_TYPE, data.provider.getId())
                .replace(GENERIC_STATE, () -> CoreLang.STATE_YES_NO.get(ExternalProviderBridge.isProviderAvailable(data.provider))))
            .toMenuItem().setSlots(38).setHandler((viewer1, event) -> {
                CrateModelProvider next = Lists.next(data.provider);
                this.runNextTick(() -> this.open(viewer.getPlayer(), new Data(data.crate, data.phase, next, BrowserMode.MODELS)));
            }).build()
        );

        this.autoFill(viewer);
    }

    @Override
    protected void onReady(@NotNull MenuViewer viewer, @NotNull Inventory inventory) {

    }

    @NotNull
    private List<String> getItems(@NotNull Data data) {
        JavaCrateModel model = phaseModel(data.crate, data.phase);
        if (data.mode == BrowserMode.STATES) {
            return statesFor(data.provider, model.getProviderModelId());
        }
        if (data.provider == CrateModelProvider.ITEM_MODEL) {
            return Arrays.asList(
                model.getItemModel().isBlank() ? "twicrates:example_model" : model.getItemModel()
            );
        }
        return ExternalProviderBridge.listModelIds(data.provider);
    }

    @NotNull
    private static List<String> statesFor(@NotNull CrateModelProvider provider, @NotNull String modelId) {
        if (provider != CrateModelProvider.BETTERMODEL && provider != CrateModelProvider.MODELENGINE) return List.of();
        java.util.ArrayList<String> states = new java.util.ArrayList<>();
        states.add("default");
        states.addAll(ExternalProviderBridge.listModelStates(provider, modelId));
        return states.stream().distinct().limit(512).toList();
    }

    @NotNull
    private static String displayModelId(@NotNull JavaCrateModel model) {
        if (model.getProvider() == CrateModelProvider.ITEM_MODEL) {
            return model.getItemModel().isBlank() ? "-" : model.getItemModel();
        }
        return model.getProviderModelId().isBlank() ? "-" : model.getProviderModelId();
    }

    private void saveAndRefresh(@NotNull Crate crate) {
        crate.markDirty();
        crate.saveIfDirty();
        this.plugin.getDisplayManager().ifPresent(manager -> manager.refresh(crate));
    }

    @NotNull
    private static JavaCrateModel phaseModel(@NotNull Crate crate, @NotNull String phase) {
        return switch (phase) {
            case "opening" -> crate.getJavaOpeningModel();
            case "closing" -> crate.getJavaClosingModel();
            default -> crate.getJavaIdleModel();
        };
    }
}
