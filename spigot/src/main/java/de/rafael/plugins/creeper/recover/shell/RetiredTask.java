package de.rafael.plugins.creeper.recover.shell;

// Vendored from TownTagsPlugin (com.towntags.shell), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.shell). Renamed into this package rather than depended on: the
// repositories share no build. Keep behaviour in step with the original when either side changes.

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * The {@link ScheduledTask} handed back when a retired core generation tries to schedule something.
 *
 * <p>Ported from Arcadia's {@code paper-market} shell.
 *
 * <h2>Why not just return null</h2>
 *
 * <p>The scheduler facade has to refuse work from a core that has already stopped — otherwise a
 * database future completing a second after a swap would run old-generation code against a
 * server that has moved on. But callers keep the handle they get back and cancel it later
 * ({@code ScalePotionService} stores one revert task per player and cancels it when the effect
 * ends), so returning {@code null} would convert a harmless late callback into a
 * {@code NullPointerException} on a scheduler thread — turning the safety measure into the
 * outage.
 *
 * <p>This is the null object instead: it reports itself as already cancelled, belongs to the
 * shell plugin so anything inspecting the owner sees something sane, and does nothing when
 * cancelled. Code written against the live case works unchanged against the dead one.
 *
 * <p>It used to stand in for a {@code BukkitTask}. The Folia port moved the shell to Paper's
 * region, entity and async schedulers, which hand out {@link ScheduledTask}s, so it now stands in
 * for one of those.
 */
public final class RetiredTask implements ScheduledTask {

    private final Plugin owner;

    /**
     * Creates the null object.
     *
     * @param owner the shell plugin, reported as the task's owner
     */
    public RetiredTask(Plugin owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public Plugin getOwningPlugin() {
        return owner;
    }

    @Override
    public boolean isRepeatingTask() {
        return false;
    }

    @Override
    public CancelledState cancel() {
        // Already cancelled by construction — the whole point of this class.
        return CancelledState.CANCELLED_ALREADY;
    }

    @Override
    public ExecutionState getExecutionState() {
        return ExecutionState.CANCELLED;
    }
}
