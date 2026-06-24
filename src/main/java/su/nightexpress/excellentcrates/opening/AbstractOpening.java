package su.nightexpress.excellentcrates.opening;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.Placeholders;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.api.opening.Opening;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.data.crate.GlobalCrateData;
import su.nightexpress.excellentcrates.data.crate.UserCrateData;
import su.nightexpress.excellentcrates.user.CrateUser;
import su.nightexpress.nightcore.util.Players;
import su.nightexpress.nightcore.util.placeholder.Replacer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractOpening implements Opening {

    protected final CratesPlugin plugin;
    protected final Player       player;
    protected final CrateSource  source;
    protected final Crate        crate;
    protected final Cost         cost;
    protected final List<Reward> rewards;

    protected long    tickCount;
    protected boolean running;
    protected boolean refundable;
    private boolean completionDelayBypassed;
    private boolean finalized;

    private static final int MAX_REWARDS_PER_OPENING = 128;

    public AbstractOpening(@NotNull CratesPlugin plugin, @NotNull Player player, @NotNull CrateSource source, @Nullable Cost cost) {
        this.plugin = plugin;
        this.player = player;
        this.source = source;
        this.crate = source.getCrate();
        this.cost = cost;
        this.rewards = new ArrayList<>();
        this.setRefundable(true);
    }

    @Override
    public void start() {
        if (this.running) return;

        this.running = true;
        this.plugin.getDisplayManager().ifPresent(manager -> manager.beginOpening(this.player, this.source));
        this.onStart();
    }

    @Override
    public void stop() {
        if (!this.running) return;
        if (this.isCompleted() && !this.completionDelayBypassed && this.tickCount < this.crate.getRewardDeliveryDelayTicks()) return;

        this.running = false;
        if (this.finalized) return;

        try {
            this.onStop();
        }
        catch (RuntimeException | LinkageError exception) {
            this.plugin.getOpeningManager().removeOpening(this.player);
            this.plugin.getDisplayManager().ifPresent(manager -> manager.cancelOpening(this.player, this.source));
            throw exception;
        }
        finally {
            this.finalized = true;
        }
    }

    @Override
    public void tick() {
        if (!this.running) return;

        if (this.isCompleted()) {
            this.stop();
            if (this.running) this.tickCount = Math.max(0L, this.tickCount + 1L);
            return;
        }

        if (this.isTickTime()) {
            this.onTick();
        }

        this.tickCount = Math.max(0L, this.tickCount + 1L);
    }

    @Override
    public final boolean isRunning() {
        return this.running;
    }

    @Override
    public long getTickCount() {
        return this.tickCount;
    }

    @Override
    public boolean isTickTime() {
        return this.tickCount == 0 || this.tickCount % this.getInterval() == 0L;
    }

    protected abstract void onStart();

    protected abstract void onTick();

    protected abstract void onComplete();

    protected void onStop() {
        boolean completed = this.isCompleted();
        if (!completed && this.isRefundable()) {
            if (this.cost != null) {
                this.cost.refundAll(this.player);
            }
            if (this.source.getItem() != null) {
                Players.addItem(this.player, this.crate.getItemStack());
            }
        }

        this.plugin.getOpeningManager().removeOpening(this.getPlayer());

        if (completed) {
            boolean showClosingDisplay = true;
            try {
                try {
                    this.onComplete();
                }
                catch (RuntimeException | LinkageError exception) {
                    this.plugin.error("Crate opening completion hook failed for '" + this.player.getName() + "'. Payment was refunded.");
                    exception.printStackTrace();
                    this.refundPayment();
                    showClosingDisplay = false;
                    return;
                }

                List<Reward> rolledRewards = new ArrayList<>(this.rewards.stream()
                    .limit(MAX_REWARDS_PER_OPENING)
                    .toList());
                this.rewards.clear();
                this.rewards.addAll(rolledRewards);

                if (rolledRewards.isEmpty()) {
                    this.refundPayment();
                    showClosingDisplay = false;
                    return;
                }

                CrateUser user = plugin.getUserManager().getOrFetch(player);
                UserCrateData userData = user.getCrateData(this.crate);
                GlobalCrateData globalData = plugin.getDataManager().getCrateDataOrCreate(this.crate);

                userData.addOpenings(1);
                globalData.setLatestOpener(this.player);
                globalData.setDirty(true);

                rolledRewards.forEach(reward -> {
                    try {
                        reward.give(this.player);
                    }
                    catch (RuntimeException | LinkageError exception) {
                        this.plugin.error("Could not give crate reward '" + reward.getId() + "' to '" + this.player.getName() + "'.");
                        exception.printStackTrace();
                    }
                });

                if (crate.isOpeningCooldownEnabled()) {
                    userData.addOpeningStreak(1);

                    if (!userData.isOnCooldown() && !crate.hasCooldownBypassPermission(player)) {
                        userData.setCooldown(crate.getOpeningCooldownTime());
                    }
                }

                if (crate.hasMilestones()) {
                    userData.addMilestones(1);
                    plugin.getCrateManager().triggerMilestones(player, crate, userData.getMilestone());
                    if (userData.getMilestone() >= crate.getMaxMilestone() && crate.isMilestonesRepeatable()) {
                        userData.setMilestone(0);
                    }
                }

                Lang.CRATE_OPEN_RESULT_INFO.message().send(this.player, replacer -> replacer
                    .replace(this.crate.replacePlaceholders())
                    .replace(Placeholders.GENERIC_REWARDS, rolledRewards.stream()
                        .map(reward -> reward.replacePlaceholders().apply(Lang.CRATE_OPEN_RESULT_REWARD.text()))
                        .collect(Collectors.joining(", "))
                    )
                );

                List<String> postOpenCommands = Replacer.create().replace(this.crate.replacePlaceholders()).apply(this.crate.getPostOpenCommands());
                try {
                    Players.dispatchCommands(this.player, postOpenCommands);
                }
                catch (RuntimeException | LinkageError exception) {
                    this.plugin.error("Post-open commands failed for crate '" + this.crate.getId() + "' and player '" + this.player.getName() + "'.");
                    exception.printStackTrace();
                }

                try {
                    this.plugin.getUserManager().save(user);
                }
                catch (RuntimeException | LinkageError exception) {
                    this.plugin.error("Could not save crate user data for '" + this.player.getName() + "' after opening.");
                    exception.printStackTrace();
                }
            }
            finally {
                boolean finalShowClosingDisplay = showClosingDisplay;
                this.plugin.getDisplayManager().ifPresent(manager -> {
                    if (finalShowClosingDisplay) manager.completeOpening(this.player, this.source);
                    else manager.cancelOpening(this.player, this.source);
                });
            }
        }
        else {
            this.plugin.getDisplayManager().ifPresent(manager -> manager.cancelOpening(this.player, this.source));
        }
    }

    private void refundPayment() {
        if (this.cost != null) {
            this.cost.refundAll(this.player);
        }
        if (this.source.getItem() != null) {
            Players.addItem(this.player, this.crate.getItemStack());
        }
    }

    /** Used by instant/mass openings and lifecycle cancellation so no delayed opening survives its owner. */
    public void bypassCompletionDelay() {
        this.completionDelayBypassed = true;
    }

    public void forceStop() {
        this.bypassCompletionDelay();
        this.stop();
    }

    @Override
    @NotNull
    public List<Reward> getRewards() {
        return this.rewards;
    }

    @Override
    public void addReward(@NotNull Reward reward) {
        if (this.finalized || this.rewards.size() >= MAX_REWARDS_PER_OPENING) return;
        this.rewards.add(reward);
    }

    @Override
    public void addRewards(@NotNull Collection<Reward> rewards) {
        if (this.finalized) return;
        rewards.stream()
            .limit(Math.max(0, MAX_REWARDS_PER_OPENING - this.rewards.size()))
            .forEach(this.rewards::add);
    }

    @Override
    public boolean isRefundable() {
        return this.refundable;
    }

    @Override
    public void setRefundable(boolean refundable) {
        this.refundable = refundable;
    }

    @Override
    @NotNull
    public Player getPlayer() {
        return this.player;
    }

    @Override
    @NotNull
    public CrateSource getSource() {
        return this.source;
    }

    @Override
    @NotNull
    public Crate getCrate() {
        return this.crate;
    }

    @Override
    @Nullable
    public Cost getCost() {
        return this.cost;
    }
}
