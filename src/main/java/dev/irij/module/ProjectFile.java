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
 * [trust]                         # optional — see {@link Trust}
 * caps = ["acme.clock"]           # modules that may bind a Java class with `cap`
 * jvm = ["seed-readme"]           # modules that may write Java interop by hand
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
     * covered too: the policy is the root project's.
     */
    public record Trust(List<String> caps, List<String> jvm) {
        public boolean allowsCaps(String module) { return matches(caps, module); }
        public boolean allowsJvm(String module) { return matches(jvm, module); }

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

        return new ParseResult(meta, List.copyOf(seeds), parseTrust(toml));
    }

    private static Trust parseTrust(Toml toml) {
        var t = toml.getTable("trust");
        if (t == null) return null;
        for (String key : t.toMap().keySet()) {
            if (!key.equals("caps") && !key.equals("jvm")) {
                throw new ParseError("[trust] has an unknown key '" + key
                        + "' — it takes `caps` and `jvm`, lists of module names");
            }
        }
        return new Trust(stringList(t, "caps"), stringList(t, "jvm"));
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

    /** Result of parsing irij.toml. */
    /** {@code trust} is null when irij.toml has no {@code [trust]}. */
    public record ParseResult(ProjectMeta meta, List<Dependency> deps, Trust trust) {
        static final ParseResult EMPTY = new ParseResult(null, List.of(), null);
    }

    /** Error thrown when irij.toml has syntax errors. */
    public static final class ParseError extends RuntimeException {
        public ParseError(String message) { super(message); }
    }
}
