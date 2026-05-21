package dev.irij.cli;

import dev.irij.compiler.CompileOptions;
import dev.irij.compiler.IrijCompiler;
import dev.irij.module.ProjectFile;
import dev.irij.module.DependencyResolver;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Stream;

/**
 * {@code irij build} — package an Irij application into a self-contained JAR.
 *
 * <p>The output JAR bundles:
 * <ul>
 *   <li>The irij runtime (this JAR's own classes + dependencies)</li>
 *   <li>Application .irj source files (under {@code __irij_app/})</li>
 *   <li>Resolved dependencies (under {@code __irij_deps/<name>/})</li>
 *   <li>Static resources from {@code resources/} (under {@code __irij_resources/})</li>
 * </ul>
 *
 * <p>The bundled JAR runs with: {@code java -jar app.jar}
 */
public final class BuildCommand {

    private static final String APP_PREFIX = "__irij_app/";
    private static final String DEPS_PREFIX = "__irij_deps/";
    private static final String RESOURCES_PREFIX = "__irij_resources/";
    private static final String MANIFEST_ENTRY = "Irij-Entry-Point";
    private static final String MANIFEST_NREPL_PORT = "Irij-NRepl-Port";
    private static final String BYTECODE_CLASS_NAME = "irij.Program";

    /**
     * Run the build command.
     *
     * @param args remaining CLI args after "build" (optional: entry file, --output)
     */
    public static void run(String[] args) throws IOException {
        Path projectRoot = Path.of("").toAbsolutePath();
        rejectLegacyModeFlag(args);
        boolean directLinking = parseDirectLinking(args);
        int nreplPort = parseNReplPort(args);
        String entryPoint = findEntryPoint(projectRoot, args);
        Path outputJar = resolveOutputPath(args, entryPoint);

        System.out.println("Building Irij application...");
        if (directLinking) System.out.println("  link:   direct (no hot-redef)");
        if (nreplPort > 0) System.out.println("  nrepl:  embedded on port " + nreplPort);
        System.out.println("  entry:  " + entryPoint);
        System.out.println("  output: " + outputJar);

        Map<String, Path> bcDeps = resolveDeps(projectRoot);
        buildBytecodeJar(projectRoot, entryPoint, outputJar, directLinking, bcDeps);
        long sizeKbBc = Files.size(outputJar) / 1024;
        System.out.println();
        System.out.println("Built: " + outputJar + " (" + sizeKbBc + " KB)");
        System.out.println("Run:   java --enable-native-access=ALL-UNNAMED -jar "
                + outputJar.getFileName());
    }

