package de.rafael.plugins.creeper.recover.shell;

// Vendored from TownTagsPlugin (com.towntags.shell), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.shell). Renamed into this package rather than depended on: the
// repositories share no build. Keep behaviour in step with the original when either side changes.

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Objects;

/**
 * The permanent Bukkit-side handler for one command name, forwarding to whichever core is
 * currently running.
 *
 * <p>CreeperRecover used to register {@code /recover} by reflecting into the command map from
 * its {@code onEnable}. The shell now declares it in plugin.yml instead and puts this relay behind
 * it, so the core never touches the command map.
 *
 * <h2>Why commands cannot simply be re-registered</h2>
 *
 * <p>Bukkit's command map is populated from plugin.yml once, at plugin load. A
 * {@code PluginCommand} can have its executor replaced freely, but the command <em>name</em>
 * itself is not something a plugin can drop and re-add at runtime without reaching into the
 * command map and its alias tables — and getting that wrong means {@code /recover} stops existing
 * until the server restarts, which is a spectacular way to fail at "iterate without restarting".
 *
 * <p>So the shell registers one of these per command name at boot and never touches the
 * registration again. A core generation fills the delegate slot in
 * {@link CreeperShellContext#registerCommand}, and teardown empties it. From the player's side the
 * command is continuously present; from the core's side it is an ordinary {@link CommandExecutor}
 * it hands over and forgets.
 *
 * <h2>The coreless window</h2>
 *
 * <p>Between one core stopping and the next starting there is no delegate. That window is
 * microseconds during a swap, but it is permanent when the boot jar is missing and the shell has
 * disabled itself, or when a swap's start failed and recovery also failed. Rather than let Bukkit
 * answer "unknown command" (which reads like the plugin is broken) the relay says the core is not
 * running and points at the console, where the reason is.
 *
 * <p>The delegate is {@code volatile} because commands are dispatched on the main thread while a
 * swap could, in principle, be driven from elsewhere; publication has to be safe either way.
 */
public final class CommandRelay implements CommandExecutor, TabCompleter {

    /** What a player sees if they type the command during the coreless window. */
    public static final String NO_CORE_MESSAGE =
            "§cThe CreeperRecover core is not running; check the console.";

    private final String name;
    private volatile CommandExecutor delegate;

    /**
     * Creates a relay for one command name.
     *
     * @param name the plugin.yml command name this relay is registered against
     */
    public CommandRelay(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** @return the plugin.yml command name this relay serves */
    public String name() {
        return name;
    }

    /**
     * Points the command at a core's executor.
     *
     * @param executor the new handler; replaces any previous one outright
     */
    public void bind(CommandExecutor executor) {
        this.delegate = Objects.requireNonNull(executor, "executor");
    }

    /** Empties the slot, so the command reports that the plugin is reloading. */
    public void unbind() {
        this.delegate = null;
    }

    /** @return the currently bound executor, or {@code null} while no core is running */
    public CommandExecutor delegate() {
        return delegate;
    }

    /**
     * Forwards the invocation to the running core.
     *
     * <p>{@code label} is passed through untouched so the core's usage text still echoes whatever
     * the player actually typed, alias included — {@code /tw} has to keep saying {@code /tw}.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandExecutor current = delegate;
        if (current == null) {
            sender.sendMessage(NO_CORE_MESSAGE);
            return true;
        }
        return current.onCommand(sender, command, label, args);
    }

    /**
     * Forwards completion to the running core when its executor can complete, and otherwise
     * returns an empty list.
     *
     * <p>Empty rather than {@code null}: {@code null} tells Bukkit to fall back to online player
     * names, which would be a confusing thing for {@code /recover} to suggest while the core is
     * between generations.
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        CommandExecutor current = delegate;
        if (current instanceof TabCompleter completer) {
            List<String> completions = completer.onTabComplete(sender, command, alias, args);
            return completions == null ? List.of() : completions;
        }
        return List.of();
    }
}
