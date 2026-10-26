package dev.irij.module;

import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for {@code irij.toml} project manifest files.
 *
 * <p>Format:
 * <pre>
 * [project]
 * name = "my-app"
 * version = "0.1.0"
 * description = "My Irij application"
 * author = "user"
 * license = "MIT"
 * website = "https://example.org"       # optional — shown on the registry seed page
 * repo = "https://github.com/u/my-app"  # optional
 * docs = "https://example.org/docs"     # optional
 *
 * [seeds]
 * vrata = "0.1.1"                                          # registry shorthand
 * utils = { git = "https://github.com/user/utils.git", tag = "v1.0" }  # git inline
 * local-lib = { path = "../my-lib" }                        # path inline
 *
 * [jvm]                           # optional — Java libraries, see {@link Jvm}
 * "org.neo4j.driver:neo4j-java-driver" = "5.28.5"
 *
 * [trust]                         # optional — see {@link Trust}
 * caps = ["acme.clock"]           # modules that may bind a Java class with `cap`
 * jvm = ["seed-readme"]           # modules that may write Java interop by hand
 * jars = ["neo4irij"]             # seeds that may bring Java libraries
 * </pre>
 *
 * <p>Seeds (dependencies) support three source types:
 * <ul>
 *   <li><b>Registry</b> — bare string version: {@code vrata = "0.1.1"}</li>
 *   <li><b>Git</b> — table with {@code git} URL and {@code tag} or {@code commit}</li>
 *   <li><b>Path</b> — table with {@code path} (relative to project root, dev only)</li>
 * </ul>
 *
 * <p>The {@code [project]} section is optional metadata for the seed registry.
 */
public final class ProjectFile {

    /** A single seed (dependency) source. */
    public sealed interface DepSource {
        /** Registry seed — resolved from the Irij seed registry. */
        record RegistryDep(String version) implements DepSource {}
        /** Git repository seed. */
        record GitDep(String url, String ref) implements DepSource {}
        /** Local path seed (for development). */
        record PathDep(String path) implements DepSource {}
    }

    /** A named seed with its source. */
    public record Dependency(String name, DepSource source) {}

    /** Project metadata from [project] section. */
    public record ProjectMeta(
        String name,
        String version,
        String description,
        String author,
        String license,
        String website,
        String repo,
        String docs
    ) {}

    /** A Java library from {@code [jvm]}: a Maven coordinate at an exact version. */
    public record JavaDep(String group, String artifact, String version) {
        /** {@code group:artifact} — the key two requests for one library share. */
        public String key() { return group + ":" + artifact; }
        public String coord() { return key() + ":" + version; }
        @Override public String toString() { return coord(); }
    }

    /**
     * The {@code [jvm]} section: Java libraries the project or seed needs
     * on the classpath. Keys are quoted {@code "group:artifact"}
     * coordinates, values exact versions (no ranges, no {@code LATEST},
     * no snapshots — what a manifest names must mean one jar forever).
     * {@code repos} lists Maven repository URLs to fetch from instead of
     * Maven Central; only the root project's are used, and a seed that
     * sets them is refused (it would choose where your build downloads
     * code from).
     */
    public record Jvm(List<JavaDep> deps, List<String> repos) {
        static final Jvm EMPTY = new Jvm(List.of(), List.of());
        public boolean isEmpty() { return deps.isEmpty() && repos.isEmpty(); }
    }

    /**
     * The {@code [trust]} section: which modules may reach Java. With it,
     * the compiler refuses a Java {@code cap} declaration in a module not
     * matched by {@code caps}, and hand-written Java interop
     * ({@code Class/member}, {@code obj#member}, {@code #member}) in a
     * module not matched by {@code jvm} — so "anything can happen through
     * Java" is confined to the modules listed. Without the section nothing
     * is enforced. Patterns are a module name ({@code "acme.clock"}) or a
     * prefix ({@code "acme.*"}: {@code acme} and everything under it); the
     * entry program (a file without {@code mod}) is {@code "main"}. The
     * standard library ({@code std.*}) is always trusted. Seeds are
     * covered too: the policy is the root project's. {@code jars} names
     * the seeds (by their name in {@code [seeds]}, same pattern rules)
     * whose {@code [jvm]} libraries may be fetched and loaded; the root
     * project's own {@code [jvm]} needs no entry.
     */
    public record Trust(List<String> caps, List<String> jvm, List<String> jars) {
        public Trust(List<String> caps, List<String> jvm) { this(caps, jvm, List.of()); }

