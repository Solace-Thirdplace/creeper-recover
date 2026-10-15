package de.rafael.plugins.creeper.recover.common.scheduler;

import de.rafael.plugins.creeper.recover.common.CreeperPlugin;
import de.rafael.plugins.creeper.recover.shell.CreeperShellContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The Bukkit scheduler, reached through the shell context so every task this core generation
 * schedules is cancelled when it stops.
 *
 * <p>Moved here from the old spigot module. Behaviour is the same, with one addition: a hop onto
 * the main thread from another thread (a recovery timer putting a block back) is remembered until
 * it has run, so a stopping core can run the ones still queued before the shell cancels them.
 * Without that, a block a timer had already taken off its explosion's list but not yet placed would
 * be lost on a swap.
 *
 * @author Rafael K.
 * @since 06/07/2023
 */
public class BukkitScheduler implements CreeperPlugin.Scheduler {

    private final CreeperShellContext context;

    /** Main-thread hops queued from other threads that have not run yet, oldest first. */
    private final Set<MainThreadHop> pending = new LinkedHashSet<>();

    public BukkitScheduler(@NotNull CreeperShellContext context) {
        this.context = context;
    }

    @Override
    public void runOnCorrectThread(Location location, Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            MainThreadHop hop = new MainThreadHop(runnable);
            synchronized (this.pending) {
                this.pending.add(hop);
            }
            this.context.runTask(hop);
        }
    }

    @Override
    public void runAsync(Runnable runnable) {
        this.context.runTaskAsynchronously(runnable);
    }

    @Override
    public void runAsyncAtFixedRate(Consumer<Runnable> runnable, int delay, int period, @NotNull TimeUnit unit) {
        // The old code used Bukkit's Consumer<BukkitTask> overload to hand the task its own cancel.
        // The context only takes a Runnable, so the handle is published through a reference, and a
        // cancel that arrives before the handle exists is applied as soon as it does.
        AtomicReference<BukkitTask> handle = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        Runnable cancel = () -> {
            cancelled.set(true);
            BukkitTask task = handle.get();
            if (task != null) {
                task.cancel();
            }
        };
        BukkitTask task = this.context.runTaskTimerAsynchronously(() -> {
            if (!cancelled.get()) {
                runnable.accept(cancel);
            }
        }, unit.toMillis(delay) / 50, unit.toMillis(period) / 50);
        handle.set(task);
        if (cancelled.get()) {
            task.cancel();
        }
    }

    @Override
    public int runPendingNow() {
        List<MainThreadHop> due;
        synchronized (this.pending) {
            due = new ArrayList<>(this.pending);
        }
        int ran = 0;
        for (MainThreadHop hop : due) {
            if (hop.runOnce()) {
                ran++;
            }
        }
        return ran;
    }

    /** One queued main-thread runnable that runs at most once, from the scheduler or a drain. */
    private final class MainThreadHop implements Runnable {

        private final Runnable work;
        private final AtomicBoolean done = new AtomicBoolean();

        private MainThreadHop(Runnable work) {
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
            synchronized (BukkitScheduler.this.pending) {
                BukkitScheduler.this.pending.remove(this);
            }
            this.work.run();
            return true;
        }
    }

}
