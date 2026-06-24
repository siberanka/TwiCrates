package su.nightexpress.excellentcrates;

import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.api.addon.CratesAddon;
import su.nightexpress.excellentcrates.command.BaseCommands;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.config.Keys;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.config.Perms;
import su.nightexpress.excellentcrates.crate.CrateManager;
import su.nightexpress.excellentcrates.crate.cost.type.impl.EcoCostType;
import su.nightexpress.excellentcrates.data.DataHandler;
import su.nightexpress.excellentcrates.data.DataManager;
import su.nightexpress.excellentcrates.dialog.DialogRegistry;
import su.nightexpress.excellentcrates.editor.EditorManager;
import su.nightexpress.excellentcrates.display.CrateDisplayManager;
import su.nightexpress.excellentcrates.bedrock.BedrockManager;
import su.nightexpress.excellentcrates.hologram.HologramManager;
import su.nightexpress.excellentcrates.hooks.impl.PlaceholderHook;
import su.nightexpress.excellentcrates.key.KeyManager;
import su.nightexpress.excellentcrates.opening.OpeningManager;
import su.nightexpress.excellentcrates.opening.ProviderRegistry;
import su.nightexpress.excellentcrates.registry.CratesRegistries;
import su.nightexpress.excellentcrates.user.UserManager;
import su.nightexpress.nightcore.NightPlugin;
import su.nightexpress.nightcore.commands.command.NightCommand;
import su.nightexpress.nightcore.config.PluginDetails;
import su.nightexpress.nightcore.util.Plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

public class CratesPlugin extends NightPlugin {

    private final List<CratesAddon> addons = new ArrayList<>();
    private final Set<UUID> resourcePackPlayers = new HashSet<>();

    private DialogRegistry dialogRegistry;

    private DataHandler dataHandler;
    private DataManager dataManager;
    private UserManager userManager;

    private HologramManager hologramManager;
    private OpeningManager  openingManager;
    private KeyManager      keyManager;
    private CrateManager    crateManager;
    private EditorManager   editorManager;
    private CrateDisplayManager displayManager;
    private BedrockManager bedrockManager;

    private CrateLogger crateLogger;

    @Override
    @NotNull
    protected PluginDetails getDefaultDetails() {
        return PluginDetails.create("TwiCrates", new String[]{"twicrates", "twicrate", "tcrate", "crates", "ecrates", "excellentcrates", "crate", "case", "cases"})
            .setConfigClass(Config.class)
            .setPermissionsClass(Perms.class);
    }

    @Override
    protected boolean disableCommandManager() {
        return true;
    }

    @Override
    protected void onStartup() {
        this.migrateLegacyDataFolder();
        CratesAPI.load(this);
        Keys.load(this);
    }

    @Override
    protected void addRegistries() {
        this.registerLang(Lang.class);
    }

    @Override
    public void enable() {
        this.crateLogger = new CrateLogger(this);
        this.dialogRegistry = new DialogRegistry(this);

        ProviderRegistry.load();
        CratesRegistries.load(this);
        CratesRegistries.registerCostType(new EcoCostType(this, this.dialogRegistry));
        this.proceedAddons(CratesAddon::onInit);

        this.dataHandler = new DataHandler(this);
        this.dataHandler.setup();

        this.dataManager = new DataManager(this);
        this.dataManager.setup();

        this.userManager = new UserManager(this, this.dataHandler);
        this.userManager.setup();

        if (Config.HOLOGRAMS_ENABLED.get() || Config.CRATE_PACKET_BASED_MODE.get()) {
            this.hologramManager = new HologramManager(this);
            this.hologramManager.setup();
        }

        this.openingManager = new OpeningManager(this);
        this.openingManager.setup();

        this.keyManager = new KeyManager(this, this.dialogRegistry);
        this.keyManager.setup();

        this.crateManager = new CrateManager(this, this.dialogRegistry);
        this.crateManager.setup();

        if (this.getPluginManager().isPluginEnabled("Geyser-Spigot") || this.getPluginManager().isPluginEnabled("floodgate")) {
            this.bedrockManager = new BedrockManager(this);
            this.bedrockManager.setup();
        }

        this.displayManager = new CrateDisplayManager(this, this.crateManager, this.bedrockManager);
        this.displayManager.setup();

        this.editorManager = new EditorManager(this, this.dialogRegistry);
        this.editorManager.setup();

        this.dataHandler.updateRewardLimits();

        if (Plugins.hasPlaceholderAPI()) {
            PlaceholderHook.setup(this);
        }



        this.loadCommands();
        this.proceedAddons(CratesAddon::onLoad);
    }

