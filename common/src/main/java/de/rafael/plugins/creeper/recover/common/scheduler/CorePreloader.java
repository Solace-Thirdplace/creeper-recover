package de.rafael.plugins.creeper.recover.common.scheduler;

import java.io.File;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads every class of this core jar into its class loader.
 *
 * <p>Needed when a stopping core hands block restores to other regions (a swap on Folia). Those
 * restores run a tick later on this generation's classes, but by then the swapper has closed this
 * generation's class loader. A closed loader keeps the classes it has already loaded and cannot
 * load any more, so a restore that reached a class nothing had used yet (the first sign a
 * generation ever restored, say) would fail with {@code NoClassDefFoundError} and leave its block
 * missing. Loading everything first, while the loader is still open, rules that out. The jar is a
 * few hundred small classes, and this only runs during such a swap.
 */
public final class CorePreloader {

    private static final String PREFIX = "de/rafael/plugins/creeper/recover/";

    private CorePreloader() {
    }

    public static void preload(Logger logger) {
        ClassLoader loader = CorePreloader.class.getClassLoader();
        File jar;
        try {
            URL location = CorePreloader.class.getProtectionDomain().getCodeSource().getLocation();
            jar = new File(location.toURI());
        } catch (Exception | Error notAJar) {
            logger.log(Level.WARNING, "could not find the core jar to preload its classes", notAJar);
            return;
        }
        int failed = 0;
        try (JarFile file = new JarFile(jar)) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(PREFIX) || !name.endsWith(".class") || name.endsWith("module-info.class")) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                try {
                    Class.forName(className, false, loader);
                } catch (ClassNotFoundException | LinkageError skipped) {
                    // Optional integrations (WorldGuard) may not link without their plugin.
                    failed++;
                }
            }
        } catch (Exception readFailure) {
            logger.log(Level.WARNING, "could not preload the core's classes from " + jar, readFailure);
            return;
        }
        if (failed > 0) {
            logger.fine("preloaded the core's classes; " + failed + " did not load");
        }
    }
}
