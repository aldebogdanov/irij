package dev.irij.module;

import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * [deps.vrata]
 * git = "https://github.com/aldebogdanov/vrata.git"
 * tag = "v0.1.1"
 *
 * [deps.local-lib]
 * path = "../my-lib"
 * </pre>
 *
 * <p>Each dependency is a TOML table under {@code [deps.<name>]}.
 * Git dependencies require a {@code git} URL and either {@code tag} or {@code commit}.
 * Local dependencies use {@code path} (relative to project root).
 *
 * <p>The {@code [project]} section is optional metadata for the package registry.
 */
public final class ProjectFile {

    /** A single dependency declaration. */
    public sealed interface DepSource {
        /** Git repository dependency. */
        record GitDep(String url, String ref) implements DepSource {}
        /** Local path dependency (for development). */
        record PathDep(String path) implements DepSource {}
    }

    /** A named dependency with its source. */
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

        // Parse [deps.*] sections
        var deps = new ArrayList<Dependency>();
        var depsTable = toml.getTable("deps");
        if (depsTable != null) {
            for (var entry : depsTable.entrySet()) {
                var depName = entry.getKey();
                if (!(entry.getValue() instanceof com.moandjiezana.toml.Toml depToml)) {
                    // Try as a table from the map representation
                    var depMap = depsTable.getTable(depName);
                    if (depMap == null) {
                        throw new ParseError("Dependency '" + depName + "' must be a TOML table");
                    }
                    deps.add(buildDep(depName, depMap));
                    continue;
                }
                deps.add(buildDepFromToml(depName, depToml));
            }
        }

        return new ParseResult(meta, List.copyOf(deps));
    }

    /** Extract just the dependency list (convenience for loadDeps). */
    public static List<Dependency> parseDeps(Path tomlFile) throws IOException {
        return parseFile(tomlFile).deps();
    }

    private static Dependency buildDepFromToml(String name, Toml table) {
        var gitUrl = table.getString("git");
        var tag = table.getString("tag");
        var commit = table.getString("commit");
        var path = table.getString("path");

        if (gitUrl != null) {
            var ref = tag != null ? tag : commit;
            if (ref == null) {
                throw new ParseError("Git dependency '" + name + "' requires 'tag' or 'commit'");
            }
            return new Dependency(name, new DepSource.GitDep(gitUrl, ref));
        } else if (path != null) {
            return new Dependency(name, new DepSource.PathDep(path));
        } else {
            throw new ParseError("Dependency '" + name + "' must have 'git' or 'path'");
        }
    }

    private static Dependency buildDep(String name, Toml table) {
        return buildDepFromToml(name, table);
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
