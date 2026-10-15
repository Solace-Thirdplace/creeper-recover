package de.rafael.plugins.creeper.recover.shell;

// Vendored from TownTagsPlugin (com.towntags.shell), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.shell). Renamed into this package rather than depended on: the
// repositories share no build. Keep behaviour in step with the original when either side changes.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A list of things one core generation attached to the server, and the one way to detach them
 * all again.
 *
 * <p>Ported from Arcadia's {@code paper-market} shell (see {@code docs/hotswap-contract.md}).
 * The rules below are the ported part and must not drift; the instantiations live in
 * {@link ShellContext}.
 *
 * <h2>Why this is generic and platform-free</h2>
 *
 * <p>The bookkeeping is the part of the hotswap shell that has to be <em>right</em>: a
 * registration that survives its core is a leaked classloader plus a handler running twice, and
 * both symptoms surface hours later on a live server, where they are nearly impossible to
 * attribute. That makes it exactly the code worth testing directly — and it cannot be tested
 * directly if it is welded to {@code Listener}, {@code BukkitTask} and a running server. So the
 * rules live here, over an opaque {@code T} with a disposer function, and {@link ShellContext}
 * supplies the Bukkit-shaped instantiations.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><strong>Teardown is LIFO.</strong> Registrations are undone newest-first, the same order
 *       a stack of resources unwinds. Nothing in CreeperRecover currently depends on it, but the
 *       alternative is an order that is arbitrary <em>and</em> undocumented.</li>
 *   <li><strong>Teardown never stops early.</strong> A disposer that throws must not strand the
 *       registrations behind it — that would leave precisely the half-detached state this class
 *       exists to prevent. Failures are collected and returned so the caller can log them.</li>
 *   <li><strong>Teardown is final and idempotent.</strong> Once torn down, the tracker refuses
 *       new entries and a second teardown does nothing. A retired core whose async callback
 *       lands late must not be able to re-attach itself to the server it no longer serves.</li>
 *   <li><strong>Entries can be pruned.</strong> Short-lived registrations (a one-shot scheduler
 *       task) would otherwise accumulate for the lifetime of the generation. {@link #prune} drops
 *       the ones a caller-supplied predicate says are already finished.</li>
 * </ul>
 *
 * <p>Not thread-safe by design intent, but synchronized anyway: registrations happen on the main
 * thread and teardown happens on the main thread, yet CreeperRecover's recovery timers run off the
 * main thread and hop back onto it with {@code runTask} for every block they restore. A monitor on a handful of list operations costs nothing at this frequency and removes a
 * whole class of "it only happens under load" bug.
 *
 * @param <T> the kind of registration being tracked
 */
public final class Registrations<T> {

    private final Consumer<T> disposer;
    private final List<T> tracked = new ArrayList<>();
    private boolean tornDown;

    /**
     * Creates a tracker.
     *
     * @param disposer how to undo one registration; invoked once per tracked entry during
     *                 {@link #teardown()} and never afterwards
     */
    public Registrations(Consumer<T> disposer) {
        this.disposer = Objects.requireNonNull(disposer, "disposer");
    }

    /**
     * Records a registration so it can be undone later.
     *
     * @param entry the registration to track
     * @return {@code true} if it was tracked, {@code false} if this tracker is already torn down
     *         — in which case the caller should treat its registration as void and not perform it
     */
    public synchronized boolean track(T entry) {
        Objects.requireNonNull(entry, "entry");
        if (tornDown) {
            return false;
        }
        tracked.add(entry);
        return true;
    }

    /**
     * Drops entries that are no longer worth tracking.
     *
     * <p>Only meaningful for registrations that can finish on their own — a scheduler task that
     * has already run. Without this, a long-lived core generation accumulates one dead
     * {@code BukkitTask} per menu click, and teardown then walks thousands of them to cancel
     * nothing.
     *
     * @param stillRelevant answers whether an entry should stay tracked
     * @return how many entries were dropped
     */
    public synchronized int prune(Predicate<T> stillRelevant) {
        Objects.requireNonNull(stillRelevant, "stillRelevant");
        if (tornDown) {
            return 0;
        }
        int before = tracked.size();
        tracked.removeIf(entry -> !stillRelevant.test(entry));
        return before - tracked.size();
    }

    /**
     * How many registrations are currently tracked. Zero after {@link #teardown()}.
     *
     * @return the tracked count
     */
    public synchronized int size() {
        return tracked.size();
    }

    /**
     * Whether {@link #teardown()} has already run.
     *
     * @return {@code true} once torn down
     */
    public synchronized boolean isTornDown() {
        return tornDown;
    }

    /**
     * Undoes every tracked registration, newest first, and permanently closes this tracker.
     *
     * <p>Each disposer failure is caught and collected rather than propagated, because the caller
     * is a plugin-disable or a swap that is already committed: the correct response to "one
     * listener would not unregister" is to log it and carry on unregistering the rest, never to
     * abandon the teardown partway.
     *
     * @return the failures thrown by disposers, in disposal order; empty when everything came
     *         down cleanly
     */
    public synchronized List<Throwable> teardown() {
        if (tornDown) {
            return List.of();
        }
        tornDown = true;
        List<Throwable> failures = new ArrayList<>();
        List<T> reversed = new ArrayList<>(tracked);
        Collections.reverse(reversed);
        tracked.clear();
        for (T entry : reversed) {
            try {
                disposer.accept(entry);
            } catch (RuntimeException | Error failure) {
                failures.add(failure);
            }
        }
        return failures;
    }
}
