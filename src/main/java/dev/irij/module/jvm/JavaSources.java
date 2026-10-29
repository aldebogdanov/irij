package dev.irij.module.jvm;

import dev.irij.module.ProjectFile;
import dev.irij.module.ProjectFile.Trust;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Java sources a project or seed keeps in {@code java/} beside its
 * {@code irij.toml}: compiled into a jar that joins the program's Java
 * libraries, so a {@code cap} binds one of its classes like any library
 * class (spec §3.3, packaging.md § Java sources).
 *
 * <p>Sources compile against the program's {@code [jvm]} libraries and the
 * JDK — never Irij's own classes, so a provider trades plain Java values
 * with the Irij side. The root project's sources also see its seeds'. Each
 * unit (the root, or one seed) becomes one jar in a cache
 * ({@code ~/.irij/java}, {@code irij.java.dir} overrides) named by a hash
 * of its sources, its classpath and the release, so an unchanged project
 * compiles nothing. A seed's classes must live in its own package
 * ({@link #packageOf}): the program has one classpath. With a root
 * {@code [trust]}, a seed its {@code java} key doesn't name is refused
 * before anything is compiled.
 */
public final class JavaSources {

    private JavaSources() {}

    public static final String DIR = "java";

    /** Compiled classes load wherever Irij runs: its own Java level. */
    public static final int RELEASE = 25;

    /** One project's or seed's sources; {@code seed} is null for the root. */
    public record Unit(String seed, Path root, List<Path> files) {
        public String who() { return seed == null ? "irij.toml" : seed; }
    }

    /** The units to compile, and the seeds [trust] java doesn't name. */
    public record Plan(List<Unit> units, List<String> untrusted, boolean trustEnforced) {
        public static final Plan EMPTY = new Plan(List.of(), List.of(), false);

        public boolean isEmpty() { return units.isEmpty(); }

        public List<String> seeds() {
            return units.stream().filter(u -> u.seed() != null).map(Unit::seed).toList();
        }

        /** Throw unless every seed with sources is trusted. */
        public void requireTrusted() throws JvmDeps.DepsError {
            if (untrusted.isEmpty()) return;
            throw new JvmDeps.DepsError("irij.toml [trust] doesn't let these seeds compile Java sources: "
                    + String.join(", ", untrusted) + "\nNothing was compiled. To allow them, add to [trust]:\n  java = ["
                    + String.join(", ", untrusted.stream().map(s -> "\"" + s + "\"").toList()) + "]");
        }
    }

    /** A source that doesn't compile, or a seed class outside its package. */
    public static final class CompileError extends IOException {
        public CompileError(String message) { super(message); }
    }

    // ── Planning ────────────────────────────────────────────────────────

    /** The root's and every seed's {@code java/} sources, seeds in name order. */
    public static Plan plan(ProjectFile.ParseResult root, Path projectRoot, Map<String, Path> seeds)
            throws IOException {
        var units = new ArrayList<Unit>();
        var untrusted = new ArrayList<String>();
        Trust trust = root.trust();
        for (String name : seeds.keySet().stream().sorted().toList()) {
            List<Path> files = sources(seeds.get(name));
            if (files.isEmpty()) continue;
            units.add(new Unit(name, seeds.get(name), files));
            if (trust != null && !trust.allowsJava(name)) untrusted.add(name);
        }
        if (projectRoot != null) {
            List<Path> files = sources(projectRoot);
            if (!files.isEmpty()) units.add(new Unit(null, projectRoot, files));
        }
        return new Plan(List.copyOf(units), List.copyOf(untrusted), trust != null);
    }

    /** Every {@code .java} file under {@code root/java}, in path order. */
    public static List<Path> sources(Path root) throws IOException {
        Path dir = root.resolve(DIR);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString().replace('\\', '/')))
                    .toList();
        }
    }

    /** The package a seed's classes live in: its name, with {@code -} as
     *  {@code _} and a segment starting with a digit prefixed by {@code _}. */
    public static String packageOf(String seed) {
        var segments = new ArrayList<String>();
        for (String seg : seed.split("\\.")) {
            String s = seg.replace('-', '_');
            if (!s.isEmpty() && Character.isDigit(s.charAt(0))) s = "_" + s;
            segments.add(s);
        }
        return String.join(".", segments);
    }

    // ── Compiling ───────────────────────────────────────────────────────

    /**
     * One jar per unit, compiling only what the cache doesn't hold. Seeds
     * compile against {@code libraries}; the root against those and the
     * seeds' jars.
     */
    public static List<Path> compile(Plan plan, List<Path> libraries, PrintStream out) throws IOException {
        plan.requireTrusted();
        var jars = new ArrayList<Path>();
        var seedJars = new ArrayList<Path>();
        for (Unit u : plan.units()) {
            var classpath = new ArrayList<>(libraries);
            if (u.seed() == null) classpath.addAll(seedJars);
            Path jar = compileUnit(u, classpath, out);
            jars.add(jar);
            if (u.seed() != null) seedJars.add(jar);
        }
        return jars;
    }

    static Path cacheDir() {
        String override = System.getProperty("irij.java.dir");
        return override != null ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".irij", "java");
    }

    private static Path compileUnit(Unit u, List<Path> classpath, PrintStream out) throws IOException {
        Path cache = cacheDir();
        Path jar = cache.resolve(key(u, classpath) + ".jar");
        if (Files.isRegularFile(jar)) return jar;

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new CompileError("Compiling " + where(u) + " needs a JDK, and this Java ("
                    + System.getProperty("java.home") + ") has no compiler. Run Irij on a JDK.");
        }
        out.println("Compiling " + where(u) + " (" + u.files().size() + " file" + (u.files().size() == 1 ? "" : "s") + ")");
        Files.createDirectories(cache);
        Path work = Files.createTempDirectory(cache, "compile-");
        try {
            Path classes = Files.createDirectories(work.resolve("classes"));
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var options = new ArrayList<>(List.of("--release", String.valueOf(RELEASE),
                    "-encoding", "UTF-8", "-proc:none", "-implicit:class", "-d", classes.toString(),
                    // Always explicit: an unset classpath would fall back to
                    // Irij's own, and sources must not compile against it.
                    "-classpath", classpath.isEmpty() ? classes.toString()
                            : String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList())));
            try (var fm = javac.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
                var task = javac.getTask(null, fm, diagnostics, options, null,
                        fm.getJavaFileObjectsFromPaths(u.files()));
                if (!task.call()) throw new CompileError(report(u, diagnostics));
            }
            if (u.seed() != null) checkPackage(u, classes);
            Path tmpJar = work.resolve("out.jar");
            writeJar(classes, tmpJar);
            try {
                Files.move(tmpJar, jar, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | AtomicMoveNotSupportedException e) {
                if (!Files.isRegularFile(jar)) Files.move(tmpJar, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            return jar;
        } finally {
            deleteTree(work);
        }
    }

    private static String where(Unit u) {
        return u.seed() == null ? DIR + "/" : u.seed() + "'s " + DIR + "/";
    }

    /** Errors as {@code [seed: ]java/a/B.java:12: message}, one per line. */
    private static String report(Unit u, DiagnosticCollector<JavaFileObject> diagnostics) {
        var sb = new StringBuilder("Java sources in " + where(u) + " don't compile:");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            sb.append("\n  ");
            if (u.seed() != null) sb.append(u.seed()).append(": ");
            if (d.getSource() != null) {
                Path file = Path.of(d.getSource().toUri());
                sb.append(u.root().relativize(file).toString().replace('\\', '/'))
                  .append(':').append(d.getLineNumber()).append(": ");
            }
            sb.append(d.getMessage(Locale.ROOT).lines().findFirst().orElse(""));
        }
        return sb.toString();
    }

    private static void checkPackage(Unit u, Path classes) throws IOException {
        String pkg = packageOf(u.seed());
        String prefix = pkg.replace('.', '/') + "/";
        var outside = new ArrayList<String>();
        try (Stream<Path> s = Files.walk(classes)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = classes.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith(prefix)) outside.add(rel);
            }
        }
        if (!outside.isEmpty()) {
            throw new CompileError("Seed '" + u.seed() + "' has Java classes outside package " + pkg
                    + " (the program has one classpath, so each seed keeps to its own):\n  "
                    + String.join("\n  ", outside.stream().sorted().toList()));
        }
    }

    /** Entries in path order with a fixed time, so a jar's bytes depend
     *  only on the classes. */
    private static void writeJar(Path classes, Path jar) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(classes)) {
            files = s.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> classes.relativize(p).toString().replace('\\', '/')))
                    .toList();
        }
        try (var jos = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path f : files) {
                var e = new JarEntry(classes.relativize(f).toString().replace('\\', '/'));
                e.setTime(0);
                jos.putNextEntry(e);
                Files.copy(f, jos);
                jos.closeEntry();
            }
        }
    }

    /** What a unit's jar depends on: its sources (paths and contents), its
     *  classpath (each jar's path and size — library jars are versioned
     *  paths, compiled ones content-named) and the release. */
    static String key(Unit u, List<Path> classpath) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path dir = u.root().resolve(DIR);
        line(md, "irij-java/1 release " + RELEASE + " seed " + u.who());
        for (Path f : u.files()) {
            line(md, "src " + dir.relativize(f).toString().replace('\\', '/'));
            md.update(Files.readAllBytes(f));
            line(md, "");
        }
        for (Path j : classpath) line(md, "cp " + j.toAbsolutePath() + " " + Files.size(j));
        return HexFormat.of().formatHex(md.digest());
    }

    private static void line(MessageDigest md, String s) {
        md.update((s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // A leftover temp dir in the cache is harmless.
        }
    }
}
