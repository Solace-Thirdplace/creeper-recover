package de.rafael.plugins.creeper.recover.shell;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Everything a CreeperRecover core may ask the shell for, and the only route it should use to
 * attach itself to the server.
 *
 * <p>Trimmed from TownTags' {@code TownTagsShellContext} (itself from ThirdPlaceMcTweaks): no
 * database pools and no PlaceholderAPI, which CreeperRecover has no use for.
 *
 * <h2>Why a facade at all</h2>
 *
 * <p>A core's classes die when its loader is closed, but Bukkit does not know that. A listener
 * registered straight with {@code PluginManager} stays in the handler lists and a repeating task
 * stays in the scheduler, and both keep the retired classloader alive. The plugin would then run
 * two generations of the explosion listener at once and leak a copy of its classes per swap.
 * Routing registrations through this object gives the shell an exact list of what one generation
 * attached, so it can detach all of it the moment that generation stops.
 *
 * <p>Calls that hold no long-lived reference back into core classes (blocks, worlds, entities,
 * sounds, sending messages) go straight to the Bukkit API and need no bookkeeping.
 *
 * <h2>Generations</h2>
 *
 * <p>One context belongs to one core generation. It is created just before
 * {@link CreeperRecoverCore#start} and torn down just after {@link CreeperRecoverCore#stop}. After
 * teardown every method here is a safe no-op and the scheduling methods hand back an
 * already-cancelled task, so a late async callback from a retired core cannot re-attach itself.
 */
public interface CreeperShellContext {

    /**
     * The shell plugin: the identity Bukkit knows CreeperRecover by, the same object for the life
     * of the server. bStats needs it as a {@code JavaPlugin}.
     *
     * @return the plugin that owns this context
     */
    Plugin plugin();

    /** @return the shell plugin's logger */
    Logger logger();

    /** @return {@code plugins/CreeperRecover/}, which also holds {@code core/} */
    File dataFolder();

    /** @return whether this context's core is still the running one and the plugin is enabled */
    boolean isRunning();

    /**
     * Whether the current {@link CreeperRecoverCore#stop()} (or the following start) is part of a
     * swap rather than a server shutdown or boot.
     *
     * @return {@code true} while a swap is in progress
     */
    boolean isSwapping();

    /**
     * Registers an event listener against the shell plugin and remembers it, so it is unregistered
     * when this generation stops.
     *
     * @param listener the listener to register
     */
    void registerListener(Listener listener);

    /**
     * Supplies the executor behind a command name the shell registered from plugin.yml. If the
     * executor is also a {@link org.bukkit.command.TabCompleter} it completes too. Emptied at
     * teardown.
     *
     * @param name     the plugin.yml command name, e.g. {@code "recover"}
     * @param executor this generation's handler
     * @throws IllegalArgumentException if the shell registered no command by that name
     */
    void registerCommand(String name, CommandExecutor executor);

    /**
     * Registers a hook to run at teardown, after {@link CreeperRecoverCore#stop()} and before the
     * listeners and tasks come down. For resources the shell cannot see, such as the bStats
     * submitter thread.
     *
     * @param closeout the disposal to run
     */
    void registerCloseout(Runnable closeout);

    /**
     * Hands a plain JDK value to the next core generation during a swap. See {@link HandoffAttic}
     * for what counts as plain.
     *
     * @param key   a namespaced key
     * @param value the value
     * @throws IllegalArgumentException if {@code value} contains a non-JDK type
     */
    void stash(String key, Object value);

    /**
     * Reclaims a value the previous generation stashed.
     *
     * @param key the key the previous generation used
     * @return the value, or {@code null} at boot or when nothing was stashed
     */
    Object take(String key);

    /*
     * Scheduling. Paper's region, entity and async schedulers, which exist on Paper and Purpur as
     * well as on Folia and Canvas, so one code path serves both. On Paper the region and entity
     * schedulers run on the main thread; on Folia they run on the thread that owns the location or
     * the entity. Bukkit's BukkitScheduler is not offered: Folia refuses it outright.
     *
     * Every tracked method hands back a task that is never null; after teardown it is an already
     * cancelled RetiredTask. Tracked tasks are cancelled when this generation stops.
     */

    /**
     * Runs {@code task} on the thread that owns {@code location}, next tick. Cancelled if this
     * generation stops first.
     *
     * @param location where the work happens; its world and chunk pick the owning region
     * @param task     the work
     * @return the task, never {@code null}
     */
    ScheduledTask runAt(Location location, Consumer<ScheduledTask> task);

    /**
     * Runs {@code task} on the thread that owns {@code location}, next tick, and does NOT cancel it
     * when this generation stops. For work that must not be lost to a swap: a block restore whose
     * block is already off its explosion's list. It keeps the retired generation's classes alive
     * until it has run, which is one tick.
     *
     * <p>Unlike the tracked methods this still schedules after teardown, as long as the shell
     * plugin is enabled.
     *
     * @param location where the work happens
     * @param task     the work
     * @return {@code false} if the server refused the task (the plugin is disabling)
     */
    boolean runAtUntracked(Location location, Runnable task);

    /**
     * Runs {@code task} on the thread that owns {@code entity}, next tick, following the entity
     * across regions. If the entity is removed first, {@code retired} runs instead (on whatever
     * thread notices). If the entity is already gone, {@code retired} runs now, on the calling
     * thread, and an already-cancelled task comes back.
     *
     * @param entity  the entity the work reads or writes
     * @param task    the work
     * @param retired run instead of {@code task} when the entity is gone; may be {@code null}
     * @return the task, never {@code null}
     */
    ScheduledTask runFor(Entity entity, Consumer<ScheduledTask> task, Runnable retired);

    /**
     * Runs {@code task} off every tick thread, now.
     *
     * @param task the work
     * @return the task, never {@code null}
     */
    ScheduledTask runAsync(Consumer<ScheduledTask> task);

    /**
     * Runs {@code task} off every tick thread repeatedly until cancelled or this generation stops.
     *
     * @param task         the work; it is handed its own task, so it can cancel itself
     * @param initialDelay before the first run, at least 0
     * @param period       between runs, more than 0
     * @param unit         the unit of both
     * @return the task, never {@code null}
     */
    ScheduledTask runAsyncAtFixedRate(Consumer<ScheduledTask> task, long initialDelay, long period, TimeUnit unit);
}
