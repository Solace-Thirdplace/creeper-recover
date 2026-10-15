package de.rafael.plugins.creeper.recover;

import de.rafael.plugins.creeper.recover.hotswap.CoreHandle;
import de.rafael.plugins.creeper.recover.hotswap.CoreLoadException;
import de.rafael.plugins.creeper.recover.hotswap.CoreSwapper;
import de.rafael.plugins.creeper.recover.hotswap.SwapReport;
import de.rafael.plugins.creeper.recover.shell.CommandRelay;
import de.rafael.plugins.creeper.recover.shell.CreeperRecoverCore;
import de.rafael.plugins.creeper.recover.shell.HandoffAttic;
import de.rafael.plugins.creeper.recover.shell.ShellContext;
import de.rafael.plugins.creeper.recover.shell.StagedCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * CreeperRecover as Bukkit sees it: the shell, and nothing more.
 *
 * <p>None of the plugin's behaviour lives here. The explosion listener, block snapshots, timed
 * recovery, physics suppression, config, messages, stats and the {@code /recover} subcommands all
 * live in {@code creeper-recover-core.jar}, loaded into a disposable classloader so a change is
 * {@code /recover swap} instead of a restart. What stays here is what Bukkit will not let go of at
 * runtime: plugin.yml and this main class, the {@code /recover} command name with a permanent
 * {@link CommandRelay} behind it, and the swap protocol. Changing anything in this jar needs a
 * restart, which is why there is so little of it.
 *
 * <p>Ported from the TownTags shell (com.towntags.TownTagsPlugin), which follows ThirdPlaceMcTweaks.
 * One difference: a core that fails to load at boot leaves the plugin enabled with no core, as
 * VelocityNicknames does, so a fixed build can be staged and loaded with {@code /recover swap}
 * without a restart.
 *
 * <h2>Deploy layout</h2>
 *
 * <pre>
 * plugins/creeper-recover-1.1.4-spigot.jar                     the shell (restart to update)
 * plugins/CreeperRecover/core/creeper-recover-core.jar         loaded at boot
 * plugins/CreeperRecover/core/creeper-recover-core.jar.staged  dropped in by a deploy; /recover swap or a restart
 * </pre>
 *
 * <p>A successful swap moves the staged file over the boot jar, so the running core and the one the
 * next restart loads are the same build. A failed swap leaves the old core running and the staged
 * file in place. A restart also applies a staged build (see {@link StagedCore}).
 *
 * <h2>In-flight explosions</h2>
 *
 * <p>The outgoing core puts back every block still waiting for recovery during its stop, on the
 * main thread, exactly as a server shutdown always has. The incoming core starts with nothing in
 * flight, so nothing is handed across and nothing can be lost in the gap.
 */
public final class CreeperRecover extends JavaPlugin implements CommandExecutor, TabCompleter {

    /** Directory under the data folder holding the boot and staged core jars. */
    public static final String CORE_DIRECTORY = "core";

    /** The jar loaded at every boot. */
    public static final String CORE_JAR = "creeper-recover-core.jar";

    /** Suffix a deploy adds to stage a build for the next {@code /recover swap}. */
    public static final String STAGED_SUFFIX = ".staged";

    /** Who may replace the running core; the node the core already uses for admin subcommands. */
    public static final String ADMIN_PERMISSION = "creeper.recover.admin";

    /** The one command name the shell claims from plugin.yml. */
    private static final String COMMAND = "recover";

    private static final String SWAP = "swap";

    /** The permanent forwarding executor behind {@code /recover}. */
    private CommandRelay relay;

    /** State one core generation hands to the next; empty except during a swap. */
    private final HandoffAttic attic = new HandoffAttic();

    private CoreSwapper<CreeperRecoverCore> swapper;

    private StagedCore<CreeperRecoverCore> cores;

    /** The context of the running core generation; null while no core is running. */
    private ShellContext context;

    /** True from just before a swap stops the old core until the new one has started. */
    private volatile boolean swapping;

