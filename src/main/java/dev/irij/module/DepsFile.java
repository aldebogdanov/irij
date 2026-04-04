package dev.irij.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for {@code deps.irj} dependency manifest files.
 *
 * <p>Format:
 * <pre>
 * ;; deps.irj
 * dep utils
 *   git "https://github.com/user/irij-utils.git"
 *   tag "v0.1.0"
 *
 * dep http-extra
 *   git "https://github.com/user/irij-http.git"
 *   commit "a7f3b2c1"
 *
 * dep local-lib
 *   path "../my-lib"
 * </pre>
 *
 * <p>Each dependency has a name and either a git source (with tag or commit) or a local path.
 */
public final class DepsFile {

    /** A single dependency declaration. */
    public sealed interface DepSource {
        /** Git repository dependency. */
        record GitDep(String url, String ref) implements DepSource {}
        /** Local path dependency (for development). */
        record PathDep(String path) implements DepSource {}
    }

    /** A named dependency with its source. */
    public record Dependency(String name, DepSource source) {}

    private DepsFile() {}

    /**
     * Parse a deps.irj file at the given path.
     * Returns empty list if file does not exist.
     */
    public static List<Dependency> parse(Path depsFile) throws IOException {
        if (!Files.exists(depsFile)) return List.of();
        return parse(Files.readString(depsFile));
    }

    /** Parse deps.irj content from a string. */
    public static List<Dependency> parse(String content) {
        var deps = new ArrayList<Dependency>();
        var lines = content.split("\n");

        int i = 0;
        while (i < lines.length) {
            var line = lines[i].trim();

            // Skip blank lines and comments
            if (line.isEmpty() || line.startsWith(";;")) {
                i++;
                continue;
            }

            // Expect "dep <name>"
            if (line.startsWith("dep ")) {
                var name = line.substring(4).trim();
                if (name.isEmpty()) {
                    throw new DepsParseError("Missing dependency name at line " + (i + 1));
                }

                // Parse indented properties
                String gitUrl = null;
                String ref = null;
                String path = null;
                i++;

                while (i < lines.length) {
                    var propLine = lines[i];
                    // Must be indented (part of this dep block)
                    if (propLine.isEmpty() || propLine.charAt(0) != ' ') break;
                    var prop = propLine.trim();
                    if (prop.isEmpty() || prop.startsWith(";;")) {
                        i++;
                        continue;
                    }

                    if (prop.startsWith("git ")) {
                        gitUrl = unquote(prop.substring(4).trim(), i + 1);
                    } else if (prop.startsWith("tag ")) {
                        ref = unquote(prop.substring(4).trim(), i + 1);
                    } else if (prop.startsWith("commit ")) {
                        ref = unquote(prop.substring(7).trim(), i + 1);
                    } else if (prop.startsWith("path ")) {
                        path = unquote(prop.substring(5).trim(), i + 1);
                    } else {
                        throw new DepsParseError("Unknown property '" + prop.split(" ")[0]
                            + "' at line " + (i + 1));
                    }
                    i++;
                }

                // Build dependency
                if (gitUrl != null) {
                    if (ref == null) {
                        throw new DepsParseError("Git dependency '" + name
                            + "' requires a 'tag' or 'commit'");
                    }
                    deps.add(new Dependency(name, new DepSource.GitDep(gitUrl, ref)));
                } else if (path != null) {
                    deps.add(new Dependency(name, new DepSource.PathDep(path)));
                } else {
                    throw new DepsParseError("Dependency '" + name
                        + "' must have either 'git' or 'path'");
                }
            } else {
                throw new DepsParseError("Expected 'dep <name>' at line " + (i + 1)
                    + ", got: " + line);
            }
        }

        return List.copyOf(deps);
    }

    /** Remove surrounding double quotes from a string value. */
    private static String unquote(String s, int line) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        throw new DepsParseError("Expected quoted string at line " + line + ", got: " + s);
    }

    /** Error thrown when deps.irj has syntax errors. */
    public static final class DepsParseError extends RuntimeException {
        public DepsParseError(String message) { super(message); }
    }
}
