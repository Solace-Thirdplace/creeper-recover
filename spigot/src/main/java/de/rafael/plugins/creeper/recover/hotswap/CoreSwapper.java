package de.rafael.plugins.creeper.recover.hotswap;

// Vendored from TownTagsPlugin (com.towntags.hotswap), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.hotswap) and Arcadia's :hotswap module. Renamed into this package rather
// than depended on: the repositories share no build. Keep behaviour in step with the original
// when either changes.

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the currently running core and the protocol for replacing it.
 *
 * <p><strong>Origin.</strong> Vendored from Arcadia's {@code :hotswap} module
 * ({@code com.solacethirdplace.arcadia.hotswap}), relocated into this package rather than
 * depended on, because the two repositories share no build. Keep the behaviour in step with
 * the original when either side changes; the only intentional divergences here are the
 * package name, the classloader name prefix ({@code creeperrecover-core:}) and the paragraph below
 * about handed-over state, which Arcadia does not have.
 *
 * <p>The swapper holds the one piece of mutable state in the pattern — which
 * {@link CoreHandle} is live — and enforces the ordering that makes a swap safe on a server
 * full of players. The shell supplies the two callbacks that only it can implement
 * (unregistering the old core's platform hooks, wiring up the new core's), and this class
 * decides <em>when</em> they run and what happens when they misbehave.
 *
 * <h2>Why the order is what it is</h2>
 *
 * <p>The new jar is loaded and instantiated <em>before</em> the old core is stopped. A staged
 * jar that is truncated, missing its service declaration, or built against a renamed contract
 * is the single most likely failure, and it is detectable without touching the running plugin.
 * Loading first turns that entire class of mistake into a no-op: the admin sees an error, the
 * server never notices, and the staged file stays put for a retry.
 *
 * <p>The old core is stopped before the new one starts, never concurrently, because both
 * generations would otherwise hold listener registrations for the same events and players
 * would see doubled effects. Whatever transient state the outgoing core wants to keep alive
 * across that gap is its own responsibility: the shell holds a handoff attic of plain JDK
 * values between the two calls (see {@code CreeperShellContext.stash}), so a swap costs only
 * the state the core chose not to hand over. CreeperRecover's core hands nothing over: it
 * restores every pending block in its stop, so the next core starts with nothing in flight.
 *
 * <p>The old loader is closed last, after the new core is confirmed running, so that a start
 * failure still has a working old core to fall back to.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Each step has a different blast radius, so each is treated differently:
 *
 * <ul>
 *   <li><strong>Load fails</strong> — nothing has changed; the old core is still registered
 *       and running. The exception propagates untouched.</li>
 *   <li><strong>Stop fails</strong> — the old core is going away regardless, and refusing to
 *       proceed would leave the plugin in the worst possible state (old core half-stopped, new
 *       core never started). The failure is logged and the swap continues. The shell's tracked
 *       registration facades already unregister everything on their own, so a throwing
 *       {@code stop()} mostly means the core's own cleanup was sloppy.</li>
 *   <li><strong>Start fails</strong> — see {@link #swap}, which attempts to bring the old core
 *       back.</li>
 *   <li><strong>Close fails</strong> — logged and ignored; a loader that will not close is a
 *       leak, not an outage.</li>
 * </ul>
 *
 * <p>Instances are thread-safe by coarse synchronization. Swaps are rare, admin-triggered, and
 * must never interleave, so a monitor is both sufficient and the least surprising choice.
 *
 * @param <T> the per-plugin core contract type
 */
public final class CoreSwapper<T> {

    private final Class<T> contract;
    private final CoreLoader<T> loader;
    private final Logger logger;

    /**
     * Retired loaders, weakly referenced so tracking them cannot itself be the leak. Cleared
     * of dead entries opportunistically in {@link #liveOldLoaderCount()}.
     */
    private final List<WeakReference<URLClassLoader>> retiredLoaders = new ArrayList<>();

    private CoreHandle<T> current;

    /**
     * Creates a swapper with the default {@link CoreLoader}.
     *
     * @param contract the per-plugin core contract interface; its classloader becomes the
     *                 parent of every core loader this swapper opens
     * @param logger   where non-fatal swap problems are reported — stop failures and loader
     *                 close failures. Injected rather than obtained statically so the shell's
     *                 own plugin logger is used and the library stays platform-free
     */
    public CoreSwapper(Class<T> contract, Logger logger) {
        this(contract, new CoreLoader<>(), logger);
    }

    /**
     * Creates a swapper with an explicit loader.
     *
     * <p>Exists so the swap protocol can be exercised against a loader stub, and so a shell
     * with unusual packaging needs can supply its own discovery without reimplementing the
     * protocol.
     *
     * @param contract the per-plugin core contract interface
     * @param loader   the loader used for every load and swap
     * @param logger   where non-fatal swap problems are reported
     */
    public CoreSwapper(Class<T> contract, CoreLoader<T> loader, Logger logger) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Returns the live handle, or {@code null} before a successful {@link #loadInitial}.
     *
     * <p>Nullable rather than {@link java.util.Optional} because the null case is a real and
     * expected operating mode: when the boot jar is missing, the shell registers nothing but a
     * swap command and sits coreless until an admin stages a good jar. Shells are expected to
     * null-check and report that state.
     *
     * @return the running core's handle, or {@code null} if no core is loaded
     */
    public synchronized CoreHandle<T> current() {
        return current;
    }

    /**
     * Performs the boot-time load: load the jar, then start it. There is no stop callback
     * because there is nothing to stop.
     *
     * <p>If {@code start} throws, the loader is closed and retired and the swapper stays
     * coreless — the shell is expected to disable itself (Paper) or log loudly and keep only
     * the swap command alive (Velocity, which must not take the proxy down).
     *
     * @param jar   the boot core jar
     * @param start invoked with the new instance and its handle; performs the shell-side
     *              registrations and calls the core's own start method
     * @return a report with a {@code null} old digest
     * @throws CoreLoadException     if the jar cannot be loaded, or if {@code start} throws
     * @throws IllegalStateException if a core is already loaded
     */
    public synchronized SwapReport loadInitial(Path jar, BiConsumer<T, CoreHandle<T>> start)
            throws CoreLoadException {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(start, "start");
        if (current != null) {
            throw new IllegalStateException("a core is already loaded (" + current.shortSha() + "); use swap()");
        }

        long startedAt = System.nanoTime();
        CoreHandle<T> fresh = loader.load(jar, contract);
        try {
            start.accept(fresh.instance(), fresh);
        } catch (RuntimeException | Error startFailure) {
            retire(fresh.loader());
            throw new CoreLoadException("core " + fresh.shortSha() + " from " + jar
                    + " loaded but failed to start; the plugin has no core", startFailure);
        }
        current = fresh;
        return new SwapReport(null, fresh.sha256(), millisSince(startedAt), liveOldLoaderCount());
    }

    /**
     * Replaces the running core with the one in {@code newJar}.
     *
     * <p>Steps, in order: load the new jar; stop the old core; start the new core; close the
     * old loader. See the class javadoc for why that order and not another.
     *
     * <h3>Recovery when {@code start} throws</h3>
     *
     * <p>A start failure is the one genuinely dangerous case: the old core has already been
     * stopped, so doing nothing would leave the plugin dead. This method therefore closes the
     * new core's loader and calls {@code start} <em>again with the old instance and its
     * original handle</em>, putting the previous core back in service. This works because
     * cores are stateless by design: a start is idempotent re-initialisation, the same thing a
     * boot does. The old handle remains {@link #current()} and the swap is reported as failed
     * via {@link CoreLoadException} — the staged jar stays on disk for another attempt.
     *
     * <p>If that restart <em>also</em> throws, there is nothing left to try. The recovery
     * failure is attached as a suppressed exception to the original start failure and a
     * {@link CoreLoadException} is thrown whose message says the plugin is now coreless, so
     * the admin knows a server restart is required rather than another swap.
     *
     * @param newJar the staged core jar to load
     * @param stop   invoked with the old instance; exceptions are logged and do not abort the
     *               swap
     * @param start  invoked with an instance and its handle; used for the new core and, on
     *               failure, to restore the old one
     * @return a report describing the completed swap; its duration covers load through start
     *         (the point at which the new core is serving), not the best-effort close that
     *         follows
     * @throws CoreLoadException     if the new jar cannot be loaded (old core untouched and
     *                               still running), or if the new core fails to start
     * @throws IllegalStateException if no core is loaded yet; use {@link #loadInitial}
     */
    public synchronized SwapReport swap(Path newJar, Consumer<T> stop, BiConsumer<T, CoreHandle<T>> start)
            throws CoreLoadException {
        Objects.requireNonNull(newJar, "newJar");
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(start, "start");
        CoreHandle<T> old = current;
        if (old == null) {
            throw new IllegalStateException("no core is loaded; use loadInitial()");
        }

        long startedAt = System.nanoTime();

        // 1. Load first: a bad staged jar must never disturb a healthy running core.
        CoreHandle<T> fresh = loader.load(newJar, contract);

        // 2. Stop the old core. Its failure is not worth aborting a swap that is already
        //    committed — the alternative is a plugin with neither core running.
        try {
            stop.accept(old.instance());
        } catch (RuntimeException | Error stopFailure) {
            logger.log(Level.WARNING, "core " + old.shortSha() + " threw while stopping; continuing the swap",
                    stopFailure);
        }

        // 3. Start the new core, restoring the old one if that fails.
        try {
            start.accept(fresh.instance(), fresh);
        } catch (RuntimeException | Error startFailure) {
            retire(fresh.loader());
            boolean restored;
            try {
                start.accept(old.instance(), old);
                restored = true;
            } catch (RuntimeException | Error recoveryFailure) {
                startFailure.addSuppressed(recoveryFailure);
                restored = false;
            }
            String outcome = restored
                    ? "the previous core " + old.shortSha() + " was restarted and is running again"
                    : "restarting the previous core " + old.shortSha() + " ALSO failed; the plugin now has no core"
                      + " and needs a server restart";
            throw new CoreLoadException("core " + fresh.shortSha() + " from " + newJar
                    + " failed to start; " + outcome, startFailure);
        }

        current = fresh;
        long durationMillis = millisSince(startedAt);

        // The telltale is read BEFORE this swap's loader joins the tracked set. A loader
        // closed microseconds ago cannot have been collected yet, so counting it would make
        // every single report claim a live prior loader and train admins to ignore the number.
        // What the report answers is "are loaders from EARLIER swaps still hanging around".
        int priorLive = liveOldLoaderCount();

        // 4. Close the old loader last, once the new core is confirmed up.
        retire(old.loader());

        return new SwapReport(old.sha256(), fresh.sha256(), durationMillis, priorLive);
    }

    /**
     * Counts retired core loaders that are still reachable, after hinting at a collection.
     *
     * <p>Exposed beyond {@link SwapReport} so a shell can surface the telltale on demand (a
     * status command, a periodic health log) without performing a swap. See
     * {@link SwapReport#liveOldLoaders()} for why the number is a hint and not a measurement.
     *
     * <p>Dead references are dropped from the tracking list as a side effect, so the list
     * cannot grow without bound across a long-lived server.
     *
     * <p>Note that {@link #swap} samples this <em>before</em> retiring the loader it is about
     * to close, so a report never counts the loader that swap just closed.
     *
     * @return the number of retired loaders still reachable
     */
    public synchronized int liveOldLoaderCount() {
        if (retiredLoaders.isEmpty()) {
            // Nothing has ever been retired, so there is nothing a collection could reveal —
            // and boot should not pay for a full GC just to print a zero.
            return 0;
        }
        // A hint, and documented as such. Cheap at swap frequency (a handful per session) and
        // the only way to distinguish "not collected yet" from "cannot be collected".
        System.gc();
        int live = 0;
        Iterator<WeakReference<URLClassLoader>> it = retiredLoaders.iterator();
        while (it.hasNext()) {
            if (it.next().get() != null) {
                live++;
            } else {
                it.remove();
            }
        }
        return live;
    }

    /**
     * Closes a loader and begins weakly tracking it.
     *
     * <p>Every loader this swapper stops using goes through here — the old one after a
     * successful swap, and the new one after a failed start — so the telltale sees the whole
     * population of things that ought to have become collectable.
     */
    private void retire(URLClassLoader retired) {
        try {
            retired.close();
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "could not close retired core loader " + retired.getName(), e);
        }
        retiredLoaders.add(new WeakReference<>(retired));
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
