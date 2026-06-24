package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.reward.impl.ItemReward;
import su.nightexpress.excellentcrates.hooks.ExternalProviderBridge;
import su.nightexpress.nightcore.bridge.item.AdaptedItem;
import su.nightexpress.nightcore.locale.LangContainer;
import su.nightexpress.nightcore.locale.LangEntry;
import su.nightexpress.nightcore.locale.entry.IconLocale;
import su.nightexpress.nightcore.ui.menu.MenuViewer;
import su.nightexpress.nightcore.ui.menu.data.Filled;
import su.nightexpress.nightcore.ui.menu.data.MenuFiller;
import su.nightexpress.nightcore.ui.menu.item.MenuItem;
import su.nightexpress.nightcore.ui.menu.type.LinkedMenu;
import su.nightexpress.nightcore.util.bukkit.NightItem;

import java.util.stream.IntStream;

import static su.nightexpress.excellentcrates.Placeholders.GENERIC_TYPE;
import static su.nightexpress.excellentcrates.Placeholders.GENERIC_VALUE;

public class CraftEngineItemBrowserMenu extends LinkedMenu<CratesPlugin, CraftEngineItemBrowserMenu.Data> implements Filled<String>, LangContainer {

    private static final IconLocale LOCALE_ITEM = LangEntry.iconBuilder("Editor.Button.CraftEngine.Item")
        .rawName(GENERIC_VALUE)
        .appendCurrent("Target", GENERIC_TYPE).br()
        .appendClick("Click to select")
        .build();

    public record Data(@NotNull Crate crate, @Nullable ItemReward reward) {

        public boolean isBaseItem() {
            return this.reward == null;
        }
    }

    public CraftEngineItemBrowserMenu(@NotNull CratesPlugin plugin) {
        super(plugin, MenuType.GENERIC_9X5, Lang.EDITOR_TITLE_CRATE_SETTINGS.text());
        this.plugin.injectLang(this);

        this.addItem(MenuItem.background(Material.GRAY_STAINED_GLASS_PANE, IntStream.range(0, 36).toArray()));
        this.addItem(MenuItem.background(Material.BLACK_STAINED_GLASS_PANE, IntStream.range(36, 45).toArray()));
        this.addItem(MenuItem.buildPreviousPage(this, 39));
        this.addItem(MenuItem.buildReturn(this, 40, (viewer, event) -> {
            Data data = this.getLink(viewer);
            Player player = viewer.getPlayer();
            this.runNextTick(() -> {
                if (data.reward == null) this.plugin.getEditorManager().openOptionsMenu(player, data.crate);
                else this.plugin.getEditorManager().openRewardContent(player, data.reward);
            });
        }));
        this.addItem(MenuItem.buildNextPage(this, 41));
    }

    public boolean openBase(@NotNull Player player, @NotNull Crate crate) {
        return this.open(player, new Data(crate, null));
    }

    public boolean openReward(@NotNull Player player, @NotNull ItemReward reward) {
        return this.open(player, new Data(reward.getCrate(), reward));
    }

    @Override
    @NotNull
    public MenuFiller<String> createFiller(@NotNull MenuViewer viewer) {
        Data data = this.getLink(viewer);
        var filler = MenuFiller.builder(this);
        filler.setSlots(IntStream.range(0, 36).toArray());
        filler.setItems(ExternalProviderBridge.listCraftEngineItemIds());
        filler.setItemCreator(id -> NightItem.fromType(Material.KNOWLEDGE_BOOK)
            .localized(LOCALE_ITEM)
            .replacement(replacer -> replacer
                .replace(GENERIC_VALUE, id)
                .replace(GENERIC_TYPE, data.isBaseItem() ? "Crate Base Item" : "Reward Item")));
        filler.setItemClick(id -> (viewer1, event) -> {
            AdaptedItem item = ExternalProviderBridge.createCraftEngineItem(id, 1).orElse(null);
            if (item == null) return;

            if (data.reward == null) data.crate.setItem(item);
            else data.reward.addItem(item);
            data.crate.markDirty();
            data.crate.saveIfDirty();
            this.runNextTick(() -> this.flush(viewer1));
        });
        return filler.build();
    }

    @Override
    protected void onPrepare(@NotNull MenuViewer viewer, @NotNull InventoryView view) {
        this.autoFill(viewer);
    }

    @Override
    protected void onReady(@NotNull MenuViewer viewer, @NotNull Inventory inventory) {

    }
}
