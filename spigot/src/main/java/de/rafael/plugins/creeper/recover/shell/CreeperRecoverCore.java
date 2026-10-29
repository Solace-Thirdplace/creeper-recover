package de.rafael.plugins.creeper.recover.shell;

/**
 * The one interface that crosses the shell/core boundary for CreeperRecover.
 *
 * <p>Everything the plugin <em>does</em> (the explosion listener, the block snapshots and their
 * timed recovery, the physics suppression, config.json, messages.json, stats.json, bStats and the
 * {@code /recover} subcommands) lives in {@code creeper-recover-core.jar}, loaded into a disposable
 * classloader so it can be replaced with {@code /recover swap} instead of a restart. This interface
 * is the seam. It is loaded by the shell's classloader, the parent of every core loader, which is
 * what lets the shell hold a core object whose class it has never seen.
 *
 * <p>It is kept to two lifecycle methods because changing it changes the shell, and changing the
 * shell needs a restart. The swap/shutdown distinction rides on
 * {@link CreeperShellContext#isSwapping()} rather than a {@code stop} argument for the same reason.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The loader constructs the core through a public no-arg constructor and starts it exactly once,
 * so a constructor must do nothing. {@link #start} is the old {@code onLoad} plus {@code onEnable};
 * {@link #stop} is the old {@code onDisable}. Both run on the main server thread. The context tears
 * down every listener, command binding and scheduled task the core made through it immediately
 * after {@code stop} returns or throws.
 *
 * <p>The entrypoint class is named by
 * {@code META-INF/services/de.rafael.plugins.creeper.recover.shell.CreeperRecoverCore} inside the
 * core jar. Exactly one provider must be declared.
 */
public interface CreeperRecoverCore {

    /**
     * Loads config and messages, registers the listeners and the {@code /recover} executor through
     * {@code context}, and starts the stats timer.
     *
     * <p>Throwing is the supported way to refuse to run. At boot the shell logs it and stays up with
     * no core, so {@code /recover swap} can load a fixed build; during a swap the swapper restarts
     * the previous core and reports the failure to whoever ran the command.
     *
     * @param context the shell facade; the only route a core should use to attach itself to the
     *                server
     */
    void start(CreeperShellContext context);

    /**
     * Takes the core down. Must leave nothing in flight: every block still waiting to be recovered
     * is put back here before this returns, because the next core (or the next server start) knows
     * nothing about this core's explosions. A block whose region the calling thread does not own
     * (a swap on Folia) is handed to that region with {@code runAtUntracked}, which outlives the
     * teardown, and goes back on the next tick.
     *
     * <p>Exceptions are logged and the swap continues: a core that is going away must never be able
     * to block its own replacement.
     */
    void stop();
}
