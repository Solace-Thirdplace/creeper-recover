package de.rafael.plugins.creeper.recover.common.scheduler;

import de.rafael.plugins.creeper.recover.common.CreeperPlugin;
import de.rafael.plugins.creeper.recover.shell.CreeperShellContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Paper's region, entity and async schedulers, reached through the shell context so every task
 * this core generation schedules is cancelled when it stops.
 *
 * <p>Was {@code BukkitScheduler}, built on Bukkit's main-thread scheduler. Folia has no main thread
 * and refuses that scheduler, so this now asks "does the current thread own this block (or
 * entity)?" with {@link Bukkit#isOwnedByCurrentRegion}, runs the work inline when it does, and
 * otherwise hands it to the owning region's (or entity's) scheduler. On Paper and Purpur the
 * owning thread is always the main thread, so behaviour there is what it was: inline on the main
 * thread, next tick from anywhere else.
 *
 * <p>A block restore hop from another thread (a recovery timer putting a block back) is remembered
 * until it has run, so a stopping core can run the ones its thread owns before the shell tears it
 * down. Those hops are scheduled untracked, so the ones a stopping core cannot run itself (a swap
 * on Folia, run from a thread that owns at most one region) still run in their own regions one
 * tick later instead of being cancelled. Without that, a block a timer had already taken off its
 * explosion's list but not yet placed would be lost on a swap.
 *
 * @author Rafael K.
 * @since 06/07/2023
 */
public class CoreScheduler implements CreeperPlugin.Scheduler {

    /** Bukkit async timers ran on tick boundaries; recovery timing keeps that granularity. */
    private static final long TICK_MILLIS = 50;

    private final CreeperShellContext context;

    /** Restore hops queued from other threads that have not run yet, oldest first. */
    private final Set<RestoreHop> pending = new LinkedHashSet<>();

    public CoreScheduler(@NotNull CreeperShellContext context) {
        this.context = context;
    }

    @Override
    public void runOnCorrectThread(Location location, Runnable runnable) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            runnable.run();
            return;
        }
        RestoreHop hop = new RestoreHop(location, runnable);
        synchronized (this.pending) {
            this.pending.add(hop);
        }
        // Untracked: this hop carries a block that is already off its explosion's list, so a swap
        // must not cancel it. If the server refuses it (the plugin is disabling), it stays pending
        // and the stopping core runs it.
        this.context.runAtUntracked(location, hop);
    }

    @Override
    public void runAt(Location location, Runnable runnable) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            runnable.run();
        } else {
            this.context.runAt(location, task -> runnable.run());
        }
    }

    @Override
    public void runFor(Entity entity, Runnable runnable, @Nullable Runnable retired) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            runnable.run();
        } else {
            this.context.runFor(entity, task -> runnable.run(), retired);
        }
    }

    @Override
    public void runAsync(Runnable runnable) {
        this.context.runAsync(task -> runnable.run());
    }

    @Override
    public void runAsyncAtFixedRate(Consumer<Runnable> runnable, int delay, int period, @NotNull TimeUnit unit) {
        // Quantised to whole ticks as the Bukkit async timer this replaces was. Paper's async
        // scheduler takes no period of 0, where Bukkit ran such a timer every tick.
        long delayTicks = Math.max(0, unit.toMillis(delay) / TICK_MILLIS);
        long periodTicks = Math.max(1, unit.toMillis(period) / TICK_MILLIS);
        this.context.runAsyncAtFixedRate(task -> runnable.accept(task::cancel),
                delayTicks * TICK_MILLIS, periodTicks * TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public int runPendingNow() {
        List<RestoreHop> due;
        synchronized (this.pending) {
            due = new ArrayList<>(this.pending);
        }
        int ran = 0;
        for (RestoreHop hop : due) {
            if (Bukkit.isOwnedByCurrentRegion(hop.location) && hop.runOnce()) {
                ran++;
            }
        }
        return ran;
    }

    @Override
    public int pendingElsewhere() {
        synchronized (this.pending) {
            return this.pending.size();
        }
    }

    /** One queued restore that runs at most once, from its region's scheduler or a drain. */
    private final class RestoreHop implements Runnable {

        private final Location location;
        private final Runnable work;
        private final AtomicBoolean done = new AtomicBoolean();

        private RestoreHop(Location location, Runnable work) {
            this.location = location;
            this.work = work;
        }

        @Override
        public void run() {
            runOnce();
        }

        private boolean runOnce() {
            if (!this.done.compareAndSet(false, true)) {
                return false;
            }
            synchronized (CoreScheduler.this.pending) {
                CoreScheduler.this.pending.remove(this);
            }
            this.work.run();
            return true;
        }
    }

}
