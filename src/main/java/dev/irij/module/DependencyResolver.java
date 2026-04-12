package dev.irij.module;

import dev.irij.module.ProjectFile.Dependency;
import dev.irij.module.ProjectFile.DepSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves and fetches seeds (dependencies) declared in {@code irij.toml}.
 *
 * <p>Supports three source types:
 * <ul>
 *   <li><b>Registry</b> — downloaded from the Irij seed registry</li>
 *   <li><b>Git</b> — cloned/cached under {@code ~/.irij/seeds/<name>/<ref>/}</li>
 *   <li><b>Path</b> — local filesystem path (relative to project root)</li>
 * </ul>
 *
 * <p>Resolution is recursive: if a resolved seed has its own {@code irij.toml},
 * its transitive seeds are resolved too (with cycle detection).
 */
public final class DependencyResolver {

    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"), ".irij", "seeds");
    private static final String DEFAULT_REGISTRY = "https://irij.online";

    private final Path projectRoot;
    private final java.io.PrintStream out;
    private final String registryUrl;

    public DependencyResolver(Path projectRoot, java.io.PrintStream out) {
        this.projectRoot = projectRoot;
        this.out = out;
        this.registryUrl = System.getenv().getOrDefault("IRIJ_REGISTRY", DEFAULT_REGISTRY);
    }

    /**
     * Resolve all seeds (including transitive) and return their source paths.
     *
     * @return map of seed name → resolved source directory path
     */
    public Map<String, Path> resolveAll(List<Dependency> deps) throws IOException {
        var resolved = new LinkedHashMap<String, Path>();
        var visiting = new HashSet<String>();
        resolveRecursive(deps, projectRoot, resolved, visiting);
        return resolved;
    }

    /**
     * Recursively resolve seeds. {@code resolved} accumulates all results,
     * {@code visiting} tracks the current resolution stack for cycle detection.
     */
    private void resolveRecursive(List<Dependency> deps, Path contextRoot,
                                   Map<String, Path> resolved, Set<String> visiting) throws IOException {
        for (var dep : deps) {
            var name = dep.name();

            // Cycle detection — check before resolved (a seed in both sets = cycle)
            if (visiting.contains(name)) {
                throw new IOException("Circular dependency detected: " + name);
            }

            // Already resolved — skip (first declaration wins)
            if (resolved.containsKey(name)) continue;

            visiting.add(name);

            var path = resolve(dep, contextRoot);
            resolved.put(name, path);

            // Check for transitive seeds in resolved seed's irij.toml
            var depToml = path.resolve("irij.toml");
            if (Files.exists(depToml)) {
                try {
                    var transitiveDeps = ProjectFile.parseDeps(depToml);
                    if (!transitiveDeps.isEmpty()) {
                        resolveRecursive(transitiveDeps, path, resolved, visiting);
                    }
                } catch (ProjectFile.ParseError e) {
                    throw new IOException("Error in " + name + "'s irij.toml: " + e.getMessage());
                }
            }

            visiting.remove(name);
        }
    }

    /** Resolve a single seed to a local path. */
    private Path resolve(Dependency dep, Path contextRoot) throws IOException {
        return switch (dep.source()) {
            case DepSource.RegistryDep reg -> resolveRegistry(dep.name(), reg);
            case DepSource.GitDep git -> resolveGit(dep.name(), git, contextRoot);
            case DepSource.PathDep local -> resolveLocal(dep.name(), local, contextRoot);
        };
    }

    private Path resolveRegistry(String name, DepSource.RegistryDep reg) throws IOException {
        var seedDir = CACHE_DIR.resolve(name).resolve(reg.version());

        if (Files.isDirectory(seedDir)) {
            return seedDir;
        }

        // Download from registry
        Files.createDirectories(seedDir);
        out.println("Fetching " + name + " " + reg.version() + " from registry ...");

        var url = registryUrl + "/api/seeds/" + name + "/" + reg.version() + "/download";
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET().build();
            var response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                cleanup(seedDir);
                throw new IOException("Seed '" + name + "' version " + reg.version()
                    + " not found in registry (HTTP " + response.statusCode() + ")");
            }

            // Response is a tarball — extract to seedDir
            extractTarGz(response.body(), seedDir);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanup(seedDir);
            throw new IOException("Registry download interrupted", e);
        }

        return seedDir;
    }

    private Path resolveGit(String name, DepSource.GitDep git, Path contextRoot) throws IOException {
        // Check project-local .irij/seeds/ first (supports pre-resolved seeds in build envs)
        var localSeedDir = contextRoot.resolve(".irij/seeds").resolve(name).resolve(sanitizeRef(git.ref()));
        if (Files.isDirectory(localSeedDir)) {
            return localSeedDir;
        }

        var seedDir = CACHE_DIR.resolve(name).resolve(sanitizeRef(git.ref()));

        if (Files.isDirectory(seedDir)) {
            return seedDir;
        }

        // Clone to cache
        Files.createDirectories(seedDir.getParent());
        out.println("Fetching " + name + " from " + git.url() + " @ " + git.ref() + " ...");

        try {
            var cloneResult = exec("git", "clone", "--depth", "1", "--branch", git.ref(),
                git.url(), seedDir.toString());
            if (cloneResult != 0) {
                cleanup(seedDir);
                var fullClone = exec("git", "clone", git.url(), seedDir.toString());
                if (fullClone != 0) {
                    throw new IOException("Failed to clone " + git.url());
                }
                var checkout = exec("git", "-C", seedDir.toString(), "checkout", git.ref());
                if (checkout != 0) {
                    cleanup(seedDir);
                    throw new IOException("Failed to checkout ref '" + git.ref()
                        + "' in " + git.url());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git operation interrupted", e);
        }

        return seedDir;
    }

    private Path resolveLocal(String name, DepSource.PathDep local, Path contextRoot) throws IOException {
        var resolved = contextRoot.resolve(local.path()).normalize();
        if (!Files.isDirectory(resolved)) {
            throw new IOException("Local seed '" + name + "' path not found: " + resolved);
        }
        return resolved;
    }

    /** Extract a .tar.gz stream into a target directory. */
    private void extractTarGz(java.io.InputStream gzStream, Path targetDir) throws IOException {
        // Write to temp file, then extract with tar
        var tmpFile = Files.createTempFile("irij-seed-", ".tar.gz");
        try {
            Files.copy(gzStream, tmpFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                var result = exec("tar", "xzf", tmpFile.toString(), "-C", targetDir.toString());
                if (result != 0) {
                    throw new IOException("Failed to extract seed archive");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Tar extraction interrupted", e);
            }
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    /** Execute a process and return its exit code. */
    private int exec(String... cmd) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.PIPE);
        var proc = pb.start();
        try (var is = proc.getInputStream()) { is.readAllBytes(); }
        return proc.waitFor();
    }

    /** Clean up a partially-created directory. */
    private void cleanup(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
        } catch (IOException ignored) {}
    }

    /** Sanitize a git ref for use as a directory name. */
    private static String sanitizeRef(String ref) {
        return ref.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
