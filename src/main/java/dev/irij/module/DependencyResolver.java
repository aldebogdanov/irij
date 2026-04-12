package dev.irij.module;

import dev.irij.module.ProjectFile.Dependency;
import dev.irij.module.ProjectFile.DepSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves and fetches dependencies declared in {@code irij.toml}.
 *
 * <p>Git dependencies are cloned/cached under {@code ~/.irij/deps/<name>/<ref>/}.
 * Local path dependencies resolve relative to the project root.
 *
 * <p>Resolution is recursive: if a resolved dependency has its own {@code irij.toml},
 * its transitive dependencies are resolved too (with cycle detection).
 *
 * <p>After resolving, each dependency's source directory is registered as a module
 * search path in the {@link ModuleRegistry}.
 */
public final class DependencyResolver {

    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"), ".irij", "deps");

    private final Path projectRoot;
    private final java.io.PrintStream out;

    public DependencyResolver(Path projectRoot, java.io.PrintStream out) {
        this.projectRoot = projectRoot;
        this.out = out;
    }

    /**
     * Resolve all dependencies (including transitive) and return their source paths.
     * Git deps are fetched if not already cached.
     *
     * @return map of dep name → resolved source directory path
     */
    public Map<String, Path> resolveAll(List<Dependency> deps) throws IOException {
        var resolved = new LinkedHashMap<String, Path>();
        var visiting = new HashSet<String>();
        resolveRecursive(deps, projectRoot, resolved, visiting);
        return resolved;
    }

    /**
     * Recursively resolve deps. {@code resolved} accumulates all results,
     * {@code visiting} tracks the current resolution stack for cycle detection.
     */
    private void resolveRecursive(List<Dependency> deps, Path contextRoot,
                                   Map<String, Path> resolved, Set<String> visiting) throws IOException {
        for (var dep : deps) {
            var name = dep.name();

            // Cycle detection — check before resolved (a dep in both sets = cycle)
            if (visiting.contains(name)) {
                throw new IOException("Circular dependency detected: " + name);
            }

            // Already resolved — skip (first declaration wins)
            if (resolved.containsKey(name)) continue;

            visiting.add(name);

            var path = resolve(dep, contextRoot);
            resolved.put(name, path);

            // Check for transitive deps in resolved dep's irij.toml
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

    /** Resolve a single dependency to a local path. */
    private Path resolve(Dependency dep, Path contextRoot) throws IOException {
        return switch (dep.source()) {
            case DepSource.GitDep git -> resolveGit(dep.name(), git, contextRoot);
            case DepSource.PathDep local -> resolveLocal(dep.name(), local, contextRoot);
        };
    }

    private Path resolveGit(String name, DepSource.GitDep git, Path contextRoot) throws IOException {
        // Check project-local .irij/deps/ first (supports pre-resolved deps in build envs)
        var localDepDir = contextRoot.resolve(".irij/deps").resolve(name).resolve(sanitizeRef(git.ref()));
        if (Files.isDirectory(localDepDir)) {
            return localDepDir;
        }

        var depDir = CACHE_DIR.resolve(name).resolve(sanitizeRef(git.ref()));

        if (Files.isDirectory(depDir)) {
            // Already cached
            return depDir;
        }

        // Clone to cache
        Files.createDirectories(depDir.getParent());
        out.println("Fetching " + name + " from " + git.url() + " @ " + git.ref() + " ...");

        try {
            // Clone with depth 1 for the specific ref
            var cloneResult = exec("git", "clone", "--depth", "1", "--branch", git.ref(),
                git.url(), depDir.toString());
            if (cloneResult != 0) {
                // Tag/branch clone failed — try full clone + checkout (for commit hashes)
                cleanup(depDir);
                var fullClone = exec("git", "clone", git.url(), depDir.toString());
                if (fullClone != 0) {
                    throw new IOException("Failed to clone " + git.url());
                }
                var checkout = exec("git", "-C", depDir.toString(), "checkout", git.ref());
                if (checkout != 0) {
                    cleanup(depDir);
                    throw new IOException("Failed to checkout ref '" + git.ref()
                        + "' in " + git.url());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git operation interrupted", e);
        }

        return depDir;
    }

    private Path resolveLocal(String name, DepSource.PathDep local, Path contextRoot) throws IOException {
        var resolved = contextRoot.resolve(local.path()).normalize();
        if (!Files.isDirectory(resolved)) {
            throw new IOException("Local dependency '" + name + "' path not found: " + resolved);
        }
        return resolved;
    }

    /** Execute a process and return its exit code. */
    private int exec(String... cmd) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.PIPE);
        var proc = pb.start();
        // Drain output
        try (var is = proc.getInputStream()) { is.readAllBytes(); }
        return proc.waitFor();
    }

    /** Clean up a partially-created directory. */
    private void cleanup(Path dir) {
        try {
            if (Files.exists(dir)) {
                // rm -rf equivalent
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