        public boolean allowsCaps(String module) { return matches(caps, module); }
        public boolean allowsJvm(String module) { return matches(jvm, module); }
        public boolean allowsJars(String seed) { return matches(jars, seed); }

        public static boolean isStd(String module) {
            return module != null && (module.equals("std") || module.startsWith("std."));
        }

        private static boolean matches(List<String> patterns, String module) {
            if (isStd(module)) return true;
            String m = module == null ? "main" : module;
            for (String p : patterns) {
                if (p.equals(m)) return true;
                if (p.endsWith(".*")) {
                    String prefix = p.substring(0, p.length() - 2);
                    if (m.equals(prefix) || m.startsWith(prefix + ".")) return true;
                }
            }
            return false;
        }
    }

    private ProjectFile() {}

    /**
     * Parse an irij.toml file at the given path.
     * Returns empty result if file does not exist.
     */
    public static ParseResult parseFile(Path tomlFile) throws IOException {
        if (!Files.exists(tomlFile)) return ParseResult.EMPTY;
        return parse(Files.readString(tomlFile));
    }

    /** Parse irij.toml content from a string. */
    public static ParseResult parse(String content) {
        var toml = new Toml().read(content);

        // Parse [project] section
        ProjectMeta meta = null;
        var projectTable = toml.getTable("project");
        if (projectTable != null) {
            meta = new ProjectMeta(
                projectTable.getString("name", ""),
                projectTable.getString("version", ""),
                projectTable.getString("description", ""),
                projectTable.getString("author", ""),
                projectTable.getString("license", ""),
                projectTable.getString("website", ""),
                projectTable.getString("repo", ""),
                projectTable.getString("docs", "")
            );
        }

        // Parse [seeds] section
        var seeds = new ArrayList<Dependency>();
        var seedsTable = toml.getTable("seeds");
        if (seedsTable != null) {
            for (var entry : seedsTable.entrySet()) {
                var seedName = entry.getKey();
                var value = entry.getValue();

                if (value instanceof String version) {
                    // Registry shorthand: vrata = "0.1.1"
                    seeds.add(new Dependency(seedName, new DepSource.RegistryDep(version)));
                } else if (value instanceof com.moandjiezana.toml.Toml seedToml) {
                    // Full table: [seeds.name] with git/path/version keys
                    seeds.add(buildSeed(seedName, seedToml));
                } else {
                    // Inline table comes as HashMap from toml4j
                    var seedSubTable = seedsTable.getTable(seedName);
                    if (seedSubTable != null) {
                        seeds.add(buildSeed(seedName, seedSubTable));
                    } else {
                        throw new ParseError("Seed '" + seedName
                            + "' must be a version string or table");
                    }
                }
            }
        }

        return new ParseResult(meta, List.copyOf(seeds), parseTrust(toml), parseJvm(toml));
    }

    /** A Maven group or artifact id: dots, dashes and underscores between
     *  letters and digits — never a path, never empty. */
    private static final java.util.regex.Pattern MAVEN_ID =
        java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    /** An exact version. Ranges ({@code [1,2)}), {@code ${props}} and
     *  spaces fail this; {@code LATEST}/{@code RELEASE}/snapshots are
     *  refused by name below. */
    private static final java.util.regex.Pattern MAVEN_VERSION =
        java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