    @Override
    public void disable() {
        if (this.editorManager != null) this.editorManager.shutdown();
        if (this.openingManager != null) this.openingManager.shutdown();
        if (this.displayManager != null) this.displayManager.shutdown();
        if (this.bedrockManager != null) this.bedrockManager.shutdown();
        if (this.keyManager != null) this.keyManager.shutdown();
        if (this.crateManager != null) this.crateManager.shutdown();
        //if (this.menuManager != null) this.menuManager.shutdown();
        if (this.hologramManager != null) this.hologramManager.shutdown();
        if (this.userManager != null) this.userManager.shutdown();
        if (this.dataManager != null) this.dataManager.shutdown();
        if (this.dataHandler != null) this.dataHandler.shutdown();
        if (this.dialogRegistry != null) this.dialogRegistry.clear();

        if (Plugins.hasPlaceholderAPI()) {
            PlaceholderHook.shutdown();
        }

        CratesRegistries.clear();
        ProviderRegistry.clear();
    }

    @Override
    protected void onShutdown() {
        super.onShutdown();
        this.resourcePackPlayers.clear();
        Keys.clear();
        CratesAPI.clear();
    }

    private void loadCommands() {
        this.rootCommand = NightCommand.forPlugin(this, builder -> new BaseCommands(this).load(builder));
    }

    public void registerAddon(@NotNull CratesAddon addon) {
        this.addons.add(addon);
    }

    private void proceedAddons(@NotNull Consumer<CratesAddon> consumer) {
        this.addons.forEach(consumer);
    }

    @NotNull
    public List<CratesAddon> getAddons() {
        return this.addons;
    }

    public boolean hasHolograms() {
        return Config.HOLOGRAMS_ENABLED.get() && this.hologramManager != null && this.hologramManager.hasHandler();
    }

    @NotNull
    public Optional<HologramManager> getHologramManager() {
        return Optional.ofNullable(this.hologramManager);
    }

    @NotNull
    public CrateLogger getCrateLogger() {
        return this.crateLogger;
    }

    @NotNull
    public DataHandler getDataHandler() {
        return this.dataHandler;
    }

    @NotNull
    public DataManager getDataManager() {
        return this.dataManager;
    }

    @NotNull
    public UserManager getUserManager() {
        return this.userManager;
    }

    @NotNull
    public OpeningManager getOpeningManager() {
        return this.openingManager;
    }

    @NotNull
    public EditorManager getEditorManager() {
        return this.editorManager;
    }

    @NotNull
    public KeyManager getKeyManager() {
        return this.keyManager;
    }

    @NotNull
    public CrateManager getCrateManager() {
        return this.crateManager;
    }

    private void migrateLegacyDataFolder() {
        Path targetRoot = this.getDataFolder().toPath().toAbsolutePath().normalize();
        Path parent = targetRoot.getParent();
        if (parent == null) return;

        Path sourceRoot = parent.resolve("ExcellentCrates").toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot) || sourceRoot.equals(targetRoot)) return;

        try {
            if (Files.isDirectory(targetRoot)) {
                try (Stream<Path> entries = Files.list(targetRoot)) {
                    if (entries.findAny().isPresent()) return;
                }
            }

            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                for (Path source : paths.toList()) {
                    if (Files.isSymbolicLink(source)) continue;
                    Path relative = sourceRoot.relativize(source);
                    Path target = targetRoot.resolve(relative).normalize();
                    if (!target.startsWith(targetRoot)) continue;

                    if (Files.isDirectory(source)) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            this.info("Copied legacy ExcellentCrates data into the TwiCrates data folder.");
        }
        catch (IOException exception) {
            this.error("Could not migrate the legacy ExcellentCrates data folder: " + exception.getMessage());
        }
    }

    public Optional<CrateDisplayManager> getDisplayManager() {
        return Optional.ofNullable(this.displayManager);
    }

    public Optional<BedrockManager> getBedrockManager() {
        return Optional.ofNullable(this.bedrockManager);
    }

    public boolean hasLoadedResourcePack(@NotNull UUID playerId) {
        return this.resourcePackPlayers.contains(playerId);
    }

    public void setResourcePackLoaded(@NotNull UUID playerId, boolean loaded) {
        if (loaded) this.resourcePackPlayers.add(playerId);
        else this.resourcePackPlayers.remove(playerId);
    }
}
