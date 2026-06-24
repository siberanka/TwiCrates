package su.nightexpress.excellentcrates.display;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** A bounded, validated Java resource-pack model definition for one crate display phase. */
public final class JavaCrateModel {

    private boolean enabled;
    private Material material;
    private int customModelData;
    private String itemModel;
    private CrateModelProvider provider;
    private String providerModelId;

    public JavaCrateModel(boolean enabled, @NotNull Material material, int customModelData, @Nullable String itemModel) {
        this.setEnabled(enabled);
        this.setMaterial(material);
        this.setCustomModelData(customModelData);
        this.setItemModel(itemModel);
        this.provider = CrateModelProvider.ITEM_MODEL;
        this.providerModelId = "";
    }

    @NotNull
    public ItemStack createItem() {
        ItemStack item = new ItemStack(this.material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setCustomModelData(this.customModelData > 0 ? this.customModelData : null);
        if (!this.itemModel.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(this.itemModel);
            if (key != null) meta.setItemModel(key);
        }
        item.setItemMeta(meta);
        return item;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NotNull
    public Material getMaterial() {
        return this.material;
    }

    public void setMaterial(@NotNull Material material) {
        this.material = material.isItem() && !material.isAir() ? material : Material.PAPER;
    }

    public int getCustomModelData() {
        return this.customModelData;
    }

    public void setCustomModelData(int customModelData) {
        this.customModelData = Math.max(0, customModelData);
    }

    @NotNull
    public String getItemModel() {
        return this.itemModel;
    }

    public void setItemModel(@Nullable String itemModel) {
        if (itemModel == null || itemModel.isBlank()) {
            this.itemModel = "";
            return;
        }

        String normalized = itemModel.trim().toLowerCase(Locale.ROOT);
        this.itemModel = normalized.length() <= 128 && NamespacedKey.fromString(normalized) != null ? normalized : "";
    }

    @NotNull
    public CrateModelProvider getProvider() {
        return this.provider;
    }

    public void setProvider(@Nullable String provider) {
        this.setProvider(CrateModelProvider.getById(provider));
    }

    public void setProvider(@NotNull CrateModelProvider provider) {
        this.provider = provider;
    }

    @NotNull
    public String getProviderModelId() {
        return this.providerModelId;
    }

    public void setProviderModelId(@Nullable String modelId) {
        if (modelId == null || modelId.isBlank()) {
            this.providerModelId = "";
            return;
        }

        String trimmed = modelId.trim();
        this.providerModelId = trimmed.length() <= 128 && trimmed.chars().noneMatch(ch -> ch < 32 || ch == 127) ? trimmed : "";
    }

    @NotNull
    public String getSourceName() {
        if (this.provider == CrateModelProvider.ITEM_MODEL) {
            return this.itemModel.isBlank() ? this.material.name() + ":" + this.customModelData : this.itemModel;
        }
        return this.provider.getId() + ":" + (this.providerModelId.isBlank() ? "-" : this.providerModelId);
    }
}
