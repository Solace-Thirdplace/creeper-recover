/*
 * Copyright (c) 2023. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright notice,
 *         this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *         notice, this list of conditions and the following disclaimer in the
 *         documentation and/or other materials provided with the distribution.
 *     * Neither the name of the developer nor the names of its contributors
 *         may be used to endorse or promote products derived from this software
 *         without specific prior written permission.
 *     * Redistributions in source or binary form must keep the original package
 *         and class name.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package de.rafael.plugins.creeper.recover.common;

import de.rafael.plugins.creeper.recover.common.command.RecoverCommand;
import de.rafael.plugins.creeper.recover.common.listener.BlockPhysicsListener;
import de.rafael.plugins.creeper.recover.common.listener.EntityExplodeListener;
import de.rafael.plugins.creeper.recover.common.listener.ExplosionEntityListener;
import de.rafael.plugins.creeper.recover.common.manager.ConfigManager;
import de.rafael.plugins.creeper.recover.common.manager.ExplosionManager;
import de.rafael.plugins.creeper.recover.common.manager.MessageManager;
import de.rafael.plugins.creeper.recover.common.scheduler.CoreScheduler;
import de.rafael.plugins.creeper.recover.common.stats.PluginStats;
import de.rafael.plugins.creeper.recover.common.utils.version.PluginVersion;
import de.rafael.plugins.creeper.recover.common.utils.version.UpdateChecker;
import de.rafael.plugins.creeper.recover.shell.CreeperRecoverCore;
import de.rafael.plugins.creeper.recover.shell.CreeperShellContext;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The CreeperRecover core: everything the plugin does, brought up and taken down on demand.
 *
 * <p>This used to be the plugin's {@code JavaPlugin}. It is now the entrypoint of
 * {@code creeper-recover-core.jar} (declared in
 * {@code META-INF/services/de.rafael.plugins.creeper.recover.shell.CreeperRecoverCore}), loaded by
 * the shell into its own classloader and replaceable with {@code /recover swap}. {@link #start} is
 * the old {@code onLoad} followed by the old {@code onEnable}; {@link #stop} is the old
 * {@code onDisable}. Listeners, the {@code /recover} executor and every scheduled task go through
 * the {@link CreeperShellContext} so the shell can detach them when this generation stops.
 *
 * <p>The static {@link #instance()} is per generation: each core jar is loaded by its own
 * classloader, so each generation has its own copy of this class and its statics.
 *
 * @author Rafael K.
 * @since 06/07/2023
 */

@Getter
public class CreeperPlugin implements CreeperRecoverCore {

    @Getter
    protected static PluginVersion version;
    @Getter
    private static CreeperPlugin instance;
    protected Scheduler scheduler;

    protected ExplosionManager explosionManager;
    protected ConfigManager configManager;
    protected MessageManager messageManager;
    protected PluginStats pluginStats;

    protected UpdateChecker updateChecker;

    /** The shell facade for this generation; set at {@link #start}. */
    private CreeperShellContext context;

    /** Null unless bStats is enabled in config.json; shut down when this generation stops. */
    private Metrics metrics;

    /** Required by the core loader. Does nothing: there is no context yet. */
    public CreeperPlugin() {
    }

    public static Scheduler scheduler() {
        return instance.scheduler;
    }

    @Override
    public void start(CreeperShellContext context) {
        this.context = context;
        instance = this;
        this.scheduler = new CoreScheduler(context);

        load();
        enable();
    }

