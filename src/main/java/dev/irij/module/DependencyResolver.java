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
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .build();

    private final Path projectRoot;
    private final java.io.PrintStream out;
    private final String registryUrl;

    public DependencyResolver(Path projectRoot, java.io.PrintStream out) {
        this.projectRoot = projectRoot;
        this.out = out;
        this.registryUrl = System.getenv().getOrDefault("IRIJ_REGISTRY", DEFAULT_REGISTRY);
    }

    /**
     * Resolve a project's seeds to their local source roots, for the
     * compile-time module inliner. Reads {@code <projectRoot>/irij.toml},
     * resolves every seed (transitively), and returns the roots in
     * declaration order.
     *
     * <p>Shared entry point for every "compile in a project context"
     * caller — file runs, the REPL, and the nREPL session — so they all
     * resolve {@code use <seed>} the same way. Returns an empty list when
     * {@code projectRoot} is null, has no manifest, or declares no seeds.
     *
     * @throws IOException if a declared seed cannot be resolved (offline,
     *         missing tag, bad path) — callers that prefer best-effort
     *         degradation should catch and fall back to an empty list.
     */
    public static List<Path> resolveSeedRoots(Path projectRoot, java.io.PrintStream out)
            throws IOException {
        if (projectRoot == null) return List.of();
        Path toml = projectRoot.resolve("irij.toml");
        if (!Files.exists(toml)) return List.of();
        var deps = ProjectFile.parseDeps(toml);
        if (deps.isEmpty()) return List.of();
        var resolver = new DependencyResolver(projectRoot, out);
        return new ArrayList<>(resolver.resolveAll(deps).values());
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
            var name = requireSafeSegment("seed name", dep.name());

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
        // Commit-count versioning: a 2-part MAJOR.MINOR pin resolves to the
        // highest published patch in that line (the patch is a commit count,
        // so you almost always want the latest). An exact 3-part pin is
        // honoured verbatim for reproducible builds.
        var version = requireSafeSegment("version of seed '" + name + "'", reg.version());
        if (ProjectVersion.isMajorMinor(version)) {
            // The registry's answer names a cache directory too.
            version = requireSafeSegment("registry version of seed '" + name + "'",
                resolveLatestPatch(name, version));
        }

        var seedDir = CACHE_DIR.resolve(name).resolve(version);

        if (Files.isDirectory(seedDir)) {
            return seedDir;
        }

        // Download from registry into a scratch directory; it becomes
        // seedDir only once fully extracted, so a failed or interrupted
        // fetch never leaves a half-filled seed that later runs trust.
        out.println("Fetching " + name + " " + version + " from registry ...");
        var staging = stagingDir(seedDir);

        var url = registryUrl + "/api/seeds/" + name + "/" + version + "/download";
        try {
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET().build();
            var response = HTTP.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Seed '" + name + "' version " + version
                    + " not found in registry (HTTP " + response.statusCode() + ")");
            }

            // Response is a tarball — extract to the staging dir
            extractTarGz(response.body(), staging);
            publish(staging, seedDir);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Registry download interrupted", e);
        } finally {
            cleanup(staging);
        }

        return seedDir;
    }

    /**
     * Resolve a 2-part {@code MAJOR.MINOR} pin to the highest published
     * {@code MAJOR.MINOR.PATCH} by querying the registry's seed detail
     * endpoint ({@code GET /api/seeds/<name>} → {@code {versions:[...]}}).
     */
    private String resolveLatestPatch(String name, String minorBase) throws IOException {
        var url = registryUrl + "/api/seeds/" + name;
        try {
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET().build();
            var response = HTTP.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Seed '" + name + "' not found in registry "
                    + "(HTTP " + response.statusCode() + ") while resolving "
                    + minorBase + ".*");
            }
            var versions = new ArrayList<String>();
            var root = com.google.gson.JsonParser.parseString(response.body());
            if (root.isJsonObject()) {
                var arr = root.getAsJsonObject().getAsJsonArray("versions");
                if (arr != null) {
                    for (var el : arr) {
                        var v = el.getAsJsonObject().get("version");
                        if (v != null && !v.isJsonNull()) versions.add(v.getAsString());
                    }
                }
            }
            var picked = ProjectVersion.latestPatch(versions, minorBase);
            if (picked.isEmpty()) {
                throw new IOException("No published version of '" + name + "' in the "
                    + minorBase + ".* line (available: "
                    + (versions.isEmpty() ? "none" : String.join(", ", versions)) + ")");
            }
            return picked.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Registry query interrupted", e);
        }
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

        // Both strings reach git's command line. A URL or ref that starts
        // with `-` would be read as an option (`--upload-pack=<cmd>` runs
        // <cmd>), and `<transport>::<address>` URLs hand the address to a
        // helper program (`ext::` runs it as a shell command).
        var url = git.url();
        var ref = git.ref();
        if (url == null || url.isBlank() || url.startsWith("-") || url.matches("^[A-Za-z][A-Za-z0-9+.-]*::.*")) {
            throw new IOException("Seed '" + name + "' has an unsupported git URL: " + url);
        }
        if (ref == null || ref.isBlank() || ref.startsWith("-")) {
            throw new IOException("Seed '" + name + "' has an invalid git ref: " + ref);
        }

        // Clone into a scratch directory; it becomes the cached seed only
        // once checked out, so an interrupted clone is never reused.
        out.println("Fetching " + name + " from " + url + " @ " + ref + " ...");
        var staging = stagingDir(seedDir);

        try {
            var cloneResult = exec("git", "-c", "protocol.ext.allow=never",
                "clone", "--depth", "1", "--branch", ref, "--", url, staging.toString());
            if (cloneResult != 0) {
                cleanup(staging);
                var fullClone = exec("git", "-c", "protocol.ext.allow=never",
                    "clone", "--", url, staging.toString());
                if (fullClone != 0) {
                    throw new IOException("Failed to clone " + url);
                }
                var checkout = exec("git", "-C", staging.toString(), "checkout", "--detach", ref);
                if (checkout != 0) {
                    throw new IOException("Failed to checkout ref '" + ref
                        + "' in " + url);
                }
            }
            publish(staging, seedDir);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git operation interrupted", e);
        } finally {
            cleanup(staging);
        }

        return seedDir;
    }

    /** A fresh, empty sibling of {@code target} to build it in. */
    private static Path stagingDir(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        return Files.createTempDirectory(target.getParent(), "." + target.getFileName() + ".partial-");
    }

    /** Move a fully built {@code staging} dir into place as {@code target}.
     *  If another process got there first, its copy wins and ours is
     *  discarded by the caller. */
    private static void publish(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileAlreadyExistsException
                 | java.nio.file.DirectoryNotEmptyException e) {
            if (!Files.isDirectory(target)) throw e;
        }
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

    /** Seed names and versions become directory names under the seed
     *  cache (and come from any transitive seed's irij.toml, or from the
     *  registry), so each must be one plain path segment. */
    private static final java.util.regex.Pattern SAFE_SEGMENT =
        java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

    static String requireSafeSegment(String what, String s) throws IOException {
        if (s == null || !SAFE_SEGMENT.matcher(s).matches() || s.contains("..")) {
            throw new IOException("Invalid " + what + ": '" + s + "'");
        }
        return s;
    }

    /** Sanitize a git ref for use as a directory name. */
    private static String sanitizeRef(String ref) {
        var safe = ref.replaceAll("[^a-zA-Z0-9._-]", "_");
        // "." and ".." survive the character filter but name the parent.
        return safe.chars().allMatch(c -> c == '.') ? "_" + safe : safe;
    }
}
