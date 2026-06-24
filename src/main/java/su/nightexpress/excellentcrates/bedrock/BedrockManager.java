package su.nightexpress.excellentcrates.bedrock;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.crate.impl.OpenOptions;
import su.nightexpress.excellentcrates.util.InteractType;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional Geyser/Floodgate bridge. All platform calls are reflected so TwiCrates remains loadable
 * when neither plugin is installed and across compatible API patch releases.
 */
public final class BedrockManager {

    private static final int REWARDS_PER_PAGE = 24;
    private static final long ACTION_COOLDOWN_NANOS = 400_000_000L;

    private final CratesPlugin plugin;
    private final Map<UUID, Long> actionCooldowns;

    private Object geyserApi;
    private Object floodgateApi;

    public BedrockManager(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
        this.actionCooldowns = new ConcurrentHashMap<>();
    }

    public void setup() {
        this.geyserApi = invokeStatic("org.geysermc.geyser.api.GeyserApi", "api");
        this.floodgateApi = invokeStatic("org.geysermc.floodgate.api.FloodgateApi", "getInstance");

        if (this.geyserApi == null && this.floodgateApi == null) {
            this.plugin.warn("Geyser/Floodgate was detected but its API is unavailable. Bedrock forms are disabled.");
        }
        else {
            this.plugin.info("Bedrock bridge enabled (native forms and per-player crate blocks)." );
        }
    }

    public void shutdown() {
        this.actionCooldowns.clear();
        this.geyserApi = null;
        this.floodgateApi = null;
    }

    public void forgetPlayer(@NotNull UUID uuid) {
        this.actionCooldowns.remove(uuid);
    }

    public boolean isBedrockPlayer(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (this.connection(uuid) != null) return true;

        Object result = invoke(this.floodgateApi, "isFloodgatePlayer", new Class<?>[]{UUID.class}, uuid);
        return result instanceof Boolean value && value;
    }

    public boolean openCrateForm(@NotNull Player player,
                                 @NotNull Crate crate,
                                 @NotNull InteractType interaction,
                                 @Nullable Location sourceLocation) {
        if (!this.isBedrockPlayer(player) || !crate.isBedrockFormsEnabled()) return false;

        Location safeSource = sourceLocation == null ? null : sourceLocation.clone();
        return interaction == InteractType.CRATE_PREVIEW
            ? this.showRewards(player, crate, safeSource, 0)
            : this.showOverview(player, crate, safeSource);
    }

    private boolean showOverview(@NotNull Player player, @NotNull Crate crate, @Nullable Location source) {
        StringBuilder content = new StringBuilder();
        for (String line : crate.getDescription()) {
            if (!content.isEmpty()) content.append('\n');
            content.append(plain(line, 240));
            if (content.length() >= 1200) break;
        }

        List<Cost> costs = crate.getCosts().stream().filter(Cost::isAvailable).toList();
        if (!costs.isEmpty()) {
            content.append("\n\nCosts:\n");
            for (Cost cost : costs.stream().limit(16).toList()) {
                content.append("- ").append(plain(cost.getName(), 80)).append(": ")
                    .append(plain(cost.formatInline(", "), 160)).append('\n');
            }
        }

        SimpleForm.Builder form = SimpleForm.builder()
            .title(plain(crate.getName(), 120))
            .content(content.toString())
            .button("Preview rewards")
            .button("Open crate")
            .button("Close")
            .validResultHandler(response -> {
                if (response.clickedButtonId() == 0) this.runSync(player, () -> this.showRewards(player, crate, source, 0));
                else if (response.clickedButtonId() == 1) this.runSync(player, () -> this.chooseCostOrOpen(player, crate, source));
            });

        return this.sendForm(player, form.build());
    }

    private boolean showRewards(@NotNull Player player, @NotNull Crate crate, @Nullable Location source, int requestedPage) {
        List<Reward> rewards = crate.getRewards(player);
        int pageCount = Math.max(1, (rewards.size() + REWARDS_PER_PAGE - 1) / REWARDS_PER_PAGE);
        int page = Math.clamp(requestedPage, 0, pageCount - 1);
        int from = page * REWARDS_PER_PAGE;
        int to = Math.min(rewards.size(), from + REWARDS_PER_PAGE);

        SimpleForm.Builder form = SimpleForm.builder()
            .title(plain(crate.getName(), 90) + " - Rewards")
            .content("Page " + (page + 1) + "/" + pageCount + " - " + rewards.size() + " available rewards");

        for (int index = from; index < to; index++) {
            Reward reward = rewards.get(index);
            form.button(plain(reward.getName(), 100) + "\n" + plain(reward.getRarity().getName(), 60));
        }

        int rewardButtonCount = to - from;
        int previousButton = -1;
        int nextButton = -1;
        if (page > 0) {
            previousButton = rewardButtonCount;
            form.button("Previous page");
        }
        if (page + 1 < pageCount) {
            nextButton = rewardButtonCount + (previousButton >= 0 ? 1 : 0);
            form.button("Next page");
        }
        int backButton = rewardButtonCount + (previousButton >= 0 ? 1 : 0) + (nextButton >= 0 ? 1 : 0);
        int finalPreviousButton = previousButton;
        int finalNextButton = nextButton;
        form.button("Back").validResultHandler(response -> {
            int selected = response.clickedButtonId();
            if (selected < rewardButtonCount) {
                Reward reward = rewards.get(from + selected);
                this.runSync(player, () -> this.showRewardDetails(player, crate, reward, source, page));
            }
            else if (selected == finalPreviousButton) this.runSync(player, () -> this.showRewards(player, crate, source, page - 1));
            else if (selected == finalNextButton) this.runSync(player, () -> this.showRewards(player, crate, source, page + 1));
            else if (selected == backButton) this.runSync(player, () -> this.showOverview(player, crate, source));
        });
        return this.sendForm(player, form.build());
    }