    /** The old {@code onLoad}: messages, config and stats. */
    private void load() {
        PluginDescriptionFile description = context.plugin().getDescription();
        version = new PluginVersion().from(description.getVersion());

        this.messageManager = new MessageManager();
        this.messageManager.load();

        Bukkit.getConsoleSender().sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX) + "§7Loading §b"
                + description.getName() + " §7version §3" + version.toString());

        Bukkit.getConsoleSender().sendMessage("§7[CreeperRecover] Initializing ConfigManager...");
        try {
            this.configManager = new ConfigManager();
            Bukkit.getConsoleSender()
                    .sendMessage("§7[CreeperRecover] ConfigManager created, initializing PluginStats...");
        } catch (Exception e) {
            Bukkit.getConsoleSender()
                    .sendMessage("§c[CreeperRecover] Failed to create ConfigManager: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        try {
            this.pluginStats = new PluginStats();
            this.pluginStats.load();
            Bukkit.getConsoleSender().sendMessage("§7[CreeperRecover] PluginStats loaded, loading config...");
        } catch (Exception e) {
            Bukkit.getConsoleSender().sendMessage("§c[CreeperRecover] Failed to load PluginStats: " + e.getMessage());
            e.printStackTrace();
        }

        int amount = 0;
        boolean configLoaded = false;
        try {
            while (!configManager.load()) {
                amount++;
                Bukkit.getConsoleSender()
                        .sendMessage("§7[CreeperRecover] Config load attempt " + amount + " failed, retrying...");
                if (amount > 10) {
                    Bukkit.getConsoleSender().sendMessage(
                            "§c[CreeperRecover] Failed to load config after 10 attempts. Plugin may not function correctly.");
                    break;
                }
            }
            if (amount <= 10) {
                configLoaded = true;
                Bukkit.getConsoleSender().sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX)
                        + "§7The config §aloaded §7in §b" + amount + " §7cycles§8.");
            }
        } catch (Exception e) {
            Bukkit.getConsoleSender()
                    .sendMessage("§c[CreeperRecover] Exception during config loading: " + e.getMessage());
            e.printStackTrace();
        }

        Bukkit.getConsoleSender().sendMessage("§7[CreeperRecover] onLoad complete. ConfigManager null? "
                + (configManager == null) + ", Config loaded? " + configLoaded);
    }

    /** The old {@code onEnable}, with registrations routed through the context. */
    private void enable() {
        this.explosionManager = new ExplosionManager();
        this.updateChecker = new UpdateChecker(98836);

        // Check if configManager is properly initialized
        if (this.configManager == null) {
            Bukkit.getConsoleSender()
                    .sendMessage("§c[CreeperRecover] ConfigManager is null! Plugin initialization failed.");
            // The shell answers a throwing start by running without a core (boot) or by putting the
            // previous core back (swap); the old JavaPlugin disabled itself here.
            throw new IllegalStateException("ConfigManager is null; CreeperRecover core initialization failed");
        }

        if (this.configManager.bStats()) {
            int pluginId = 14155;
            this.metrics = new Metrics((JavaPlugin) context.plugin(), pluginId);
            metrics.addCustomChart(new SingleLineChart("blocksRecovered", () -> this.pluginStats.blocksRecovered()));
            metrics.addCustomChart(
                    new SingleLineChart("explosionsRecovered", () -> this.pluginStats.explosionsRecovered()));
            // bStats runs its own submitter thread that holds this generation's chart lambdas.
            context.registerCloseout(this.metrics::shutdown);
        }
        if (!this.configManager.ignoreUpdates()) {
            this.updateChecker.isLatestVersion(version, aBoolean -> {
                String current = context.plugin().getDescription().getVersion();
                if (!aBoolean) {
                    Bukkit.getConsoleSender().sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX)
                            + "§8--------------------------------------");
                    Bukkit.getConsoleSender()
                            .sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX) + " ");
                    Bukkit.getConsoleSender().sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX)
                            + "§7The plugin §bCreeperRecover §7has an §aupdate§8.");
                    Bukkit.getConsoleSender()
                            .sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX)
                                    + "§7Current Version§8: §3" + current
                                    + " §7Latest Version§8: §a" + this.updateChecker.latestVersion());
                    Bukkit.getConsoleSender()
                            .sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX) + " ");
                    Bukkit.getConsoleSender().sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX)
                            + "§cThe older version may contain bugs that could lead to item loss§8.");
                    Bukkit.getConsoleSender()
                            .sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX) + " ");
                    Bukkit.getConsoleSender().sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX)
                            + "§8--------------------------------------");
                } else {
                    Bukkit.getConsoleSender().sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX)
                            + "§7The §bCreeperRecover §7plugin is §aup to date§8.");
                }
            });
        }

        // Commands. The shell owns the /recover name (plugin.yml) and keeps "swap" for itself.
        context.registerCommand("recover", new RecoverCommand());

        // Events
        context.registerListener(new EntityExplodeListener());
        context.registerListener(new BlockPhysicsListener());
        context.registerListener(new ExplosionEntityListener());

        // Tasks (was the spigot module's onEnable)
        context.runAsyncAtFixedRate(task -> this.pluginStats.tick(), 0, 5, TimeUnit.MINUTES);
    }

    /**
     * The old {@code onDisable}, run on a swap as well as at shutdown. Puts back every block still
     * waiting for recovery before returning, so the next core (or the next boot) starts with nothing
     * in flight and no location left suppressed.
     */
    @Override
    public void stop() {
        String when = context.isSwapping() ? "the core is §eswapped" : "the server §cstops";
        if (this.explosionManager != null) {
            int recovered = this.explosionManager.recoverAllForStop();
            if (this.messageManager != null) {
                Bukkit.getConsoleSender().sendMessage(messageManager.getMessage(MessageManager.Message.PREFIX)
                        + "§7The plugin recovered §b" + recovered + " §7blocks before " + when + "§8.");
            } else {
                Bukkit.getConsoleSender().sendMessage("§7[CreeperRecover] Plugin recovered §b" + recovered
                        + " §7blocks before " + when + "§8.");
            }
        }

        if (this.pluginStats != null) {
            this.pluginStats.save();
        }
    }

    /**
     * Where this core's work runs. On Paper and Purpur "the owning thread" below is always the main
     * thread; on Folia and Canvas it is the region thread that owns the block or entity.
     */
    public interface Scheduler {

        /**
         * Runs a block restore on the thread that owns {@code location}: now, if that is the
         * calling thread, otherwise on the owning region's next tick. A restore queued from another
         * thread is kept until it has run (see {@link #runPendingNow}) and is not cancelled when
         * this generation stops.
         */
        void runOnCorrectThread(Location location, Runnable runnable);

        /**
         * Runs {@code runnable} on the thread that owns {@code location}: now, if that is the
         * calling thread, otherwise next tick. Dropped if this generation stops first.
         */
        void runAt(Location location, Runnable runnable);

        /**
         * Runs {@code runnable} on the thread that owns {@code entity}: now, if that is the calling
         * thread, otherwise next tick. If the entity is removed first, {@code retired} runs instead.
         * Dropped if this generation stops first.
         */
        void runFor(Entity entity, Runnable runnable, Runnable retired);

        void runAsync(Runnable runnable);

        void runAsyncAtFixedRate(Consumer<Runnable> runnable, int delay, int period, TimeUnit unit);

        /**
         * Runs, on the calling thread, every restore that was queued from another thread, has not
         * run yet, and whose block the calling thread owns. Used by a stopping core: those restores
         * carry blocks that are already off their explosion's list. On Paper, and at a Folia
         * shutdown, the calling thread owns every block, so this runs them all.
         *
         * @return how many restores ran
         */
        int runPendingNow();

        /**
         * @return how many queued restores have not run yet. After {@link #runPendingNow} these
         *         are restores in regions the calling thread does not own; they run there on the
         *         next tick.
         */
        int pendingElsewhere();

    }

}
