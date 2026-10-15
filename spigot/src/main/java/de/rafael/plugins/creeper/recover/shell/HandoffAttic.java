package de.rafael.plugins.creeper.recover.shell;

// Vendored from TownTagsPlugin (com.towntags.shell), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.shell). Renamed into this package rather than depended on: the
// repositories share no build. Keep behaviour in step with the original when either side changes.

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The shell-held scratch space an outgoing core leaves state in for its replacement to reclaim.
 *
 * <h2>What CreeperRecover uses it for</h2>
 *
 * <p>Nothing, today. The only in-memory state a CreeperRecover core holds is the list of blocks
 * still waiting to be put back, and a stopping core restores all of them before it goes, so a swap
 * is a cold start. The attic is here anyway because it is the one place state <em>could</em> cross
 * the gap between {@link CreeperRecoverCore#stop()} and the next {@link CreeperRecoverCore#start},
 * and adding it later would be a shell change, which costs a restart.
 *
 * <h2>Why the values are restricted</h2>
 *
 * <p>The attic outlives the classloader whose core filled it. Stashing a core-defined object
 * would therefore pin that entire retired loader for as long as the value is held — the exact leak the
 * whole pattern exists to prevent — and the incoming core, loaded by a <em>different</em> loader,
 * could not cast it back anyway: same name, different {@code Class}, {@code ClassCastException}.
 *
 * <p>So {@link #put} enforces what the contract states in prose: only plain JDK value types
 * cross. Strings, numbers, booleans, characters, {@link UUID}s, {@link Instant}s, and collections
 * and maps of those. Every one of those classes is loaded by the platform loader, which is a
 * shared ancestor of both cores, so both generations see the same {@code Class} and neither
 * generation's loader is pinned by the value.
 *
 * <p>The check is recursive and runs at {@code put} time, on the main thread, during a swap that
 * has not yet stopped anything. That is the one moment where the mistake is cheap to report and
 * the stack trace still points at the core code that made it. Silently accepting a foreign type
 * would produce a leak nobody notices until the swap report's live-loader count starts climbing.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The shell clears the attic immediately before it stops a core for a swap, and again once the
 * replacement has started successfully. So the window in which anything lives here is exactly one
 * swap, and a key the incoming core forgets to reclaim is dropped rather than served to some
 * later generation that was not expecting it.
 *
 * <p>A failed swap deliberately leaves the attic populated <em>while</em> the old core is
 * restarted: the swapper's recovery path calls {@code start} again on the previous instance, and
 * that instance reclaims exactly what it just stashed. State survives a broken build.
 */
public final class HandoffAttic {

    private final Map<String, Object> values = new LinkedHashMap<>();

    /**
     * Stashes a value under a key for the next generation.
     *
     * @param key   what the incoming core will ask for; cores should namespace these by module
     *              ({@code "explosions.pending"}) so two modules cannot collide
     * @param value the value, restricted to plain JDK types as described above; {@code null} is
     *              accepted and reclaims as {@code null}
     * @throws IllegalArgumentException if {@code value} contains anything but plain JDK types,
     *                                  naming the offending class
     */
    public synchronized void put(String key, Object value) {
        Objects.requireNonNull(key, "key");
        requirePlain(value, key);
        values.put(key, value);
    }

    /**
     * Reclaims and removes a stashed value.
     *
     * <p>Removing rather than peeking is deliberate: a value read twice would mean two subsystems
     * both believe they own it, and the second one would be rehydrating from a generation older
     * than it thinks.
     *
     * @param key the key the outgoing core used
     * @return the stashed value, or {@code null} if nothing was stashed under that key — which is
     *         the normal case at boot, when there is no previous generation at all
     */
    public synchronized Object take(String key) {
        Objects.requireNonNull(key, "key");
        return values.remove(key);
    }

    /**
     * Whether anything is currently stashed.
     *
     * @return {@code true} when at least one key is held
     */
    public synchronized boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * How many keys are currently stashed. Diagnostics and tests only.
     *
     * @return the stashed key count
     */
    public synchronized int size() {
        return values.size();
    }

    /**
     * Drops everything, so a key nobody reclaimed cannot outlive the swap it belonged to.
     */
    public synchronized void clear() {
        values.clear();
    }

    /**
     * Rejects anything that would pin a core classloader, recursing into collections and maps.
     *
     * <p>{@code path} accumulates the route to the offending value ({@code "explosions.pending[2]"})
     * because the failure a developer actually hits is one bad element deep inside an otherwise
     * fine list, and "some element of some list is wrong" is not a message anyone can act on.
     */
    private static void requirePlain(Object value, String path) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Number
                || value instanceof UUID
                || value instanceof Instant) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object element : collection) {
                requirePlain(element, path + "[" + index++ + "]");
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                requirePlain(entry.getKey(), path + ".<key>");
                requirePlain(entry.getValue(), path + "." + entry.getKey());
            }
            return;
        }
        throw new IllegalArgumentException("handoff value at " + path + " is a "
                + value.getClass().getName() + "; only plain JDK types (String, Number, Boolean, "
                + "Character, UUID, Instant, and collections/maps of those) may cross a swap — "
                + "anything else pins the retired core's classloader and cannot be cast back by "
                + "the incoming generation");
    }

    /**
     * The set of keys currently held. Diagnostics and tests only; the returned set is a copy.
     *
     * @return the stashed keys
     */
    public synchronized Set<String> keys() {
        return Set.copyOf(values.keySet());
    }
}