    private static String findEntryPoint(Path projectRoot, String[] args) {
        // Check CLI args for explicit entry point
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-") && arg.endsWith(".irj")) {
                return arg;
            }
        }

        // Auto-detect: look for server.irj, main.irj, app.irj
        for (String candidate : List.of("server.irj", "main.irj", "app.irj")) {
            if (Files.exists(projectRoot.resolve(candidate))) {
                return candidate;
            }
        }

        System.err.println("Error: no entry point found. Specify one: irij build <file.irj>");
        System.err.println("Or create server.irj, main.irj, or app.irj in the project root.");
        System.exit(1);
        return null;
    }

    private static Path resolveOutputPath(String[] args, String entryPoint) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--output") || args[i].equals("-o")) {
                return Path.of(args[i + 1]);
            }
        }
        // Default: build/<basename>.jar
        String baseName = entryPoint.replace(".irj", "");
        Path buildDir = Path.of("build");
        try { Files.createDirectories(buildDir); } catch (IOException ignored) {}
        return buildDir.resolve(baseName + ".jar");
    }

    private static Path findRuntimeJar() {
        // Find the JAR that contains this class
        try {
            var location = BuildCommand.class.getProtectionDomain()
                .getCodeSource().getLocation();
            if (location != null) {
                Path jarPath = Path.of(location.toURI());
                if (Files.isRegularFile(jarPath) && jarPath.toString().endsWith(".jar")) {
                    return jarPath;
                }
            }
        } catch (Exception ignored) {}

        // Fallback: check ~/.local/lib/irij.jar
        Path homeJar = Path.of(System.getProperty("user.home"), ".local", "lib", "irij.jar");
        if (Files.isRegularFile(homeJar)) return homeJar;

        return null;
    }

    private static Map<String, Path> resolveDeps(Path projectRoot) throws IOException {
        Path tomlFile = projectRoot.resolve("irij.toml");
        if (!Files.exists(tomlFile)) return Map.of();

        try {
            var deps = ProjectFile.parseDeps(tomlFile);
            if (deps.isEmpty()) return Map.of();

            System.out.println("  seeds:  " + deps.size());
            var resolver = new DependencyResolver(projectRoot, System.out);
            return resolver.resolveAll(deps);
        } catch (ProjectFile.ParseError e) {
            System.err.println("Error in irij.toml: " + e.getMessage());
            System.exit(1);
            return Map.of();
        }
    }

    private static List<Path> collectIrjFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir, 1)) {
            return walk
                .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".irj"))
                .toList();
        }
    }

    private static List<Path> collectAllFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static void buildJar(Path runtimeJar, Path outputJar,
                                  Path projectRoot, String entryPoint,
                                  List<Path> appFiles,
                                  Map<String, Path> deps,
                                  Path resourcesDir,
                                  List<Path> resourceFiles,
                                  int nreplPort) throws IOException {

        // Read the runtime JAR's manifest and modify it
        Manifest manifest;
        try (JarFile runtime = new JarFile(runtimeJar.toFile())) {
            manifest = new Manifest(runtime.getManifest());
        }
        manifest.getMainAttributes().putValue(MANIFEST_ENTRY, entryPoint);
        if (nreplPort > 0) {
            manifest.getMainAttributes().putValue(MANIFEST_NREPL_PORT,
                    Integer.toString(nreplPort));
        }

        // Write the output JAR
        try (JarOutputStream jos = new JarOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outputJar)), manifest)) {

            Set<String> written = new HashSet<>();
            written.add("META-INF/MANIFEST.MF"); // already written by JarOutputStream

            // 1. Copy all entries from the runtime JAR
            try (JarFile runtime = new JarFile(runtimeJar.toFile())) {
                var entries = runtime.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (written.contains(entry.getName())) continue;
                    if (entry.getName().startsWith("META-INF/MANIFEST")) continue;
                    written.add(entry.getName());

                    jos.putNextEntry(new JarEntry(entry.getName()));
                    try (InputStream is = runtime.getInputStream(entry)) {
                        is.transferTo(jos);
                    }
                    jos.closeEntry();
                }
            }

            // 2. Add app .irj files under __irij_app/
            for (Path appFile : appFiles) {
                String name = APP_PREFIX + projectRoot.relativize(appFile).toString();
                if (written.add(name)) {
                    jos.putNextEntry(new JarEntry(name));
                    Files.copy(appFile, jos);
                    jos.closeEntry();
                }
            }

            // 3. Add deps under __irij_deps/<depname>/
            for (var depEntry : deps.entrySet()) {
                String depName = depEntry.getKey();
                Path depDir = depEntry.getValue();
                List<Path> depFiles = collectIrjFilesRecursive(depDir);
                for (Path depFile : depFiles) {
                    String relative = depDir.relativize(depFile).toString();
                    String name = DEPS_PREFIX + depName + "/" + relative;
                    if (written.add(name)) {
                        jos.putNextEntry(new JarEntry(name));
                        Files.copy(depFile, jos);
                        jos.closeEntry();
                    }
                }
            }

            // 4. Add resources under __irij_resources/
            for (Path resFile : resourceFiles) {
                String relative = resourcesDir.relativize(resFile).toString();
                String name = RESOURCES_PREFIX + relative;
                if (written.add(name)) {
                    jos.putNextEntry(new JarEntry(name));
                    Files.copy(resFile, jos);
                    jos.closeEntry();
                }
            }
        }
    }

    // ── --nrepl-port parsing ────────────────────────────────────────────

    /** Parse `--nrepl-port=N` or `--nrepl-port N`. Returns 0 if absent. */
    private static int parseNReplPort(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String value = null;
            if (a.startsWith("--nrepl-port=")) value = a.substring("--nrepl-port=".length());
            else if (a.equals("--nrepl-port") && i + 1 < args.length) value = args[i + 1];
            if (value != null) {
                try { return Integer.parseInt(value); }
                catch (NumberFormatException e) {
                    System.err.println("Invalid --nrepl-port: " + value);
                    System.exit(1);
                }
            }
        }
        return 0;
    }

    // ── --direct-linking parsing ────────────────────────────────────────

    private static boolean parseDirectLinking(String[] args) {
        for (String a : args) {
            if (a.equals("--direct-linking") || a.equals("--direct-linking=true")) return true;
            if (a.equals("--direct-linking=false")) return false;
        }
        return false; // default: hot-redef enabled
    }

    // ── Legacy --mode flag handling ─────────────────────────────────────

    /** v0.6.13: only one mode left (bytecode-sm). The `--mode=` flag is
     *  still accepted for one release as a no-op so old build scripts
     *  don't fail silently; passing it prints a soft warning. */
    private static void rejectLegacyModeFlag(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--mode=") || a.equals("--mode")) {
                System.err.println("[warn] --mode is no longer needed: "
                        + "bytecode-sm is the only mode. Flag ignored.");
                return;
            }
        }
    }

    // ── Bytecode-build path ─────────────────────────────────────────────

    private static void buildBytecodeJar(Path projectRoot, String entryPoint,
                                          Path outputJar,
                                          boolean directLinking,
                                          Map<String, Path> deps) throws IOException {
        CompileOptions opts = CompileOptions.defaults()
                .withDirectLinking(directLinking);

        Path entryFile = projectRoot.resolve(entryPoint);
        // Hand resolved seed dirs to the compiler so `use mod.X` in the
        // entry source can be inlined from `~/.irij/seeds/<name>/<ver>/`
        // or any other resolved location DependencyResolver returned.
        List<Path> seedRoots = new java.util.ArrayList<>(deps.values());
        byte[] classBytes;
        try {
            classBytes = IrijCompiler.compileFile(entryFile, BYTECODE_CLASS_NAME, opts, seedRoots);
        } catch (IrijCompiler.CompileException e) {
            System.err.println("Compile error: " + e.getMessage());
            System.exit(1);
            return;
        }

        Path resourcesDir = projectRoot.resolve("resources");
        List<Path> resourceFiles = Files.isDirectory(resourcesDir)
                ? collectAllFiles(resourcesDir) : List.of();

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, BYTECODE_CLASS_NAME);

        try (JarOutputStream jos = new JarOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outputJar)), mf)) {
            Set<String> written = new HashSet<>();
            written.add("META-INF/MANIFEST.MF");

            // 1. User program class
            String classEntry = BYTECODE_CLASS_NAME.replace('.', '/') + ".class";
            jos.putNextEntry(new JarEntry(classEntry));
            jos.write(classBytes);
            jos.closeEntry();
            written.add(classEntry);

            // 2. Bundle the runtime (everything under dev/irij/** except cli/repl/nrepl/mcp)
            //    and the packaged stdlib (std/*.irj).
            bundleRuntimeClasses(jos, written);

            // 3. Resources under __irij_resources/ (mirrors interp mode layout).
            for (Path resFile : resourceFiles) {
                String relative = resourcesDir.relativize(resFile).toString();
                String name = RESOURCES_PREFIX + relative;
                if (written.add(name)) {
                    jos.putNextEntry(new JarEntry(name));
                    Files.copy(resFile, jos);
                    jos.closeEntry();
                }
            }
        }
    }

    /** Copy all dev/irij/** classes (except cli/repl/nrepl/mcp) and std/*.irj
     *  from our own jar into {@code jos} so bytecode output is self-contained. */
    private static void bundleRuntimeClasses(JarOutputStream jos, Set<String> written)
            throws IOException {
        Path selfJar = findRuntimeJar();
        if (selfJar == null) {
            System.err.println("Error: cannot locate irij runtime JAR for bundling.");
            System.exit(1);
            return;
        }
        try (JarFile jf = new JarFile(selfJar.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                boolean keep =
                        (name.endsWith(".class") && name.startsWith("dev/irij/")
                                && !name.startsWith("dev/irij/cli/")
                                && !name.startsWith("dev/irij/repl/")
                                && !name.startsWith("dev/irij/nrepl/")
                                && !name.startsWith("dev/irij/mcp/"))
                        || name.startsWith("std/")
                        // Classes pulled in as dependencies of RuntimeSupport (ASM, gson, …)
                        // are already in dev/irij/** space? No — they live under their own
                        // packages. Copy them too so emitted classes resolve at runtime.
                        || (name.endsWith(".class") && isRuntimeDep(name))
                        // SQLite ships native libraries under org/sqlite/native/
                        // (.so/.dylib/.dll for each platform); bundle them
                        // so the JDBC driver can self-load on start.
                        || name.startsWith("org/sqlite/native/")
                        // Service loader files — java.sql.Driver discovers
                        // org.sqlite.JDBC via META-INF/services.
                        || name.startsWith("META-INF/services/");
                if (!keep) continue;
                if (!written.add(name)) continue;
                jos.putNextEntry(new JarEntry(name));
                try (var is = jf.getInputStream(e)) { is.transferTo(jos); }
                jos.closeEntry();
            }
        }
    }

    /** Packages the bytecode runtime transitively depends on. */
    private static boolean isRuntimeDep(String name) {
        return name.startsWith("com/google/gson/")
                || name.startsWith("com/moandjiezana/toml/")
                || name.startsWith("org/antlr/v4/runtime/")
                || name.startsWith("org/objectweb/asm/")
                // SQLite JDBC driver — needed by RuntimeSupport.rawDbOpen
                // and any program using std.db.
                || name.startsWith("org/sqlite/")
                // JLine — needed by the REPL surface that builtins
                // can transitively reach (e.g. when stdlib functions
                // print prompts). Cheap to bundle.
                || name.startsWith("org/jline/");
    }

    private static List<Path> collectIrjFilesRecursive(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".irj")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Skip .git directories
                if (dir.getFileName().toString().equals(".git")) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
