package de.rafael.plugins.creeper.recover.hotswap;

// Vendored from TownTagsPlugin (com.towntags.hotswap), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.hotswap) and Arcadia's :hotswap module. Renamed into this package rather
// than depended on: the repositories share no build. Keep behaviour in step with the original
// when either changes.

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads a core jar into a disposable classloader and instantiates its entrypoint.
 *
 * <p>This is deliberately the <em>only</em> place in the library that knows how a core jar is
 * shaped. Splitting discovery out of {@link CoreSwapper} keeps the swap protocol testable
 * against a stub loader, and lets a shell load a core for inspection (say, to print its digest
 * before swapping) without owning a swapper.
 *
 * <h2>Class loading model</h2>
 *
 * <p>Each load gets a fresh {@link URLClassLoader} whose parent is
 * {@code contract.getClassLoader()} — the loader that loaded the per-plugin contract
 * interface, which in practice is the plugin shell's own loader. Delegation is left
 * parent-first (the {@link URLClassLoader} default; this class deliberately does not override
 * {@code loadClass}), which is what makes the whole pattern work:
 *
 * <ul>
 *   <li>The platform API (Bukkit/Velocity) resolves from the parent, so a core's
 *       {@code Listener} is the <em>same</em> {@code Listener} the server will accept.</li>
 *   <li>This library, shaded into the shell, also resolves from the parent, so the
 *       {@link CoreHandle} a core receives is the shell's {@code CoreHandle}.</li>
 *   <li>The core jar therefore only ever contributes classes nothing else has — its own —
 *       which is exactly the isolation needed for the old copy to be collectable once the
 *       loader is closed.</li>
 * </ul>
 *
 * <p>A child-first loader would break all three: cores would see their own shaded platform
 * classes and every cross-boundary call would fail with a {@code ClassCastException} between
 * two identically named types.
 *
 * <h2>Entrypoint discovery</h2>
 *
 * <p>The entrypoint is named by {@code META-INF/services/<contract FQCN>} inside the jar —
 * standard {@link java.util.ServiceLoader} layout, so a core can declare it with a plain
 * resource file or with {@code @AutoService}, and IDEs already understand it. The file is read
 * straight out of the jar rather than through {@code ServiceLoader} for two reasons: the
 * service file must come from <em>this jar</em> and not from anything the parent contributes,
 * and {@code ServiceLoader} silently tolerates zero or many providers where a core jar must
 * declare exactly one — anything else is a build mistake worth naming loudly.
 *
 * <p>Loading never touches the core's lifecycle. Starting is the shell's job, because only the
 * shell knows what platform registrations to hand the core, and because
 * {@link CoreSwapper} needs a fully constructed instance to exist <em>before</em> it stops the
 * old one.
 *
 * @param <T> the per-plugin core contract type
 */
public final class CoreLoader<T> {

    private static final String SERVICES_PREFIX = "META-INF/services/";

    /** Creates a loader. Instances are stateless and may be shared. */
    public CoreLoader() {
    }

    /**
     * Loads {@code jar}, resolves its single entrypoint, and instantiates it.
     *
     * <p>On any failure the freshly opened classloader is closed before the exception escapes,
     * so a rejected jar leaves nothing pinned — a swap command can be spammed against a broken
     * build without accumulating loaders.
     *
     * <p>The jar's SHA-256 is computed here, from the bytes that were actually opened, so the
     * resulting handle records what is running even if the file on disk is replaced a second
     * later by the next deploy.
     *
     * @param jar      path to the core jar; must be an existing regular file
     * @param contract the per-plugin core contract interface, whose classloader becomes the
     *                 parent of the core's loader
     * @return a handle over the constructed, <em>not yet started</em> core
     * @throws CoreLoadException if the jar is missing or unreadable, declares zero or more
     *                           than one provider, names a class that is absent or does not
     *                           implement {@code contract}, or offers no usable public no-arg
     *                           constructor
     */
    public CoreHandle<T> load(Path jar, Class<T> contract) throws CoreLoadException {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(contract, "contract");

        if (!Files.isRegularFile(jar)) {
            throw new CoreLoadException("core jar " + jar + " does not exist (or is not a regular file)");
        }

        String sha256 = sha256(jar);
        URLClassLoader loader = openLoader(jar, contract);
        try {
            String providerName = readSingleProvider(jar, contract);
            T instance = instantiate(loader, providerName, jar, contract);
            return new CoreHandle<>(instance, loader, jar, sha256, Instant.now());
        } catch (CoreLoadException | RuntimeException | Error failure) {
            closeQuietly(loader, failure);
            throw failure;
        }
    }

