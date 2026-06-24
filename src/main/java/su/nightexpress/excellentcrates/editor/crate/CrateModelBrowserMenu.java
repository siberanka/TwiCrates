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

    public record Data(@NotNull Crate crate, @NotNull String phase, @NotNull CrateModelProvider provider) {}

    public CrateModelBrowserMenu(@NotNull CratesPlugin plugin) {
        super(plugin, MenuType.GENERIC_9X5, Lang.EDITOR_TITLE_CRATE_SETTINGS.text());
        this.plugin.injectLang(this);

        this.addItem(MenuItem.background(Material.GRAY_STAINED_GLASS_PANE, IntStream.range(0, 36).toArray()));
        this.addItem(MenuItem.background(Material.BLACK_STAINED_GLASS_PANE, IntStream.range(36, 45).toArray()));
        this.addItem(MenuItem.buildPreviousPage(this, 39));
        this.addItem(MenuItem.buildReturn(this, 40, (viewer, event) -> {
            this.runNextTick(() -> this.plugin.getEditorManager().openOptionsMenu(viewer.getPlayer(), this.getLink(viewer).crate));
        }));
        this.addItem(MenuItem.buildNextPage(this, 41));
    }

    public boolean open(@NotNull Player player, @NotNull Crate crate) {
        return this.open(player, new Data(crate, "idle", crate.getJavaIdleModel().getProvider()));
    }

    @Override
    @NotNull
    public MenuFiller<String> createFiller(@NotNull MenuViewer viewer) {
        Data data = this.getLink(viewer);
        var filler = MenuFiller.builder(this);
        filler.setSlots(IntStream.range(0, 36).toArray());
        filler.setItems(this.getItems(data));
        filler.setItemCreator(id -> NightItem.fromType(Material.ITEM_FRAME)
            .localized(LOCALE_MODEL)
            .replacement(replacer -> replacer
                .replace(GENERIC_VALUE, id)
                .replace(GENERIC_TYPE, data.provider.getId())));
        filler.setItemClick(id -> (viewer1, event) -> {
            JavaCrateModel model = phaseModel(data.crate, data.phase);
            model.setProvider(data.provider);
            if (data.provider == CrateModelProvider.ITEM_MODEL) {
                model.setItemModel(id);
                model.setProviderModelId("");
            }
            else {
                model.setProviderModelId(id);
            }
            data.crate.markDirty();
            data.crate.saveIfDirty();
            this.plugin.getDisplayManager().ifPresent(manager -> manager.refresh(data.crate));
            this.runNextTick(() -> this.flush(viewer1));
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
                this.runNextTick(() -> this.open(viewer.getPlayer(), new Data(data.crate, next, data.provider)));
            }).build()
        );

        viewer.addItem(NightItem.fromType(Material.KNOWLEDGE_BOOK)
            .localized(LOCALE_PROVIDER)
            .replacement(replacer -> replacer
                .replace(GENERIC_TYPE, data.provider.getId())
                .replace(GENERIC_STATE, () -> CoreLang.STATE_YES_NO.get(ExternalProviderBridge.isProviderAvailable(data.provider))))
            .toMenuItem().setSlots(38).setHandler((viewer1, event) -> {
                CrateModelProvider next = Lists.next(data.provider);
                this.runNextTick(() -> this.open(viewer.getPlayer(), new Data(data.crate, data.phase, next)));
            }).build()
        );

        this.autoFill(viewer);
    }

    @Override
    protected void onReady(@NotNull MenuViewer viewer, @NotNull Inventory inventory) {

    }

    @NotNull
    private List<String> getItems(@NotNull Data data) {
        if (data.provider == CrateModelProvider.ITEM_MODEL) {
            return Arrays.asList(
                phaseModel(data.crate, data.phase).getItemModel().isBlank() ? "twicrates:example_model" : phaseModel(data.crate, data.phase).getItemModel()
            );
        }
        return ExternalProviderBridge.listModelIds(data.provider);
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