    private static Jvm parseJvm(Toml toml) {
        var t = toml.getTable("jvm");
        if (t == null) return Jvm.EMPTY;
        var deps = new ArrayList<JavaDep>();
        var repos = List.<String>of();
        for (var e : t.toMap().entrySet()) {
            String key = unquote(e.getKey());
            Object value = e.getValue();
            if (key.equals("repos")) {
                repos = repoList(value);
                continue;
            }
            String[] ga = key.split(":", -1);
            if (ga.length != 2 || !MAVEN_ID.matcher(ga[0]).matches()
                    || !MAVEN_ID.matcher(ga[1]).matches() || key.contains("..")) {
                throw new ParseError("[jvm] key '" + key + "' must be a quoted Maven coordinate "
                        + "\"group:artifact\", like \"org.neo4j.driver:neo4j-java-driver\" = \"5.28.5\"");
            }
            if (!(value instanceof String v)) {
                throw new ParseError("[jvm] \"" + key + "\" must be a version string, like \"1.2.3\"");
            }
            if (!MAVEN_VERSION.matcher(v).matches() || v.contains("..") || v.equals("LATEST")
                    || v.equals("RELEASE") || v.endsWith("-SNAPSHOT")) {
                throw new ParseError("[jvm] \"" + key + "\" = \"" + v + "\": the version must be exact "
                        + "(no ranges, LATEST, RELEASE or -SNAPSHOT), so it names one jar forever");
            }
            deps.add(new JavaDep(ga[0], ga[1], v));
        }
        deps.sort(java.util.Comparator.comparing(JavaDep::coord));
        return new Jvm(List.copyOf(deps), repos);
    }

    private static List<String> repoList(Object value) {
        String usage = "[jvm] repos must be a list of http(s):// or file: URLs";
        if (!(value instanceof List<?> raw)) throw new ParseError(usage);
        var out = new ArrayList<String>();
        for (Object o : raw) {
            if (!(o instanceof String url)
                    || !(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file:"))) {
                throw new ParseError(usage);
            }
            out.add(url);
        }
        return List.copyOf(out);
    }

    /** toml4j hands a quoted key back with its quotes. */
    private static String unquote(String key) {
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static Trust parseTrust(Toml toml) {
        var t = toml.getTable("trust");
        if (t == null) return null;
        for (String key : t.toMap().keySet()) {
            if (!key.equals("caps") && !key.equals("jvm") && !key.equals("jars")) {
                throw new ParseError("[trust] has an unknown key '" + key
                        + "' — it takes `caps` and `jvm` (lists of module names) and `jars` (seed names)");
            }
        }
        return new Trust(stringList(t, "caps"), stringList(t, "jvm"), stringList(t, "jars"));
    }

    private static List<String> stringList(Toml t, String key) {
        if (!t.contains(key)) return List.of();
        List<Object> raw;
        try {
            raw = t.getList(key);
        } catch (ClassCastException e) {
            throw new ParseError("[trust] " + key + " must be a list of module names, like [\"acme.clock\"]");
        }
        var out = new ArrayList<String>();
        for (Object o : raw) {
            if (!(o instanceof String str) || str.isBlank()) {
                throw new ParseError("[trust] " + key + " must be a list of module names, like [\"acme.clock\"]");
            }
            out.add(str);
        }
        return List.copyOf(out);
    }

    /** Extract just the seed list (convenience for loadDeps). */
    public static List<Dependency> parseDeps(Path tomlFile) throws IOException {
        return parseFile(tomlFile).deps();
    }

    private static Dependency buildSeed(String name, Toml table) {
        var gitUrl = table.getString("git");
        var tag = table.getString("tag");
        var commit = table.getString("commit");
        var path = table.getString("path");
        var version = table.getString("version");

        if (gitUrl != null) {
            var ref = tag != null ? tag : commit;
            if (ref == null) {
                throw new ParseError("Git seed '" + name + "' requires 'tag' or 'commit'");
            }
            return new Dependency(name, new DepSource.GitDep(gitUrl, ref));
        } else if (path != null) {
            return new Dependency(name, new DepSource.PathDep(path));
        } else if (version != null) {
            return new Dependency(name, new DepSource.RegistryDep(version));
        } else {
            throw new ParseError("Seed '" + name
                + "' must have 'git', 'path', or 'version'");
        }
    }

    /** Result of parsing irij.toml. {@code trust} is null when irij.toml
     *  has no {@code [trust]}; {@code jvm} is empty without {@code [jvm]}. */
    public record ParseResult(ProjectMeta meta, List<Dependency> deps, Trust trust, Jvm jvm) {
        static final ParseResult EMPTY = new ParseResult(null, List.of(), null, Jvm.EMPTY);
    }

    /** Error thrown when irij.toml has syntax errors. */
    public static final class ParseError extends RuntimeException {
        public ParseError(String message) { super(message); }
    }
}