    /**
     * Opens the disposable loader for a core jar.
     *
     * <p>The loader is named after the jar so that thread dumps, {@code jcmd VM.classloaders}
     * and heap dumps identify which core generation a leaked class belongs to — the single
     * most useful piece of evidence when the leak telltale starts climbing.
     */
    private URLClassLoader openLoader(Path jar, Class<T> contract) throws CoreLoadException {
        URL url;
        try {
            url = jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new CoreLoadException("core jar path " + jar + " is not a usable URL", e);
        }
        // A null parent (contract loaded by the bootstrap loader) is legal and means exactly
        // what it should: delegate to the bootstrap loader only.
        return new URLClassLoader("creeperrecover-core:" + jar.getFileName(), new URL[]{url}, contract.getClassLoader());
    }

    /**
     * Reads {@code META-INF/services/<contract>} from inside the jar and returns the one
     * provider class name it must declare.
     *
     * <p>Comment ({@code #}) and blank lines are tolerated, matching
     * {@link java.util.ServiceLoader}'s own parsing, so a generated service file with a
     * provenance header still works. Everything else is an error with a message that says
     * which jar and what was found, because "zero providers" and "two providers" are both
     * build bugs whose fix is obvious once named.
     */
    private String readSingleProvider(Path jar, Class<T> contract) throws CoreLoadException {
        String resource = SERVICES_PREFIX + contract.getName();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(resource);
            if (entry == null) {
                throw new CoreLoadException("core jar " + jar + " has no " + resource
                        + " — the core must declare its entrypoint as a service provider");
            }
            List<String> providers = new ArrayList<>();
            try (InputStream in = jarFile.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int comment = line.indexOf('#');
                    if (comment >= 0) {
                        line = line.substring(0, comment);
                    }
                    line = line.trim();
                    if (!line.isEmpty()) {
                        providers.add(line);
                    }
                }
            }
            if (providers.isEmpty()) {
                throw new CoreLoadException("core jar " + jar + " declares no provider in " + resource
                        + " — exactly one entrypoint class name is required");
            }
            if (providers.size() > 1) {
                throw new CoreLoadException("core jar " + jar + " declares " + providers.size()
                        + " providers in " + resource + " " + providers
                        + " — exactly one entrypoint class name is required");
            }
            return providers.get(0);
        } catch (IOException e) {
            throw new CoreLoadException("could not read " + resource + " from core jar " + jar, e);
        }
    }

    /**
     * Loads the named entrypoint through the core loader and constructs it.
     *
     * <p>Every reflective failure mode gets its own message. These are the errors an admin
     * hits after a refactor — renamed class, constructor grown an argument, entrypoint left
     * out of the jar — and each one has a different fix, so collapsing them into a generic
     * "could not instantiate" would waste the operator's time.
     */
    private T instantiate(URLClassLoader loader, String providerName, Path jar, Class<T> contract)
            throws CoreLoadException {
        Class<?> providerClass;
        try {
            providerClass = Class.forName(providerName, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new CoreLoadException("entrypoint " + providerName + " declared by core jar " + jar
                    + " could not be loaded", e);
        }
        if (!contract.isAssignableFrom(providerClass)) {
            throw new CoreLoadException("entrypoint " + providerName + " in core jar " + jar
                    + " does not implement " + contract.getName());
        }
        Constructor<?> constructor;
        try {
            constructor = providerClass.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new CoreLoadException("entrypoint " + providerName + " in core jar " + jar
                    + " has no public no-arg constructor", e);
        }
        try {
            return contract.cast(constructor.newInstance());
        } catch (InvocationTargetException e) {
            throw new CoreLoadException("constructor of entrypoint " + providerName + " in core jar " + jar
                    + " threw " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new CoreLoadException("entrypoint " + providerName + " in core jar " + jar
                    + " could not be instantiated", e);
        }
    }

    /**
     * Closes a loader whose load failed, attaching any close failure to the original problem.
     *
     * <p>The load failure is what the operator needs to see; a secondary close error must not
     * replace it, so it rides along as a suppressed exception.
     */
    private void closeQuietly(URLClassLoader loader, Throwable primaryFailure) {
        try {
            loader.close();
        } catch (IOException | RuntimeException e) {
            primaryFailure.addSuppressed(e);
        }
    }

    /**
     * Computes the lowercase hex SHA-256 of a file.
     *
     * <p>SHA-256 rather than a timestamp or size because the whole point is to answer "is the
     * running core the build I just deployed" across machines and rebuilds, where mtimes lie
     * and sizes collide.
     */
    private static String sha256(Path file) throws CoreLoadException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new CoreLoadException("SHA-256 is unavailable in this JVM", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new CoreLoadException("could not read core jar " + file + " to digest it", e);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
