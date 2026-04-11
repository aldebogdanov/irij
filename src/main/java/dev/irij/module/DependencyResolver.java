package dev.irij.module;

import dev.irij.module.DepsFile.Dependency;
import dev.irij.module.DepsFile.DepSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves and fetches dependencies declared in {@code deps.irj}.
 *
 * <p>Git dependencies are cloned/cached under {@code ~/.irij/deps/<name>/<ref>/}.
 * Local path dependencies resolve relative to the project root.
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
     * Resolve all dependencies and return their source paths.
     * Git deps are fetched if not already cached.
     *
     * @return map of dep name → resolved source directory path
     */
    public Map<String, Path> resolveAll(List<Dependency> deps) throws IOException {
        var resolved = new LinkedHashMap<String, Path>();
        for (var dep : deps) {
            var path = resolve(dep);
            resolved.put(dep.name(), path);
        }
        return resolved;
    }

    /** Resolve a single dependency to a local path. */
    private Path resolve(Dependency dep) throws IOException {
        return switch (dep.source()) {
            case DepSource.GitDep git -> resolveGit(dep.name(), git);
            case DepSource.PathDep local -> resolveLocal(dep.name(), local);
        };
    }

    private Path resolveGit(String name, DepSource.GitDep git) throws IOException {
        // Check project-local .irij/deps/ first (supports pre-resolved deps in build envs)
        var localDepDir = projectRoot.resolve(".irij/deps").resolve(name).resolve(sanitizeRef(git.ref()));
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

    private Path resolveLocal(String name, DepSource.PathDep local) throws IOException {
        var resolved = projectRoot.resolve(local.path()).normalize();
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
