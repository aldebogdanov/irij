package dev.irij.cli;

import dev.irij.module.DepsFile;
import dev.irij.module.DependencyResolver;

import java.io.*;
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

    /**
     * Run the build command.
     *
     * @param args remaining CLI args after "build" (optional: entry file, --output)
     */
    public static void run(String[] args) throws IOException {
        Path projectRoot = Path.of("").toAbsolutePath();
        String entryPoint = findEntryPoint(projectRoot, args);
        Path outputJar = resolveOutputPath(args, entryPoint);

        System.out.println("Building Irij application...");
        System.out.println("  entry:  " + entryPoint);
        System.out.println("  output: " + outputJar);

        // Find the running irij JAR (our own JAR)
        Path runtimeJar = findRuntimeJar();
        if (runtimeJar == null) {
            System.err.println("Error: cannot locate irij runtime JAR.");
            System.err.println("Build requires running from an installed irij JAR (not IDE/classpath).");
            System.exit(1);
            return;
        }

        // Resolve dependencies
        Map<String, Path> deps = resolveDeps(projectRoot);

        // Collect app source files
        List<Path> appFiles = collectIrjFiles(projectRoot);

        // Collect resources
        Path resourcesDir = projectRoot.resolve("resources");
        List<Path> resourceFiles = Files.isDirectory(resourcesDir)
            ? collectAllFiles(resourcesDir) : List.of();

        // Build the JAR
        buildJar(runtimeJar, outputJar, projectRoot, entryPoint, appFiles, deps, resourcesDir, resourceFiles);

        long sizeKb = Files.size(outputJar) / 1024;
        System.out.println();
        System.out.println("Built: " + outputJar + " (" + sizeKb + " KB)");
        System.out.println("Run:   java --enable-native-access=ALL-UNNAMED -jar " + outputJar.getFileName());
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
        Path depsFile = projectRoot.resolve("deps.irj");
        if (!Files.exists(depsFile)) return Map.of();

        try {
            var deps = DepsFile.parse(depsFile);
            if (deps.isEmpty()) return Map.of();

            System.out.println("  deps:   " + deps.size());
            var resolver = new DependencyResolver(projectRoot, System.out);
            return resolver.resolveAll(deps);
        } catch (DepsFile.DepsParseError e) {
            System.err.println("Error in deps.irj: " + e.getMessage());
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
                                  List<Path> resourceFiles) throws IOException {

        // Read the runtime JAR's manifest and modify it
        Manifest manifest;
        try (JarFile runtime = new JarFile(runtimeJar.toFile())) {
            manifest = new Manifest(runtime.getManifest());
        }
        manifest.getMainAttributes().putValue(MANIFEST_ENTRY, entryPoint);

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
