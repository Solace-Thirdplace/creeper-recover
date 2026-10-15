package de.rafael.plugins.creeper.recover.shell;

// Vendored from TownTagsPlugin (com.towntags.shell), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.shell). Renamed into this package rather than depended on: the
// repositories share no build. Keep behaviour in step with the original when either side changes.

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

/**
 * The {@link BukkitTask} handed back when a retired core generation tries to schedule something.
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
 * <p>The task id is {@code -1}, the value Bukkit itself uses for "not a real task", so it can
 * never collide with a scheduled id or be mistaken for one in a log line.
 */
public final class RetiredTask implements BukkitTask {

    /** Bukkit's own sentinel for an id that was never issued. */
    private static final int NO_TASK_ID = -1;

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
    public int getTaskId() {
        return NO_TASK_ID;
    }

    @Override
    public Plugin getOwner() {
        return owner;
    }

    /**
     * Reports sync, because CreeperRecover's block placement is overwhelmingly main-thread and a caller
     * branching on this for a task that will never run should take the boring path.
     */
    @Override
    public boolean isSync() {
        return true;
    }

    @Override
    public boolean isCancelled() {
        return true;
    }

    @Override
    public void cancel() {
        // Already cancelled by construction — the whole point of this class.
    }
}
