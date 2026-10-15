package de.rafael.plugins.creeper.recover.hotswap;

// Vendored from TownTagsPlugin (com.towntags.hotswap), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.hotswap) and Arcadia's :hotswap module. Renamed into this package rather
// than depended on: the repositories share no build. Keep behaviour in step with the original
// when either changes.

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * One loaded core: the live instance plus everything needed to identify it and to throw it
 * away again.
 *
 * <p>A handle is the unit the shell hands around. It bundles the {@code T} instance with its
 * private {@link URLClassLoader} because the two have exactly the same lifetime — the loader
 * exists to produce that instance and is closed the moment the instance is retired. Keeping
 * them together means no caller can accidentally close a loader whose core is still running,
 * or forget to close one whose core is not.
 *
 * <p>The provenance fields exist for the swap command's output and for incident triage. When
 * an admin reports "the swap didn't take", the first question is always <em>which jar is
 * actually running</em>; {@link #sha256()} answers it without trusting the file on disk,
 * which may well have been overwritten since. {@link #loadedAt()} answers "how long has this
 * been up", which distinguishes a core that failed to swap from one that swapped and then
 * broke.
 *
 * <p>Handles are immutable value objects and safe to publish across threads. The referenced
 * instance and loader are, of course, as thread-safe as the core makes them.
 *
 * @param <T>      the per-plugin core contract type
 * @param instance the core object, freshly constructed and <em>not</em> yet started
 * @param loader   the disposable loader that produced {@code instance}
 * @param jar      the core jar this was loaded from, as given to the loader
 * @param sha256   lowercase hex SHA-256 of the jar's bytes at load time
 * @param loadedAt when the load completed
 */
public record CoreHandle<T>(T instance, URLClassLoader loader, Path jar, String sha256, Instant loadedAt) {

    /** Number of hex characters in a "short" digest — long enough to be unambiguous in chat. */
    private static final int SHORT_SHA_LENGTH = 8;

    /**
     * Validates that no component is null.
     *
     * <p>A half-populated handle would let a swap close a null loader or print a null digest
     * long after the mistake that produced it, so the check is done at construction where the
     * stack trace still points at the culprit.
     */
    public CoreHandle {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(loadedAt, "loadedAt");
    }

    /**
     * Returns the first {@value #SHORT_SHA_LENGTH} hex characters of {@link #sha256()}.
     *
     * <p>Swap output goes into a Minecraft chat line, where a 64-character digest wraps into
     * unreadable soup. Eight characters is plenty to tell two builds of the same plugin apart,
     * which is the only comparison anyone actually makes.
     *
     * @return the abbreviated digest
     */
    public String shortSha() {
        return sha256.length() <= SHORT_SHA_LENGTH ? sha256 : sha256.substring(0, SHORT_SHA_LENGTH);
    }
}
