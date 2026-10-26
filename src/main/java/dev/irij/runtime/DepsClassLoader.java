package dev.irij.runtime;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The class loader for a program's Java libraries — the jars its
 * {@code irij.toml} {@code [jvm]} (and its seeds') resolve to.
 *
 * <p>It sits between the Irij runtime and the program: every loader that
 * defines compiled Irij classes has it as parent, and
 * {@link JavaInterop#resolveClass} looks classes up through it, so a cap
 * provider or a {@code Class/member} site can name a library class.
 *
 * <p>Lookup is <b>child-first</b>, except for the JDK and the Irij
 * runtime ({@code dev.irij.*}): a library gets the gson, ASM or antlr it
 * shipped with, not the version the runtime happens to bundle. Without
 * that, a library needing a newer gson would fail with a
 * {@code NoSuchMethodError} deep inside its own code. Anything the jars
 * don't have falls through to the runtime.
 *
 * <p>One loader per process is current ({@link #current()}); with no
 * {@code [jvm]} it is the runtime's own loader, so nothing changes for a
 * program without Java libraries.
 */
public final class DepsClassLoader extends URLClassLoader {

    static { ClassLoader.registerAsParallelCapable(); }

    private static final ClassLoader RUNTIME = DepsClassLoader.class.getClassLoader();
    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

    private static volatile ClassLoader current = RUNTIME;
    private static volatile List<Path> currentJars = List.of();

    private DepsClassLoader(URL[] urls, ClassLoader parent) {
        super("irij-deps", urls, parent);
    }

    /** The loader programs resolve Java classes through. */
    public static ClassLoader current() {
        return current;
    }

    /** The jars {@link #current()} loads from (empty without {@code [jvm]}). */
    public static List<Path> jars() {
        return currentJars;
    }

    /**
     * Make {@code jars} the program's Java libraries. Installing the same
     * list again keeps the loader (a REPL re-reading its project, a test
     * runner going file to file); a different list replaces it, and an
     * empty one restores the runtime's own loader.
     */
    public static synchronized ClassLoader install(List<Path> jars) {
        List<Path> normalized = jars.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        if (normalized.equals(currentJars)) return current;
        ClassLoader next;
        if (normalized.isEmpty()) {
            next = RUNTIME;
        } else {
            URL[] urls = new URL[normalized.size()];
            for (int i = 0; i < urls.length; i++) {
                try {
                    urls[i] = normalized.get(i).toUri().toURL();
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException("bad jar path " + normalized.get(i), e);
                }
            }
            next = new DepsClassLoader(urls, RUNTIME);
        }
        current = next;
        currentJars = normalized;
        JavaInterop.clearClassCache();
        return next;
    }

    /** Classes only the parent may define: the JDK's and Irij's own. A
     *  library jar that ships a {@code dev.irij} class can't replace the
     *  runtime under the program. */
    private static boolean parentOnly(String name) {
        return name.startsWith("dev.irij.") || name.startsWith("java.");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (parentOnly(name)) {
                    c = getParent().loadClass(name);
                } else {
                    try {
                        c = PLATFORM.loadClass(name);          // the JDK (javax.sql, jdk.*, …)
                    } catch (ClassNotFoundException notJdk) {
                        try {
                            c = findClass(name);               // the program's jars
                        } catch (ClassNotFoundException notOurs) {
                            c = getParent().loadClass(name);   // the runtime's libraries
                        }
                    }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        URL url = PLATFORM.getResource(name);
        if (url == null) url = findResource(name);
        if (url == null) url = getParent().getResource(name);
        return url;
    }

    /** The jars' resources before the runtime's, so a library's
     *  {@code ServiceLoader} sees its own providers first. */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> all = new ArrayList<>(Collections.list(findResources(name)));
        all.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(all);
    }
}
