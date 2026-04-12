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
 *
 * [seeds]
 * vrata = "0.1.1"                                          # registry shorthand
 * utils = { git = "https://github.com/user/utils.git", tag = "v1.0" }  # git inline
 * local-lib = { path = "../my-lib" }                        # path inline
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
        String license
    ) {}

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
                projectTable.getString("license", "")
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

        return new ParseResult(meta, List.copyOf(seeds));
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
    public record ParseResult(ProjectMeta meta, List<Dependency> deps) {
        static final ParseResult EMPTY = new ParseResult(null, List.of());
    }

    /** Error thrown when irij.toml has syntax errors. */
    public static final class ParseError extends RuntimeException {
        public ParseError(String message) { super(message); }
    }
}
