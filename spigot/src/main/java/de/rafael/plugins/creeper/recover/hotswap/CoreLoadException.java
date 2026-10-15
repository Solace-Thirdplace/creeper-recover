package de.rafael.plugins.creeper.recover.hotswap;

// Vendored from TownTagsPlugin (com.towntags.hotswap), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.hotswap) and Arcadia's :hotswap module. Renamed into this package rather
// than depended on: the repositories share no build. Keep behaviour in step with the original
// when either changes.

/**
 * Signals that a core jar could not be loaded, or that a swap could not be carried through.
 *
 * <p>This is a <em>checked</em> exception on purpose. Every caller of this library is a swap
 * command running inside a live server: a failed load must degrade into a red chat message,
 * never into an unhandled throwable that takes a listener thread — or the proxy — down with
 * it. Making the compiler insist on a catch block is the cheapest way to guarantee that.
 *
 * <p>Messages are written for the admin who typed the command, not for a stack-trace reader:
 * they name the offending jar and state what was actually found, because the usual cause is
 * a build that forgot the service declaration or staged the wrong file.
 */
public class CoreLoadException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with an operator-facing explanation.
     *
     * @param message what went wrong, naming the jar where relevant
     */
    public CoreLoadException(String message) {
        super(message);
    }

    /**
     * Creates an exception wrapping a lower-level failure.
     *
     * <p>Reflective and I/O failures are wrapped rather than propagated so that callers only
     * ever have to catch one type, and so the wrapper can add the jar path that the
     * underlying {@link ReflectiveOperationException} has no idea about.
     *
     * @param message what went wrong, naming the jar where relevant
     * @param cause   the underlying failure
     */
    public CoreLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