    @Override
    public void onEnable() {
        if (!claimCommand()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.swapper = new CoreSwapper<>(CreeperRecoverCore.class, getLogger());
        this.cores = new StagedCore<>(coreJar(), stagedJar(), getLogger());
        try {
            cores.loadAtBoot(swapper, this::startCore);
        } catch (CoreLoadException failure) {
            getLogger().log(Level.SEVERE, "CreeperRecover could not load its core from " + cores.bootJar()
                    + ". Explosions are NOT being recovered until a core is staged at " + cores.stagedJar()
                    + " and /recover swap is run.", failure);
        }
    }

    /**
     * Stops the running core. Its stop puts back every block still waiting for recovery, which is
     * what the old {@code onDisable} did.
     */
    @Override
    public void onDisable() {
        if (swapper != null) {
            CoreHandle<CreeperRecoverCore> handle = swapper.current();
            if (handle != null) {
                stopCore(handle.instance());
            }
        }
        attic.clear();
    }

    /**
     * {@code /recover}: {@code swap} is handled here, everything else goes to the core. {@code swap}
     * is therefore reserved; a core subcommand by that name would be unreachable.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase(SWAP)) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage("§cYou don't have permission to swap the CreeperRecover core.");
                return true;
            }
            swapCore(sender);
            return true;
        }
        return relay.onCommand(sender, command, label, args);
    }

    /** Offers {@code swap} to admins alongside whatever the core suggests. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>(relay.onTabComplete(sender, command, alias, args));
        if (args.length == 1
                && sender.hasPermission(ADMIN_PERMISSION)
                && SWAP.startsWith(args[0].toLowerCase(Locale.ROOT))) {
            completions.add(SWAP);
        }
        return completions;
    }

    /**
     * Replaces the running core with the staged jar, or loads it if no core is running, and reports
     * the outcome. The staged jar is promoted over the boot jar only once its core is running, so a
     * build that fails to load can never replace the jar the next restart depends on.
     *
     * @param sender whoever ran the command
     */
    public void swapCore(CommandSender sender) {
        if (swapper == null) {
            sender.sendMessage("§cCreeperRecover never finished enabling; restart the server.");
            return;
        }
        Path staged = cores.stagedJar();
        if (!cores.isStaged()) {
            sender.sendMessage("§cNo staged core at " + staged + ". Copy the new " + CORE_JAR + " there as "
                    + CORE_JAR + STAGED_SUFFIX + " first.");
            return;
        }

        SwapReport report;
        attic.clear();
        swapping = true;
        try {
            report = swapper.current() == null
                    ? swapper.loadInitial(staged, this::startCore)
                    : swapper.swap(staged, this::stopCore, this::startCore);
        } catch (CoreLoadException failure) {
            getLogger().log(Level.WARNING, "CreeperRecover core swap from " + staged + " failed", failure);
            sender.sendMessage("§cSwap failed: " + failure.getMessage());
            return;
        } finally {
            swapping = false;
            attic.clear();
        }

        getLogger().info("CreeperRecover " + report.summary());
        sender.sendMessage("§aCreeperRecover " + report.summary());
        promoteStagedJar(sender);
    }

    /**
     * Moves the staged jar over the boot jar now its core is running. A failure here is not a failed
     * swap, but the next restart would load the old build, so it is reported loudly.
     */
    private void promoteStagedJar(CommandSender sender) {
        Path staged = cores.stagedJar();
        Path bootJar = cores.bootJar();
        try {
            cores.promote();
        } catch (IOException failure) {
            getLogger().log(Level.WARNING, "CreeperRecover swapped to the staged core but could not promote "
                    + staged + " over " + bootJar, failure);
            sender.sendMessage("§eThe new core is running, but " + staged.getFileName() + " could not replace "
                    + bootJar.getFileName() + ". The next restart will load the OLD build. Fix it by hand.");
        }
    }

    /**
     * Starts a core generation behind a fresh {@link ShellContext}. Runs at boot, on every swap, and
     * once more to bring the previous core back when a new one fails to start. A core that throws
     * has its registrations torn down before the exception leaves.
     */
    private void startCore(CreeperRecoverCore core, CoreHandle<CreeperRecoverCore> handle) {
        ShellContext fresh = new ShellContext(this, Map.of(COMMAND, relay), attic, () -> swapping);
        context = fresh;
        try {
            core.start(fresh);
        } catch (Throwable failure) {
            fresh.teardown();
            context = null;
            if (failure instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("core " + handle.shortSha() + " threw a checked exception from start",
                    failure);
        }
    }

    /** Stops a core generation, then detaches everything it registered, even if its stop threw. */
    private void stopCore(CreeperRecoverCore core) {
        try {
            core.stop();
        } finally {
            ShellContext retiring = context;
            context = null;
            if (retiring != null) {
                retiring.teardown();
            }
        }
    }

    /**
     * Puts this class in front of {@code /recover} and creates the permanent relay behind it. Done
     * once and never undone.
     *
     * @return {@code false} if plugin.yml is missing the command
     */
    private boolean claimCommand() {
        PluginCommand command = getCommand(COMMAND);
        if (command == null) {
            getLogger().severe("The '" + COMMAND + "' command is missing from plugin.yml; disabling CreeperRecover.");
            return false;
        }
        relay = new CommandRelay(COMMAND);
        command.setExecutor(this);
        command.setTabCompleter(this);
        return true;
    }

    /** {@code plugins/CreeperRecover/core/creeper-recover-core.jar}, the jar loaded at boot. */
    private Path coreJar() {
        return getDataFolder().toPath().resolve(CORE_DIRECTORY).resolve(CORE_JAR);
    }

    /** The same path with {@link #STAGED_SUFFIX}, where a deploy drops the next build. */
    private Path stagedJar() {
        return getDataFolder().toPath().resolve(CORE_DIRECTORY).resolve(CORE_JAR + STAGED_SUFFIX);
    }
}
