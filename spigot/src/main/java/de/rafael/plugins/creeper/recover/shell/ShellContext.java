package de.rafael.plugins.creeper.recover.shell;

// Adapted from TownTagsPlugin (com.towntags.shell.ShellContext), itself from ThirdPlaceMcTweaks.
// Database pools, PlaceholderAPI and listener pruning are left out; the tracking and teardown
// rules are the same. Keep behaviour in step with the original when either side changes.

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The live {@link CreeperShellContext} for one core generation.
 *
 * <p>Created by the shell just before a core starts and {@linkplain #teardown() torn down} the
 * moment that core stops, after which it is permanently inert and a fresh instance serves the next
 * generation. The bookkeeping rules live in {@link Registrations}.
 */
public final class ShellContext implements CreeperShellContext {

    /**
     * Growth in tracked tasks since the last sweep that triggers the next one. A recovery timer
     * hops back to the main thread with a one-shot {@code runTask} for every block it restores, so
     * without pruning a long-lived generation would track one finished task per block ever
     * recovered. A growth allowance rather than a fixed size keeps the sweep rare even while many
     * hops are genuinely pending.
     */
    private static final int TASK_PRUNE_GROWTH = 64;

    private final Plugin plugin;
    private final Map<String, CommandRelay> relays;
    private final HandoffAttic attic;
    private final BooleanSupplier swapping;

    private final Registrations<Listener> listeners = new Registrations<>(HandlerList::unregisterAll);
    private final Registrations<BukkitTask> tasks = new Registrations<>(BukkitTask::cancel);
    private final Registrations<CommandRelay> boundCommands = new Registrations<>(CommandRelay::unbind);
    private final Registrations<Runnable> closeouts = new Registrations<>(Runnable::run);

    private volatile boolean live = true;

    /** Tracked-task count at which the next prune runs. Guarded by {@link #tasks}' monitor. */
    private int taskPruneCeiling = TASK_PRUNE_GROWTH;

    /**
     * @param plugin   the shell plugin every registration is made against
     * @param relays   the shell's permanent plugin.yml command relays, keyed by command name;
     *                 shared, not owned
     * @param attic    the shell's handoff scratch space
     * @param swapping reads the shell's swap flag, which changes during this context's life
     */
    public ShellContext(Plugin plugin, Map<String, CommandRelay> relays, HandoffAttic attic,
                        BooleanSupplier swapping) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.relays = Objects.requireNonNull(relays, "relays");
        this.attic = Objects.requireNonNull(attic, "attic");
        this.swapping = Objects.requireNonNull(swapping, "swapping");
    }

    @Override
    public Plugin plugin() {
        return plugin;
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public File dataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public boolean isRunning() {
        return live && plugin.isEnabled();
    }

    @Override
    public boolean isSwapping() {
        return swapping.getAsBoolean();
    }

    @Override
    public void registerListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        // Track first: a registered but untracked listener is the leak this class exists to
        // prevent, whereas a tracked but unregistered one is a harmless no-op at teardown.
        if (!listeners.track(listener)) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void registerCommand(String name, CommandExecutor executor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(executor, "executor");
        CommandRelay relay = relays.get(name);
        if (relay == null) {
            throw new IllegalArgumentException("no command relay for '" + name + "'; the shell registers "
                    + relays.keySet() + " from plugin.yml, and a new command name needs a plugin.yml "
                    + "change (a server restart)");
        }
        if (!boundCommands.track(relay)) {
            return;
        }
        relay.bind(executor);
    }

    @Override
    public void registerCloseout(Runnable closeout) {
        Objects.requireNonNull(closeout, "closeout");
        closeouts.track(closeout);
    }

    @Override
    public void stash(String key, Object value) {
        attic.put(key, value);
    }

    @Override
    public Object take(String key) {
        return attic.take(key);
    }

    @Override
    public BukkitTask runTask(Runnable task) {
        return schedule(scheduler -> scheduler.runTask(plugin, task));
    }

    @Override
    public BukkitTask runTaskAsynchronously(Runnable task) {
        return schedule(scheduler -> scheduler.runTaskAsynchronously(plugin, task));
    }

    @Override
    public BukkitTask runTaskLater(Runnable task, long delayTicks) {
        return schedule(scheduler -> scheduler.runTaskLater(plugin, task, delayTicks));
    }

    @Override
    public BukkitTask runTaskLaterAsynchronously(Runnable task, long delayTicks) {
        return schedule(scheduler -> scheduler.runTaskLaterAsynchronously(plugin, task, delayTicks));
    }

    @Override
    public BukkitTask runTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        return schedule(scheduler -> scheduler.runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public BukkitTask runTaskTimerAsynchronously(Runnable task, long delayTicks, long periodTicks) {
        return schedule(scheduler -> scheduler.runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
    }

    /**
     * Schedules through Bukkit and tracks the result, or returns a {@link RetiredTask} when this
     * generation is no longer running. The catch covers the shutdown race where Bukkit refuses a
     * task from a plugin that is being disabled.
     */
    private BukkitTask schedule(Scheduling scheduling) {
        if (!isRunning()) {
            return new RetiredTask(plugin);
        }
        BukkitTask scheduled;
        try {
            scheduled = scheduling.on(Bukkit.getScheduler());
        } catch (IllegalStateException | IllegalArgumentException schedulerRefused) {
            return new RetiredTask(plugin);
        }
        pruneTasksIfDue();
        if (!tasks.track(scheduled)) {
            // Teardown happened between the isRunning() check and here: cancel rather than let a
            // retired generation run one last time.
            scheduled.cancel();
            return new RetiredTask(plugin);
        }
        return scheduled;
    }

    private void pruneTasksIfDue() {
        synchronized (tasks) {
            if (tasks.size() < taskPruneCeiling) {
                return;
            }
            tasks.prune(ShellContext::isPending);
            taskPruneCeiling = tasks.size() + TASK_PRUNE_GROWTH;
        }
    }

    /** Whether a scheduled task could still run: queued or executing right now. */
    private static boolean isPending(BukkitTask task) {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        int id = task.getTaskId();
        return scheduler.isQueued(id) || scheduler.isCurrentlyRunning(id);
    }

    /**
     * Detaches everything this generation registered and makes the context inert. Called by the
     * shell right after {@link CreeperRecoverCore#stop()}, whether it returned or threw.
     *
     * <p>Order: {@code live} first so racing async callbacks are refused, then the command binding
     * (no player can dispatch into a core being pulled apart), close-outs, listeners, and finally
     * scheduled tasks. Idempotent.
     */
    public void teardown() {
        live = false;
        report("command relay", boundCommands.teardown());
        report("close-out hook", closeouts.teardown());
        report("listener", listeners.teardown());
        report("scheduled task", tasks.teardown());
    }

    private void report(String kind, List<Throwable> failures) {
        for (Throwable failure : failures) {
            plugin.getLogger().log(Level.WARNING,
                    "CreeperRecover could not release a " + kind + " while stopping its core", failure);
        }
    }

    @FunctionalInterface
    private interface Scheduling {
        BukkitTask on(BukkitScheduler scheduler);
    }
}
