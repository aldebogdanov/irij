package dev.irij.module.jvm;

import dev.irij.module.ProjectFile;
import dev.irij.module.ProjectFile.JavaDep;
import dev.irij.module.ProjectFile.Trust;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.version.GenericVersionScheme;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java libraries for a project: what its {@code irij.toml} {@code [jvm]}
 * and its seeds' ask for ({@link #plan}), and the jars that resolves to
 * ({@link #fetch}), pinned in {@code irij.lock}.
 *
 * <p>Resolution is Maven's (Maven Resolver, transitive, nearest-wins),
 * from Maven Central unless the root project lists {@code repos}. Its
 * result — every jar with its sha256 — is written to {@code irij.lock}
 * beside {@code irij.toml}. While the lock's {@code inputs} hash matches
 * the requests, later runs fetch exactly the locked jars and refuse any
 * whose sha256 differs; a changed {@code [jvm]} (here or in a seed)
 * resolves again and rewrites the lock.
 *
 * <p>Only the CLI uses this class; {@code irij build} does not bundle
 * {@code dev.irij.module.jvm} (or Maven Resolver) into apps.
 */
public final class JvmDeps {

    private JvmDeps() {}

    public static final String CENTRAL = "https://repo.maven.apache.org/maven2";
    public static final String LOCK_FILE = "irij.lock";
    private static final int LOCK_VERSION = 1;

    /** One {@code [jvm]} entry and who declared it: a seed's name, or
     *  null for the root project. */
    public record Request(String seed, JavaDep dep) {
        public String who() { return seed == null ? "irij.toml" : seed; }
    }

    /**
     * What to resolve: every request (for {@code irij deps} / {@code irij
     * audit}), the merged roots — one version per {@code group:artifact}
     * — the repositories to fetch from, notes on versions overridden, and
     * the seeds that bring libraries without {@code [trust] jars} letting
     * them ({@link #fetch} refuses those before downloading anything).
     */
    public record Plan(List<Request> requests, List<JavaDep> roots, List<String> repos,
                       List<String> notes, List<String> untrusted, boolean trustEnforced) {
        public boolean isEmpty() { return roots.isEmpty(); }

        /** Throw unless every seed with libraries is trusted. */
        public void requireTrusted() throws DepsError {
            if (untrusted.isEmpty()) return;
            var msg = new StringBuilder("irij.toml [trust] doesn't let these seeds bring Java libraries:\n");
            for (String s : untrusted) {
                msg.append("  ").append(s).append(": ");
                msg.append(String.join(", ", requests.stream()
                        .filter(r -> s.equals(r.seed())).map(r -> r.dep().coord()).toList()));
                msg.append('\n');
            }
            msg.append("Nothing was downloaded. To allow them, add to [trust]:\n  jars = [")
               .append(String.join(", ", untrusted.stream().map(s -> "\"" + s + "\"").toList()))
               .append("]");
            throw new DepsError(msg.toString());
        }
    }

    /** A request that can't be honoured: an untrusted seed, a seed that
     *  sets repos, or two majors of one library. */
    public static final class DepsError extends IOException {
        public DepsError(String message) { super(message); }
    }

    // ── Planning ────────────────────────────────────────────────────────

    /**
     * Collect the {@code [jvm]} requests of the root manifest and of every
     * resolved seed (name → source dir), note the seeds the root's
     * {@code [trust] jars} doesn't name, and merge them: the root's version of a
     * library wins; otherwise the highest any seed asks for, unless two
     * seeds need different major versions — then the root must pin one.
     */
    public static Plan plan(ProjectFile.ParseResult root, Map<String, Path> seeds) throws IOException {
        var requests = new ArrayList<Request>();
        for (JavaDep d : root.jvm().deps()) requests.add(new Request(null, d));
        var untrusted = new ArrayList<String>();
        Trust trust = root.trust();
        for (var e : seeds.entrySet()) {
            ProjectFile.Jvm jvm;
            try {
                jvm = ProjectFile.parseFile(e.getValue().resolve("irij.toml")).jvm();
            } catch (ProjectFile.ParseError err) {
                throw new DepsError("Error in " + e.getKey() + "'s irij.toml: " + err.getMessage());
            }
            if (!jvm.repos().isEmpty()) {
                throw new DepsError("Seed '" + e.getKey() + "' sets [jvm] repos. A seed can't choose where "
                        + "your project downloads code from; its libraries come from the root project's "
                        + "repositories (Maven Central unless irij.toml [jvm] lists repos).");
            }
            if (jvm.deps().isEmpty()) continue;
            if (trust != null && !trust.allowsJars(e.getKey())) untrusted.add(e.getKey());
            for (JavaDep d : jvm.deps()) requests.add(new Request(e.getKey(), d));
        }
        var notes = new ArrayList<String>();
        var byKey = new TreeMap<String, List<Request>>();
        for (Request r : requests) byKey.computeIfAbsent(r.dep().key(), k -> new ArrayList<>()).add(r);
        var roots = new ArrayList<JavaDep>();
        for (var e : byKey.entrySet()) roots.add(pick(e.getKey(), e.getValue(), notes));

        List<String> repos = root.jvm().repos().isEmpty() ? List.of(CENTRAL) : root.jvm().repos();
        return new Plan(List.copyOf(requests), List.copyOf(roots), repos, List.copyOf(notes),
                List.copyOf(untrusted), trust != null);
    }

    private static JavaDep pick(String key, List<Request> asks, List<String> notes) throws DepsError {
        Request rootAsk = asks.stream().filter(r -> r.seed() == null).findFirst().orElse(null);
        if (rootAsk != null) {
            for (Request r : asks) {
                if (r.seed() != null && !r.dep().version().equals(rootAsk.dep().version())) {
                    notes.add(r.seed() + " asks for " + key + " " + r.dep().version()
                            + "; irij.toml pins " + rootAsk.dep().version());
                }
            }
            return rootAsk.dep();
        }
        Request best = asks.get(0);
        for (Request r : asks) {
            if (!major(r.dep().version()).equals(major(best.dep().version()))) {
                throw new DepsError("Seeds need different major versions of " + key + ": "
                        + best.who() + " asks for " + best.dep().version() + ", " + r.who() + " for "
                        + r.dep().version() + ". One program has one classpath; pin the version to use "
                        + "in irij.toml:\n  [jvm]\n  \"" + key + "\" = \"" + r.dep().version() + "\"");
            }
            if (compare(r.dep().version(), best.dep().version()) > 0) best = r;
        }
        for (Request r : asks) {
            if (!r.dep().version().equals(best.dep().version())) {
                notes.add(r.who() + " asks for " + key + " " + r.dep().version() + "; using "
                        + best.dep().version() + " (" + best.who() + ")");
            }
        }
        return best.dep();
    }

    /** The leading number of a version — {@code "5"} for {@code 5.28.5}. */
    static String major(String version) {
        int i = 0;
        while (i < version.length() && Character.isDigit(version.charAt(i))) i++;
        return version.substring(0, i);
    }

    static int compare(String a, String b) {
        try {
            var scheme = new GenericVersionScheme();
            return scheme.parseVersion(a).compareTo(scheme.parseVersion(b));
        } catch (org.eclipse.aether.version.InvalidVersionSpecificationException e) {
            return a.compareTo(b);
        }
    }

    // ── Fetching ────────────────────────────────────────────────────────

    /**
     * The jars {@code plan} resolves to, in classpath order, downloaded
     * into the jar cache. Uses {@code irij.lock} in {@code projectRoot}
     * when its inputs match the plan, and writes it otherwise.
     */
    public static List<Path> fetch(Path projectRoot, Plan plan, PrintStream out) throws IOException {
        if (plan.isEmpty()) return List.of();
        plan.requireTrusted();
        for (String n : plan.notes()) out.println("note: " + n);
        String inputs = inputsHash(plan);
        Path lockPath = projectRoot.resolve(LOCK_FILE);
        Lock lock = Lock.read(lockPath);

        silenceSlf4j();
        RepositorySystem system = new RepositorySystemSupplier().get();
        try (var session = new SessionBuilderSupplier(system).get()
                .withLocalRepositoryBaseDirectories(cacheDir())
                .setSystemProperties(System.getProperties())
                .setChecksumPolicy("fail")
                .setTransferListener(progress(out))
                .build()) {
            List<RemoteRepository> repos = repositories(plan.repos());
            if (lock != null && lock.inputs().equals(inputs)) {
                return fetchLocked(system, session, repos, lock, lockPath);
            }
            var jars = resolve(system, session, repos, plan);
            var entries = new ArrayList<Lock.Jar>();
            var paths = new ArrayList<Path>();
            for (var j : jars) {
                entries.add(new Lock.Jar(j.getKey(), sha256(j.getValue())));
                paths.add(j.getValue());
            }
            new Lock(inputs, entries).write(lockPath);
            out.println("Resolved " + entries.size() + " Java "
                    + (entries.size() == 1 ? "library" : "libraries") + " → " + LOCK_FILE);
            return paths;
        } finally {
            system.shutdown();
        }
    }

    private static List<Path> fetchLocked(RepositorySystem system, RepositorySystemSession session,
                                          List<RemoteRepository> repos, Lock lock, Path lockPath)
            throws IOException {
        var requests = new ArrayList<LockedRequest>();
        for (Lock.Jar j : lock.jars()) {
            requests.add(new LockedRequest(j, new ArtifactRequest(new DefaultArtifact(j.coord()), repos, null)));
        }
        List<ArtifactResult> results;
        try {
            results = system.resolveArtifacts(session, requests.stream().map(LockedRequest::request).toList());
        } catch (ArtifactResolutionException e) {
            throw new DepsError("Can't fetch the Java libraries " + lockPath.getFileName() + " pins: "
                    + rootMessage(e));
        }
        var paths = new ArrayList<Path>();
        for (int i = 0; i < results.size(); i++) {
            Lock.Jar want = requests.get(i).jar();
            Path p = results.get(i).getArtifact().getPath();
            String got = sha256(p);
            if (!got.equals(want.sha256())) {
                throw new DepsError(lockPath.getFileName() + " pins " + want.coord() + " at sha256 "
                        + want.sha256() + ", but the jar fetched is " + got + " (" + p + "). "
                        + "Refusing to load it: the repository or the cache served a different file.");
            }
            paths.add(p);
        }
        return paths;
    }

    private record LockedRequest(Lock.Jar jar, ArtifactRequest request) {}

    /** Resolve the plan transitively; coordinate → jar, classpath order. */
    private static List<Map.Entry<String, Path>> resolve(RepositorySystem system, RepositorySystemSession session,
                                                         List<RemoteRepository> repos, Plan plan)
            throws IOException {
        var collect = new CollectRequest();
        for (JavaDep d : plan.roots()) {
            collect.addDependency(new Dependency(
                    new DefaultArtifact(d.group(), d.artifact(), "jar", d.version()), JavaScopes.RUNTIME));
        }
        collect.setRepositories(repos);
        var request = new DependencyRequest(collect, DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
        List<ArtifactResult> results;
        try {
            results = system.resolveDependencies(session, request).getArtifactResults();
        } catch (DependencyResolutionException e) {
            throw new DepsError("Can't resolve the Java libraries in [jvm]: " + rootMessage(e));
        }
        var out = new ArrayList<Map.Entry<String, Path>>();
        for (ArtifactResult r : results) {
            Artifact a = r.getArtifact();
            if (a == null || a.getPath() == null || !"jar".equals(a.getExtension())) continue;
            out.add(Map.entry(a.toString(), a.getPath()));
        }
        return out;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t == e ? e.getMessage() : e.getMessage() + " (" + t.getMessage() + ")";
    }

    private static List<RemoteRepository> repositories(List<String> urls) {
        var out = new ArrayList<RemoteRepository>();
        for (int i = 0; i < urls.size(); i++) {
            String id = urls.get(i).equals(CENTRAL) ? "central" : "repo" + i;
            out.add(new RemoteRepository.Builder(id, "default", urls.get(i)).build());
        }
        return out;
    }

    private static AbstractTransferListener progress(PrintStream out) {
        return new AbstractTransferListener() {
            @Override public void transferSucceeded(TransferEvent event) {
                String name = event.getResource().getResourceName();
                if (name.endsWith(".jar")) {
                    out.println("Fetched " + name.substring(name.lastIndexOf('/') + 1));
                }
            }
        };
    }

    /** Where fetched jars live (a Maven-layout repository):
     *  {@code ~/.irij/jars}, or the {@code irij.jars.dir} property. */
    public static Path cacheDir() {
        String dir = System.getProperty("irij.jars.dir");
        return dir != null ? Path.of(dir) : Path.of(System.getProperty("user.home"), ".irij", "jars");
    }

    /** Maven Resolver logs through SLF4J; with no provider on the CLI's
     *  classpath SLF4J prints a three-line warning. Not a user's concern. */
    private static void silenceSlf4j() {
        if (System.getProperty("slf4j.internal.verbosity") == null) {
            System.setProperty("slf4j.internal.verbosity", "ERROR");
        }
    }

    /** Identifies a plan: the lock is current while this matches. */
    static String inputsHash(Plan plan) {
        var sb = new StringBuilder("irij.lock v").append(LOCK_VERSION).append('\n');
        for (String r : plan.repos()) sb.append("repo ").append(r).append('\n');
        for (JavaDep d : plan.roots()) sb.append("dep ").append(d.coord()).append('\n');
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── irij.lock ───────────────────────────────────────────────────────

    /** {@code irij.lock}: the plan's inputs hash and every jar it resolved to. */
    public record Lock(String inputs, List<Jar> jars) {
        public record Jar(String coord, String sha256) {}

        /** The lock at {@code path}, or null when there is none or it isn't
         *  one this version writes (it is then resolved again). */
        public static Lock read(Path path) throws IOException {
            if (!Files.exists(path)) return null;
            com.moandjiezana.toml.Toml toml;
            try {
                toml = new com.moandjiezana.toml.Toml().read(Files.readString(path));
            } catch (RuntimeException e) {
                throw new DepsError(path.getFileName() + " is not valid TOML (" + e.getMessage()
                        + "). Delete it to resolve [jvm] again.");
            }
            Long version = toml.getLong("version");
            String inputs = toml.getString("inputs");
            if (version == null || version != LOCK_VERSION || inputs == null) return null;
            var jars = new ArrayList<Jar>();
            List<com.moandjiezana.toml.Toml> tables = toml.getTables("jar");
            if (tables != null) {
                for (var t : tables) {
                    String coord = t.getString("coord");
                    String sha = t.getString("sha256");
                    if (coord == null || sha == null || !sha.matches("[0-9a-f]{64}")) {
                        throw new DepsError(path.getFileName() + " has a [[jar]] without a coord and "
                                + "sha256. Delete it to resolve [jvm] again.");
                    }
                    jars.add(new Jar(coord, sha));
                }
            }
            return new Lock(inputs, List.copyOf(jars));
        }

        public void write(Path path) throws IOException {
            var sb = new StringBuilder();
            sb.append("# irij.lock: the Java libraries irij.toml [jvm] resolved to, with their\n");
            sb.append("# sha256. Written by irij; commit it. A changed [jvm] rewrites it.\n");
            sb.append("version = ").append(LOCK_VERSION).append('\n');
            sb.append("inputs = \"").append(inputs).append("\"\n");
            for (Jar j : jars) {
                sb.append("\n[[jar]]\n");
                sb.append("coord = \"").append(j.coord()).append("\"\n");
                sb.append("sha256 = \"").append(j.sha256()).append("\"\n");
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** The locked jars when {@code irij.lock} matches {@code plan}; null
     *  when it's missing or out of date (the next run resolves again). */
    public static List<Lock.Jar> lockedJars(Path projectRoot, Plan plan) throws IOException {
        Lock lock = Lock.read(projectRoot.resolve(LOCK_FILE));
        return lock != null && lock.inputs().equals(inputsHash(plan)) ? lock.jars() : null;
    }

    /** Requests grouped by who made them, for listings. */
    public static Map<String, List<JavaDep>> byDeclarer(Plan plan) {
        var out = new LinkedHashMap<String, List<JavaDep>>();
        for (Request r : plan.requests()) out.computeIfAbsent(r.who(), k -> new ArrayList<>()).add(r.dep());
        return out;
    }
}