    private void showRewardDetails(@NotNull Player player,
                                   @NotNull Crate crate,
                                   @NotNull Reward reward,
                                   @Nullable Location source,
                                   int page) {
        StringBuilder content = new StringBuilder("Rarity: ")
            .append(plain(reward.getRarity().getName(), 80))
            .append("\nChance: ").append(String.format(java.util.Locale.ROOT, "%.4f%%", reward.getRollChance()));
        for (String line : reward.getDescription()) {
            content.append('\n').append(plain(line, 240));
            if (content.length() >= 1200) break;
        }

        SimpleForm form = SimpleForm.builder()
            .title(plain(reward.getName(), 120))
            .content(content.toString())
            .button("Back to rewards")
            .validResultHandler(response -> this.runSync(player, () -> this.showRewards(player, crate, source, page)))
            .build();
        this.sendForm(player, form);
    }

    private void chooseCostOrOpen(@NotNull Player player, @NotNull Crate crate, @Nullable Location source) {
        if (!this.isSourceValid(crate, source)) return;

        List<Cost> costs = crate.getCosts().stream().filter(Cost::isAvailable).toList();
        if (costs.size() <= 1) {
            this.openOnce(player, crate, source, costs.isEmpty() ? null : costs.getFirst());
            return;
        }

        SimpleForm.Builder form = SimpleForm.builder()
            .title(plain(crate.getName(), 90) + " - Select cost")
            .content("Choose exactly one payment option. The server revalidates the cost before opening.");
        for (Cost cost : costs.stream().limit(32).toList()) {
            String label = plain(cost.getName(), 80) + "\n" + plain(cost.formatInline(", "), 120);
            form.button(label);
        }
        int displayedCosts = Math.min(costs.size(), 32);
        form.button("Cancel").validResultHandler(response -> {
            int selected = response.clickedButtonId();
            if (selected >= 0 && selected < displayedCosts) {
                Cost cost = costs.get(selected);
                this.runSync(player, () -> this.openOnce(player, crate, source, cost));
            }
        });
        this.sendForm(player, form.build());
    }

    private void openOnce(@NotNull Player player, @NotNull Crate crate, @Nullable Location source, @Nullable Cost cost) {
        if (!player.isOnline() || !this.consumeAction(player) || !this.isSourceValid(crate, source)) return;
        this.plugin.getCrateManager().openCrate(player, new CrateSource(crate, null, source == null ? null : source.getBlock()), OpenOptions.empty(), cost);
    }

    private boolean isSourceValid(@NotNull Crate crate, @Nullable Location source) {
        return source == null || this.plugin.getCrateManager().getCrateByLocation(source) == crate;
    }

    private boolean consumeAction(@NotNull Player player) {
        long now = System.nanoTime();
        Long previous = this.actionCooldowns.put(player.getUniqueId(), now);
        return previous == null || now - previous >= ACTION_COOLDOWN_NANOS;
    }

    private void runSync(@NotNull Player player, @NotNull Runnable action) {
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline() && this.isBedrockPlayer(player)) action.run();
        });
    }

    private boolean sendForm(@NotNull Player player, @NotNull Form form) {
        Object connection = this.connection(player.getUniqueId());
        if (connection != null && invokeAssignable(connection, "sendForm", form)) return true;

        return this.floodgateApi != null
            && invoke(this.floodgateApi, "sendForm", new Class<?>[]{UUID.class, Form.class}, player.getUniqueId(), form) instanceof Boolean value
            && value;
    }

    @Nullable
    private Object connection(@NotNull UUID uuid) {
        return invoke(this.geyserApi, "connectionByUuid", new Class<?>[]{UUID.class}, uuid);
    }

    @Nullable
    private static Object invokeStatic(@NotNull String className, @NotNull String methodName) {
        try {
            Class<?> type = Class.forName(className, false, BedrockManager.class.getClassLoader());
            return type.getMethod(methodName).invoke(null);
        }
        catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }

    @Nullable
    private static Object invoke(@Nullable Object target, @NotNull String method, @NotNull Class<?>[] parameters, Object... args) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method, parameters).invoke(target, args);
        }
        catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }

    private static boolean invokeAssignable(@NotNull Object target, @NotNull String methodName, @NotNull Object argument) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isInstance(argument)) continue;
                method.invoke(target, argument);
                return true;
            }
        }
        catch (ReflectiveOperationException | LinkageError ignored) {
            // Fall through to Floodgate or the normal Java inventory path.
        }
        return false;
    }

    @NotNull
    private static String plain(@Nullable String input, int maximumLength) {
        if (input == null || input.isEmpty()) return "";
        String bounded = input.substring(0, Math.min(input.length(), Math.max(1, maximumLength * 2)));
        String stripped = bounded.replaceAll("(?i)§[0-9A-FK-ORX]", "")
            .replaceAll("<[^>]{1,96}>", "")
            .replace('\u0000', ' ');
        return stripped.substring(0, Math.min(stripped.length(), maximumLength));
    }
}
