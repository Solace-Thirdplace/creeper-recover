package de.rafael.plugins.creeper.recover.shell;

// Vendored from TownTagsPlugin (com.towntags.shell.StagedCore). Keep behaviour in step with the
// original when either side changes.

import de.rafael.plugins.creeper.recover.hotswap.CoreHandle;
import de.rafael.plugins.creeper.recover.hotswap.CoreLoadException;
import de.rafael.plugins.creeper.recover.hotswap.CoreSwapper;
import de.rafael.plugins.creeper.recover.hotswap.SwapReport;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The boot core jar, the staged jar beside it, and the two things the shell does with them:
 * choose which one to start at boot, and promote the staged one once it is running.
 *
 * <p>Kept out of the plugin class so the boot decision can be tested against real jars and a real
 * {@link CoreSwapper} without a server. Both the boot path and {@code /recover swap} promote through
 * {@link #promote()}, so there is one definition of "the staged build is now the boot build".
 *
 * <h2>The boot decision</h2>
 *
 * <ul>
 *   <li><strong>No staged jar</strong> — load the boot jar, as before.</li>
 *   <li><strong>Staged jar older than the boot jar</strong> — load the boot jar and warn. An old
 *       staged file is far more likely to be a leftover than a deliberate rollback, and a rollback
 *       is still one {@code /recover swap} away.</li>
 *   <li><strong>Otherwise</strong> — start the staged jar, then promote it. Start first, promote
 *       second, for the same reason the swap command does it that way: a build that fails to start
 *       must never have already replaced the jar the next boot depends on.</li>
 *   <li><strong>Staged jar fails to load or start</strong> — log it, leave the file where it is,
 *       and start the boot jar instead. A server that comes up on the previous build beats one that
 *       comes up without CreeperRecover. With no boot jar to fall back to, the failure propagates and the
 *       shell treats it as a missing core.</li>
 * </ul>
 *
 * <p>The fallback relies on {@link CoreSwapper#loadInitial} being retryable after a failure: a
 * failed start leaves the swapper coreless with the failed loader closed, and the shell's start
 * callback has already torn down everything that generation registered.
 *
 * @param <T> the core contract type
 */
public final class StagedCore<T> {

    /** The command that loads a staged core on purpose, named in the skip warning. */
    private static final String SWAP_COMMAND = "/recover swap";

    private final Path bootJar;
    private final Path stagedJar;
    private final Logger logger;

    /**
     * @param bootJar   the jar loaded at every boot
     * @param stagedJar where a deploy drops the next build
     * @param logger    the shell plugin's logger
     */
    public StagedCore(Path bootJar, Path stagedJar, Logger logger) {
        this.bootJar = Objects.requireNonNull(bootJar, "bootJar");
        this.stagedJar = Objects.requireNonNull(stagedJar, "stagedJar");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return the jar loaded at every boot */
    public Path bootJar() {
        return bootJar;
    }

    /** @return where a deploy drops the next build */
    public Path stagedJar() {
        return stagedJar;
    }

    /** @return whether a build is waiting in the staged slot */
    public boolean isStaged() {
        return Files.isRegularFile(stagedJar);
    }

    /**
     * Starts the core a boot should run — the staged build when there is a usable one, the boot jar
     * otherwise — and logs which one it was.
     *
     * @param swapper the shell's swapper, still coreless
     * @param start   the shell's start callback; must undo everything a core registered before
     *                rethrowing, since a failed staged start is followed by a second attempt
     * @return the report of the core now running
     * @throws CoreLoadException if no core could be started at all
     */
    public SwapReport loadAtBoot(CoreSwapper<T> swapper, BiConsumer<T, CoreHandle<T>> start)
            throws CoreLoadException {
        if (!isStaged()) {
            return loadBootJar(swapper, start);
        }
        boolean hasBootJar = Files.isRegularFile(bootJar);
        if (hasBootJar && stagedIsOlder()) {
            logger.warning("CreeperRecover ignored the staged core " + stagedJar + " at startup because it is older than "
                    + bootJar + ". Run " + SWAP_COMMAND + " to load it on purpose.");
            return loadBootJar(swapper, start);
        }

        SwapReport report;
        try {
            report = swapper.loadInitial(stagedJar, start);
        } catch (CoreLoadException failure) {
            if (!hasBootJar) {
                throw failure;
            }
            logger.log(Level.SEVERE, "CreeperRecover could not start the staged core " + stagedJar
                    + " at startup, falling back to " + bootJar, failure);
            return loadBootJar(swapper, start);
        }
        logger.info("CreeperRecover applied the staged core at startup: " + report.summary());

        try {
            promote();
        } catch (IOException failure) {
            // The new core is running; only the file layout is behind. The staged file is still the
            // newer of the two, so the next boot applies it again.
            logger.log(Level.WARNING, "CreeperRecover applied the staged core at startup but could not promote "
                    + stagedJar + " over " + bootJar, failure);
        }
        return report;
    }

    /**
     * Moves the staged jar over the boot jar. Call only once the staged core is confirmed running.
     *
     * <p>Atomic where the filesystem allows it, so a crash mid-promote can never leave a
     * half-written boot jar.
     *
     * @throws IOException if the move failed; the staged file is then still in place
     */
    public void promote() throws IOException {
        try {
            Files.move(stagedJar, bootJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(stagedJar, bootJar, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private SwapReport loadBootJar(CoreSwapper<T> swapper, BiConsumer<T, CoreHandle<T>> start)
            throws CoreLoadException {
        SwapReport report = swapper.loadInitial(bootJar, start);
        logger.info("CreeperRecover " + report.summary());
        return report;
    }

    /**
     * Whether the staged jar's modification time is strictly before the boot jar's.
     *
     * <p>An unreadable timestamp counts as "not older": the staged build is then tried, and the
     * fallback still covers it if it is bad.
     */
    private boolean stagedIsOlder() {
        try {
            return Files.getLastModifiedTime(stagedJar).compareTo(Files.getLastModifiedTime(bootJar)) < 0;
        } catch (IOException unreadable) {
            return false;
        }
    }
}
