package dev.irij.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Main class of an app JAR built with Java libraries ({@code irij.toml}
 * {@code [jvm]}). Such a JAR carries the libraries as whole jars under
 * {@code __irij_lib/} and the compiled program under
 * {@code __irij_program/}, off the classpath, with an index:
 *
 * <pre>
 * __irij_lib/index        main irij.Program
 *                         jar &lt;sha256&gt; &lt;sha256&gt;.jar   (one per library, classpath order)
 * </pre>
 *
 * <p>The launcher copies each library out to a cache directory
 * ({@code irij.lib.dir}, else {@code ~/.irij/lib}, else the temp dir)
 * named by its sha256, checks the hash of every file it will load, makes
 * them the {@link DepsClassLoader}, and defines the program in a loader
 * under it — the same arrangement {@code irij run} sets up, so a library
 * behaves the same in both. The jars stay whole (not merged into the app
 * JAR) so their signatures, service files and multi-release layouts are
 * untouched.
 *
 * <p>An app without {@code [jvm]} doesn't use this class: its JAR runs
 * the program class directly, as before.
 */
public final class JarLauncher {

    public static final String LIB = "__irij_lib/";
    public static final String PROGRAM = "__irij_program/";
    public static final String INDEX = LIB + "index";

    private JarLauncher() {}

    public static void main(String[] args) throws Throwable {
        ClassLoader app = JarLauncher.class.getClassLoader();
        String mainClass = null;
        List<String> shas = new ArrayList<>();
        try (InputStream in = app.getResourceAsStream(INDEX)) {
            if (in == null) throw new IllegalStateException("this JAR has no " + INDEX);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.trim().split(" ");
                if (f.length == 2 && f[0].equals("main")) mainClass = f[1];
                else if (f.length == 3 && f[0].equals("jar")) shas.add(f[1]);
            }
        }
        if (mainClass == null) throw new IllegalStateException(INDEX + " names no main class");

        Path dir = libDir();
        List<Path> jars = new ArrayList<>();
        for (String sha : shas) jars.add(extract(app, dir, sha));
        ClassLoader deps = DepsClassLoader.install(jars);

        ClassLoader program = new ProgramLoader(deps, app);
        Thread.currentThread().setContextClassLoader(deps);
        Class<?> main = Class.forName(mainClass, true, program);
        try {
            main.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Where library jars are copied to; created on first use. */
    static Path libDir() {
        String prop = System.getProperty("irij.lib.dir");
        if (prop != null) return Path.of(prop);
        Path home = Path.of(System.getProperty("user.home"), ".irij", "lib");
        try {
            Files.createDirectories(home);
            if (Files.isWritable(home)) return home;
        } catch (IOException | SecurityException ignored) {
            // read-only home (a sandboxed service) — use the temp dir
        }
        return Path.of(System.getProperty("java.io.tmpdir"),
                "irij-lib-" + System.getProperty("user.name", "user"));
    }

    /** The library {@code sha} as a file in {@code dir}: reused when one
     *  with that content is there, written (atomically) otherwise. */
    static Path extract(ClassLoader app, Path dir, String sha) throws IOException {
        if (!sha.matches("[0-9a-f]{64}")) throw new IOException("bad library hash in " + INDEX + ": " + sha);
        Files.createDirectories(dir);
        Path target = dir.resolve(sha + ".jar");
        if (Files.isRegularFile(target) && sha.equals(sha256(target))) return target;
        Path tmp = Files.createTempFile(dir, "." + sha, ".partial");
        try {
            try (InputStream in = app.getResourceAsStream(LIB + sha + ".jar")) {
                if (in == null) throw new IOException("this JAR lacks " + LIB + sha + ".jar");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!sha.equals(sha256(tmp))) throw new IOException(LIB + sha + ".jar is corrupt");
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        // Another launcher may have replaced it between the move and now;
        // whatever is there must still be this library.
        if (!sha.equals(sha256(target))) throw new IOException(target + " changed while starting");
        return target;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Defines the program's classes from {@code __irij_program/}. */
    private static final class ProgramLoader extends ClassLoader {
        private final ClassLoader jar;

        ProgramLoader(ClassLoader parent, ClassLoader jar) {
            super("irij-program", parent);
            this.jar = jar;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try (InputStream in = jar.getResourceAsStream(PROGRAM + name.replace('.', '/') + ".class")) {
                if (in == null) throw new ClassNotFoundException(name);
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
